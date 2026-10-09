#include <jni.h>
#include <cerrno>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>
#include <algorithm>
#include <dlfcn.h>
#include <mutex>
#include <android/log.h>
extern "C" JavaVM* jniVm;
#include <freerdp/addin.h>
#include <media/NdkMediaCodec.h>
#include <openssl/pem.h>
#include <openssl/x509.h>
#include <freerdp/freerdp.h>
#include <freerdp/transport_io.h>
#include <freerdp/client.h>
#include <freerdp/client/cmdline.h>
#include <freerdp/client/channels.h>
#include <freerdp/client/rdpgfx.h>
#include <freerdp/channels/rdpgfx.h>
#include <freerdp/gdi/gdi.h>
#include <freerdp/gdi/gfx.h>
#include <freerdp/graphics.h>
#include <freerdp/codec/color.h>
#include <freerdp/input.h>
#include <freerdp/update.h>
#include <winpr/synch.h>
#include <winpr/path.h>
#include "clipboard.h"

struct Session {
    rdpContext base;
    JNIEnv* env;
    jobject owner;
    HANDLE event;
    jmethodID read, write, wait, closed, paused, command, certificate, size, pixels, frame, cursor, encoding, failure, stats, takeClipboard, clipboardReceived, clipboardFailure;
    pcRdpgfxSurfaceCommand surfaceCommand;
    std::string* decoder;
    Clipboard* clipboard;
    bool callbackFailed;
    int buttons;
};
static thread_local Session* active = nullptr;
static thread_local std::string runtimeDirectory;
extern "C" char* __real_GetKnownPath(eKnownPathTypes);
extern "C" char* __wrap_GetKnownPath(eKnownPathTypes id) {
    if (!runtimeDirectory.empty()) return strdup(runtimeDirectory.c_str());
    return __real_GetKnownPath(id);
}
extern "C" char* __real_freerdp_GetConfigFilePath(BOOL, const char*);
extern "C" char* __wrap_freerdp_GetConfigFilePath(BOOL system, const char* filename) {
    if (!runtimeDirectory.empty()) return GetCombinedPath(runtimeDirectory.c_str(),filename ? filename : "");
    return __real_freerdp_GetConfigFilePath(system,filename);
}
extern "C" AMediaCodec* __real_AMediaCodec_createDecoderByType(const char*);
extern "C" AMediaCodec* __wrap_AMediaCodec_createDecoderByType(const char* mime) {
    auto* codec = __real_AMediaCodec_createDecoderByType(mime);
    using GetName = media_status_t (*)(AMediaCodec*, char**);
    using ReleaseName = void (*)(AMediaCodec*, char*);
    auto getName = reinterpret_cast<GetName>(dlsym(RTLD_DEFAULT, "AMediaCodec_getName"));
    auto releaseName = reinterpret_cast<ReleaseName>(dlsym(RTLD_DEFAULT, "AMediaCodec_releaseName"));
    char* name = nullptr;
    if (codec && active && getName && releaseName && getName(codec, &name) == AMEDIA_OK) {
        *active->decoder = name;
        releaseName(codec, name);
    } else if (codec && active) *active->decoder = "MediaCodec (name unavailable)";
    return codec;
}
static Session* session(rdpContext* c) { return reinterpret_cast<Session*>(c); }
static bool exception(Session* s) {
    if (!s->env->ExceptionCheck()) return false;
    // Leave the Java exception pending for nativeRun's caller; stop all subsequent callbacks.
    s->callbackFailed = true;
    return true;
}
static bool stopped(Session* s) {
    if (s->callbackFailed || exception(s)) return true;
    auto result = s->env->CallBooleanMethod(s->owner, s->closed);
    return exception(s) || result;
}
extern "C" BOOL miffan_rdp_decode_cancelled(void) {
    return active && stopped(active);
}
static int readLayer(void* context, void* data, int bytes) {
    auto* s = static_cast<Session*>(context);
    if (stopped(s)) { errno = EIO; return -1; }
    auto array = s->env->NewByteArray(bytes);
    if (!array) { exception(s); errno = ENOMEM; return -1; }
    int result = s->env->CallIntMethod(s->owner, s->read, array);
    if (exception(s)) result = -1;
    if (result > 0) s->env->GetByteArrayRegion(array, 0, result, static_cast<jbyte*>(data));
    s->env->DeleteLocalRef(array);
    if (result == 0) { errno = EAGAIN; return -1; }
    if (result < 0) return 0; // orderly EOF
    return result;
}
static int writeLayer(void* context, const void* data, int bytes) {
    auto* s = static_cast<Session*>(context);
    if (stopped(s)) { errno = EIO; return -1; }
    auto array = s->env->NewByteArray(bytes);
    if (!array) { exception(s); errno = ENOMEM; return -1; }
    s->env->SetByteArrayRegion(array, 0, bytes, static_cast<const jbyte*>(data));
    bool result = s->env->CallBooleanMethod(s->owner, s->write, array);
    s->env->DeleteLocalRef(array);
    if (exception(s) || !result) { errno = EIO; return -1; }
    return bytes;
}
static BOOL closeLayer(void*) { return TRUE; } // Kotlin owns the transport
static BOOL waitLayer(void* context, BOOL writing, DWORD timeout) {
    auto* s = static_cast<Session*>(context);
    if (stopped(s)) return FALSE;
    if (writing) return TRUE;
    bool result = s->env->CallBooleanMethod(s->owner, s->wait, static_cast<jint>(std::min<DWORD>(timeout, 50)));
    return !exception(s) && result;
}
static HANDLE eventLayer(void* context) { return static_cast<Session*>(context)->event; }
static rdpTransportLayer* connectLayer(rdpTransport* transport, const char*, int, DWORD) {
    auto* s = session(transport_get_context(transport));
    __android_log_print(ANDROID_LOG_INFO,"MiffanRdp","Attaching byte stream");
    auto* layer = transport_layer_new(transport, 0);
    if (!layer) return nullptr;
    layer->userContext = s;
    layer->Read = readLayer; layer->Write = writeLayer; layer->Close = closeLayer;
    layer->Wait = waitLayer; layer->GetEvent = eventLayer;
    return layer;
}
static int verify(freerdp* instance, const BYTE* data, size_t length, const char*, UINT16, DWORD) {
    auto* s = session(instance->context);
    BIO* bio = BIO_new_mem_buf(data, static_cast<int>(length));
    X509* cert = bio ? PEM_read_bio_X509(bio, nullptr, nullptr, nullptr) : nullptr;
    unsigned char digest[EVP_MAX_MD_SIZE]; unsigned int size = 0;
    bool ok = cert && X509_digest(cert, EVP_sha256(), digest, &size) && size == 32;
    X509_free(cert); BIO_free(bio);
    if (!ok || stopped(s)) return 0;
    char fingerprint[65];
    for (unsigned int i = 0; i < size; ++i) snprintf(fingerprint + 2*i, 3, "%02x", digest[i]);
    auto value = s->env->NewStringUTF(fingerprint);
    bool accepted = s->env->CallBooleanMethod(s->owner, s->certificate, value);
    s->env->DeleteLocalRef(value);
    return !exception(s) && accepted ? 1 : 0;
}
static void announce(Session* s) {
    auto* c = &s->base;
    auto selected = freerdp_settings_get_uint32(c->settings, FreeRDP_SelectedProtocol);
    auto security = s->env->NewStringUTF(selected == 1 ? "TLS" : "NLA");
    s->env->CallVoidMethod(s->owner, s->size, c->gdi->width, c->gdi->height, security);
    s->env->DeleteLocalRef(security);
    exception(s);
}
static BOOL resize(rdpContext* c) {
    auto w = freerdp_settings_get_uint32(c->settings, FreeRDP_DesktopWidth);
    auto h = freerdp_settings_get_uint32(c->settings, FreeRDP_DesktopHeight);
    if (w > 8192 || h > 8192 || !gdi_resize(c->gdi, w, h)) return FALSE;
    announce(session(c)); return !session(c)->callbackFailed;
}
static BOOL beginPaint(rdpContext*) { return TRUE; }
static BOOL endPaint(rdpContext* c) {
    auto* s = session(c);
    if (stopped(s)) return FALSE;
    auto* gdi = c->gdi;
    auto* window = gdi->primary->hdc->hwnd;
    bool paused = s->env->CallBooleanMethod(s->owner, s->paused);
    if (exception(s)) return FALSE;
    bool painted = false;
    for (int i = 0; !paused && i < window->ninvalid; ++i) {
        auto r = window->cinvalid[i];
        int x = std::max(0, r.x), y = std::max(0, r.y);
        int w = std::min(static_cast<int>(gdi->width), r.x+r.w) - x;
        int h = std::min(static_cast<int>(gdi->height), r.y+r.h) - y;
        if (w <= 0 || h <= 0) continue;
        auto array = s->env->NewIntArray(w*h);
        if (!array) { exception(s); return FALSE; }
        auto* pixels = s->env->GetIntArrayElements(array, nullptr);
        if (!pixels) { s->env->DeleteLocalRef(array); exception(s); return FALSE; }
        bool ok = freerdp_image_copy(reinterpret_cast<BYTE*>(pixels), PIXEL_FORMAT_BGRA32, w*4, 0, 0,
            w, h, gdi->primary_buffer, gdi->dstFormat, gdi->stride, x, y, nullptr, FREERDP_FLIP_NONE);
        for (int p = 0; p < w*h; ++p) pixels[p] |= 0xff000000;
        s->env->ReleaseIntArrayElements(array, pixels, 0);
        if (ok) s->env->CallVoidMethod(s->owner, s->pixels, x,y,w,h,array);
        s->env->DeleteLocalRef(array);
        if (!ok || exception(s)) return FALSE;
        painted = true;
    }
    window->invalid->null = TRUE; window->ninvalid = 0;
    if (painted) { s->env->CallVoidMethod(s->owner, s->frame); if (exception(s)) return FALSE; }
    return TRUE;
}
struct Pointer { rdpPointer base; BYTE* pixels; };
static BOOL pointerNew(rdpContext* c, rdpPointer* p) {
    if (p->width > 384 || p->height > 384) return FALSE;
    auto* ptr = reinterpret_cast<Pointer*>(p);
    ptr->pixels = static_cast<BYTE*>(calloc(p->width*p->height,4));
    bool ok = ptr->pixels && freerdp_image_copy_from_pointer_data(ptr->pixels, PIXEL_FORMAT_BGRA32, p->width*4, 0, 0,
        p->width,p->height,p->xorMaskData,p->lengthXorMask,p->andMaskData,p->lengthAndMask,p->xorBpp, &c->gdi->palette);
    if (!ok) { free(ptr->pixels); ptr->pixels = nullptr; }
    return ok;
}
static void pointerFree(rdpContext*, rdpPointer* p) { free(reinterpret_cast<Pointer*>(p)->pixels); }
static BOOL cursor(Session* s, int w,int h,int x,int y,const BYTE* data) {
    if (stopped(s)) return FALSE;
    auto array = s->env->NewIntArray(w*h);
    if (!array) { exception(s); return FALSE; }
    if (data) s->env->SetIntArrayRegion(array, 0, w*h, reinterpret_cast<const jint*>(data));
    s->env->CallVoidMethod(s->owner,s->cursor,w,h,x,y,array);
    s->env->DeleteLocalRef(array); return !exception(s);
}
static BOOL pointerSet(rdpContext* c, rdpPointer* p) {
    return cursor(session(c),p->width,p->height,p->xPos,p->yPos,reinterpret_cast<Pointer*>(p)->pixels);
}
static BOOL pointerNull(rdpContext* c) { return cursor(session(c),0,0,0,0,nullptr); }
static BOOL pointerDefault(rdpContext* c) {
    // A small standard arrow for servers using SYSTEM_DEFAULT rather than sending a shape.
    UINT32 data[16*24] = {};
    for (int y=0;y<20;y++) for (int x=0;x<=y/2;x++) data[y*16+x] = (x==0 || x==y/2 || y==19) ? 0xff000000 : 0xffffffff;
    return cursor(session(c),16,24,0,0,reinterpret_cast<BYTE*>(data));
}
static BOOL pointerPosition(rdpContext*,UINT32,UINT32) { return TRUE; }
static UINT surfaceCommand(RdpgfxClientContext* gfx, const RDPGFX_SURFACE_COMMAND* cmd) {
    auto* gdi = static_cast<rdpGdi*>(gfx->custom);
    auto* s = session(gdi->context);
    auto result = s->surfaceCommand(gfx, cmd);
    if (result != CHANNEL_RC_OK || stopped(s)) return result;
    const char* encoding = "Unknown";
    switch (cmd->codecId) {
        case RDPGFX_CODECID_AVC420: encoding="AVC420"; break;
        case RDPGFX_CODECID_AVC444: case RDPGFX_CODECID_AVC444v2: encoding="AVC444"; break;
        case RDPGFX_CODECID_CAPROGRESSIVE: case RDPGFX_CODECID_CAVIDEO: encoding="RFX"; break;
        case RDPGFX_CODECID_PLANAR: encoding="Planar"; break;
        case RDPGFX_CODECID_UNCOMPRESSED: encoding="Raw"; break;
    }
    auto enc = s->env->NewStringUTF(encoding);
    bool avc = cmd->codecId == RDPGFX_CODECID_AVC420 || cmd->codecId == RDPGFX_CODECID_AVC444 || cmd->codecId == RDPGFX_CODECID_AVC444v2;
    auto decoder = avc && !s->decoder->empty() ? s->env->NewStringUTF(s->decoder->c_str()) : nullptr;
    s->env->CallVoidMethod(s->owner,s->encoding,enc,decoder);
    s->env->DeleteLocalRef(enc); if (decoder) s->env->DeleteLocalRef(decoder);
    return exception(s) ? ERROR_INTERNAL_ERROR : result;
}
static bool clipboardEvents(Session* s) {
    auto* clip = s->clipboard;
    auto text = static_cast<jstring>(s->env->CallObjectMethod(s->owner, s->takeClipboard));
    if (exception(s)) return false;
    if (text) {
        const jchar* chars = s->env->GetStringChars(text, nullptr);
        if (!chars) { exception(s); s->env->DeleteLocalRef(text); return false; }
        clip->local.assign(reinterpret_cast<const char16_t*>(chars), s->env->GetStringLength(text));
        s->env->ReleaseStringChars(text, chars);
        s->env->DeleteLocalRef(text);
        clip->hasLocal = true; clip->dirty = true; clip->deadline = GetTickCount64() + 10000;
    }
    if ((clip->dirty || clip->awaitingAck) && clip->deadline && GetTickCount64() >= clip->deadline) return false;
    if (!clip->context) return true;
    std::deque<Clipboard::Event> events;
    { std::lock_guard<std::mutex> lock(clip->mutex); events.swap(clip->events); clip->queuedBytes = 0; }
    for (auto& event : events) {
        UINT result = CHANNEL_RC_OK;
        switch (event.type) {
            case 1: {
                CLIPRDR_GENERAL_CAPABILITY_SET general{};
                general.capabilitySetType = CB_CAPSTYPE_GENERAL;
                general.capabilitySetLength = CB_CAPSTYPE_GENERAL_LEN;
                general.version = CB_CAPS_VERSION_2;
                general.generalFlags = CB_USE_LONG_FORMAT_NAMES;
                CLIPRDR_CAPABILITIES capabilities{};
                capabilities.common.msgType = CB_CLIP_CAPS;
                capabilities.cCapabilitiesSets = 1;
                capabilities.capabilitySets = reinterpret_cast<CLIPRDR_CAPABILITY_SET*>(&general);
                result = clip->context->ClientCapabilities(clip->context, &capabilities);
                clip->ready = true; clip->dirty = true;
                break;
            }
            case 2: {
                CLIPRDR_FORMAT_LIST_RESPONSE response{};
                response.common.msgType = CB_FORMAT_LIST_RESPONSE;
                response.common.msgFlags = CB_RESPONSE_OK;
                result = clip->context->ClientFormatListResponse(clip->context, &response);
                clip->refresh = event.value != 0;
                if (clip->refresh && !clip->requested && result == CHANNEL_RC_OK) result = clip->request();
                break;
            }
            case 3: result = clip->respond(event.value); break;
            case 5:
                clip->awaitingAck = false;
                if (!event.value) return false;
                break;
            case 4: {
                if (!clip->requested) break;
                clip->requested = false;
                if (event.value) {
                    std::u16string content;
                    for (size_t i = 0; i + 1 < event.data.size(); i += 2) {
                        char16_t c = event.data[i] | (event.data[i+1] << 8);
                        if (!c) break;
                        content.push_back(c);
                    }
                    auto value = s->env->NewString(reinterpret_cast<const jchar*>(content.data()), static_cast<jsize>(content.size()));
                    if (!value) { exception(s); return false; }
                    s->env->CallVoidMethod(s->owner, s->clipboardReceived, value);
                    s->env->DeleteLocalRef(value);
                    if (exception(s)) return false;
                }
                if (clip->refresh) result = clip->request();
                break;
            }
        }
        if (result != CHANNEL_RC_OK) return false;
    }
    return !clip->ready || !clip->dirty || clip->awaitingAck || clip->advertise() == CHANNEL_RC_OK;
}
static void connected(void* context,const ChannelConnectedEventArgs* e) {
    auto* s = session(static_cast<rdpContext*>(context));
    __android_log_print(ANDROID_LOG_INFO,"MiffanRdp","Channel connected: %s",e->name);
    if (strcmp(e->name, "cliprdr") == 0) s->clipboard->attach(static_cast<CliprdrClientContext*>(e->pInterface));
    if (strcmp(e->name,RDPGFX_DVC_CHANNEL_NAME)==0) {
        auto* gfx = static_cast<RdpgfxClientContext*>(e->pInterface);
        if (gdi_graphics_pipeline_init(s->base.gdi,gfx)) {
            s->surfaceCommand=gfx->SurfaceCommand; gfx->SurfaceCommand=surfaceCommand;
        }
    }
}
static void disconnected(void* context,const ChannelDisconnectedEventArgs* e) {
    auto* c = static_cast<rdpContext*>(context);
    if (strcmp(e->name, "cliprdr") == 0) {
        static_cast<CliprdrClientContext*>(e->pInterface)->custom = nullptr;
        session(c)->clipboard->context = nullptr;
    }
    if (strcmp(e->name,RDPGFX_DVC_CHANNEL_NAME)==0) gdi_graphics_pipeline_uninit(c->gdi,static_cast<RdpgfxClientContext*>(e->pInterface));
}
static BOOL preConnect(freerdp* instance) {
    auto* c=instance->context;
    return PubSub_SubscribeChannelConnected(c->pubSub,connected)==CHANNEL_RC_OK &&
        PubSub_SubscribeChannelDisconnected(c->pubSub,disconnected)==CHANNEL_RC_OK;
}
static BOOL loadChannels(freerdp* instance) {
    auto* c=instance->context;
    // Load only our four channels. The generic CLI loader enables rdpdr as a side effect
    // of network autodetection, although MCS message-channel PDUs need no device channel.
    const char* gfx[]={"rdpgfx"};
    const char* disp[]={"disp"};
    if (!freerdp_client_add_dynamic_channel(c->settings,1,gfx) ||
        !freerdp_client_add_dynamic_channel(c->settings,1,disp) ||
        !freerdp_settings_set_bool(c->settings,FreeRDP_SupportDynamicChannels,TRUE)) return FALSE;
    for (const char* name:{"cliprdr","drdynvc"}) {
        auto entry=freerdp_channels_load_static_addin_entry(name,nullptr,nullptr,FREERDP_ADDIN_CHANNEL_STATIC | FREERDP_ADDIN_CHANNEL_ENTRYEX);
        auto ex=WINPR_FUNC_PTR_CAST(entry, PVIRTUALCHANNELENTRYEX);
        if (!ex || freerdp_channels_client_load_ex(c->channels,c->settings,ex,strcmp(name,"drdynvc")==0 ? c->settings : nullptr)!=0) return FALSE;
    }
    return TRUE;
}
static BOOL postConnect(freerdp* instance) {
    auto* c = instance->context;
    if (!gdi_init(instance,PIXEL_FORMAT_BGRA32)) return FALSE;
    rdpPointer p = {};
    p.size=sizeof(Pointer); p.New=pointerNew; p.Free=pointerFree; p.Set=pointerSet;
    p.SetNull=pointerNull; p.SetDefault=pointerDefault; p.SetPosition=pointerPosition;
    graphics_register_pointer(c->graphics,&p);
    c->update->BeginPaint=beginPaint; c->update->EndPaint=endPaint; c->update->DesktopResize=resize;
    announce(session(c)); return !session(c)->callbackFailed;
}
static bool inputs(Session* s) {
    auto* in = s->base.input;
    for (int i=0;i<128;i++) {
        auto cmd = static_cast<jintArray>(s->env->CallObjectMethod(s->owner,s->command));
        if (exception(s)) return false;
        if (!cmd) break;
        jint data[4] = {};
        s->env->GetIntArrayRegion(cmd,0,s->env->GetArrayLength(cmd),data);
        s->env->DeleteLocalRef(cmd);
        bool ok = true;
        if (data[0]==1) {
            ok=freerdp_input_send_mouse_event(in,PTR_FLAGS_MOVE,data[1],data[2]);
            const UINT16 flags[]={PTR_FLAGS_BUTTON1,PTR_FLAGS_BUTTON3,PTR_FLAGS_BUTTON2};
            for (int b=0;b<3;b++) if ((s->buttons ^ data[3]) & (1<<b))
                ok=freerdp_input_send_mouse_event(in,flags[b] | ((data[3] & (1<<b)) ? PTR_FLAGS_DOWN : 0),data[1],data[2]) && ok;
            for (int b=3;b<7;b++) if (data[3] & (1<<b)) {
                UINT16 flags=(b<5 ? PTR_FLAGS_WHEEL : PTR_FLAGS_HWHEEL) | ((b==4 || b==5) ? PTR_FLAGS_WHEEL_NEGATIVE | 0x88 : 0x78);
                ok=freerdp_input_send_mouse_event(in,flags,data[1],data[2]) && ok;
            }
            s->buttons=data[3] & 7;
        } else if (data[0]==2) {
            ok=freerdp_input_send_keyboard_event(in,(data[2] ? 0 : KBD_FLAGS_RELEASE) | ((data[1]&0x100) ? KBD_FLAGS_EXTENDED : 0),data[1]&0xff);
        } else if (data[0]==3) {
            if (data[1] == '\n' || data[1] == '\r' || data[1] == '\t') {
                const UINT8 code = data[1] == '\t' ? 0x0f : 0x1c;
                ok = freerdp_input_send_keyboard_event(in, 0, code) && freerdp_input_send_keyboard_event(in, KBD_FLAGS_RELEASE, code);
            } else ok=freerdp_input_send_unicode_keyboard_event(in,0,data[1]) && freerdp_input_send_unicode_keyboard_event(in,KBD_FLAGS_RELEASE,data[1]);
        }
        if (!ok) return false;
    }
    return true;
}
extern "C" JNIEXPORT void JNICALL Java_me_rerere_rdp_RdpSession_nativeRun(JNIEnv* env,jobject owner,jstring username,jstring password,jstring domain,jint width,jint height,jint security,jstring directory) {
    const char* dir = env->GetStringUTFChars(directory,nullptr);
    if (!dir) return;
    runtimeDirectory=dir;
    env->ReleaseStringUTFChars(directory,dir);
    JavaVM* vm = nullptr;
    env->GetJavaVM(&vm);
    static std::once_flag init;
    std::call_once(init, [vm] {
        jniVm = vm; // WinPR JNI_OnLoad is hidden with the static archive symbols.
        freerdp_register_addin_provider(freerdp_channels_load_static_addin_entry, 0);
    });
    __android_log_print(ANDROID_LOG_INFO,"MiffanRdp","Creating instance");
    freerdp* instance=freerdp_new();
    if (!instance) { env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),"freerdp_new failed"); return; }
    __android_log_print(ANDROID_LOG_INFO,"MiffanRdp","Creating context");
    instance->ContextSize=sizeof(Session);
    instance->PreConnect=preConnect; instance->LoadChannels=loadChannels; instance->PostConnect=postConnect; instance->VerifyX509Certificate=verify;
    if (!freerdp_context_new(instance)) {
        __android_log_print(ANDROID_LOG_ERROR,"MiffanRdp","Context creation failed");
        freerdp_free(instance);
        if(!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),"freerdp_context_new failed");
        return;
    }
    __android_log_print(ANDROID_LOG_INFO,"MiffanRdp","Context ready");
    auto* s=session(instance->context);
    s->env=env; s->owner=owner; s->decoder=new std::string(); s->clipboard=new Clipboard();
    // The stream has no fd. A signaled WinPR event makes pending BIO reads pollable; the loop
    // sleeps at most 10ms. TLS handshake waits use the stream condition variable instead.
    s->event=CreateEvent(nullptr,TRUE,TRUE,nullptr);
    auto cls=env->GetObjectClass(owner);
#define METHOD(field,name,sig) s->field=env->GetMethodID(cls,name,sig)
    METHOD(read,"readTransport","([B)I"); METHOD(write,"writeTransport","([B)Z"); METHOD(wait,"waitTransport","(I)Z");
    METHOD(closed,"isClosed","()Z"); METHOD(paused,"isPaused","()Z"); METHOD(command,"nextCommand","()[I");
    METHOD(certificate,"verifyCertificate","(Ljava/lang/String;)Z"); METHOD(size,"onSize","(IILjava/lang/String;)V");
    METHOD(pixels,"onPixels","(IIII[I)V"); METHOD(frame,"onFrame","()V"); METHOD(cursor,"onCursor","(IIII[I)V");
    METHOD(encoding,"onEncoding","(Ljava/lang/String;Ljava/lang/String;)V"); METHOD(failure,"onFailure","(J)V"); METHOD(stats,"publishStats","()V");
    METHOD(clipboardFailure,"onClipboardFailure","()V");
    METHOD(takeClipboard,"takeClipboard","()Ljava/lang/String;"); METHOD(clipboardReceived,"onClipboard","(Ljava/lang/String;)V");
#undef METHOD
    env->DeleteLocalRef(cls);
    auto* settings=instance->context->settings;
    auto setString=[&](FreeRDP_Settings_Keys_String id,jstring text) {
        const char* str=env->GetStringUTFChars(text,nullptr);
        bool ok=str && freerdp_settings_set_string(settings,id,str);
        if(str) env->ReleaseStringUTFChars(text,str);
        return ok;
    };
    bool configured=setString(FreeRDP_Username,username) && setString(FreeRDP_Password,password) && setString(FreeRDP_Domain,domain);
    configured=freerdp_settings_set_string(settings,FreeRDP_ServerHostname,"rdp-stream") && configured;
    configured=freerdp_settings_set_uint32(settings,FreeRDP_DesktopWidth,width) && configured;
    configured=freerdp_settings_set_uint32(settings,FreeRDP_DesktopHeight,height) && configured;
    configured=freerdp_settings_set_uint32(settings,FreeRDP_ColorDepth,32) && configured;
    const FreeRDP_Settings_Keys_Bool enabled[]={FreeRDP_TlsSecurity,FreeRDP_NlaSecurity,FreeRDP_Authentication,FreeRDP_ExternalCertificateManagement,
        FreeRDP_SupportGraphicsPipeline,FreeRDP_GfxH264,FreeRDP_GfxAVC444,FreeRDP_GfxAVC444v2,FreeRDP_SoftwareGdi,FreeRDP_SynchronousDynamicChannels,
        FreeRDP_NetworkAutoDetect,FreeRDP_SupportHeartbeatPdu,FreeRDP_SupportDisplayControl,FreeRDP_RedirectClipboard};
    for (auto id:enabled) configured=freerdp_settings_set_bool(settings,id,TRUE) && configured;
    const FreeRDP_Settings_Keys_Bool disabled[]={FreeRDP_AsyncUpdate,FreeRDP_AsyncChannels,FreeRDP_RdpSecurity,FreeRDP_AutoReconnectionEnabled,FreeRDP_RedirectDrives,FreeRDP_RedirectPrinters,FreeRDP_RedirectSmartCards,FreeRDP_AudioPlayback,FreeRDP_DeviceRedirection,FreeRDP_SupportMultitransport,FreeRDP_GfxCodecAV1};
    for (auto id:disabled) configured=freerdp_settings_set_bool(settings,id,FALSE) && configured;
    configured=freerdp_settings_set_bool(settings,FreeRDP_NlaSecurity,security != 1) && configured;
    configured=freerdp_settings_set_bool(settings,FreeRDP_TlsSecurity,security != 2) && configured;
    configured=freerdp_settings_set_bool(settings,FreeRDP_ExtSecurity,security != 1) && configured;
    rdpTransportIo io=*freerdp_get_io_callbacks(instance->context);
    io.ConnectLayer=connectLayer;
    configured=freerdp_set_io_callbacks(instance->context,&io) && configured;
    active=s;
    __android_log_print(ANDROID_LOG_INFO,"MiffanRdp","Connecting (configured=%d)",configured);
    bool success=configured && !exception(s) && freerdp_connect(instance);
    __android_log_print(ANDROID_LOG_INFO,"MiffanRdp","Connect returned %d",success);
    if (success) {
        bool wasPaused=false;
        while (!stopped(s) && !freerdp_shall_disconnect_context(instance->context)) {
            bool paused=env->CallBooleanMethod(owner,s->paused);
            if(exception(s)) break;
            if(paused!=wasPaused) {
                RECTANGLE_16 area={0,0,static_cast<UINT16>(instance->context->gdi->width-1),static_cast<UINT16>(instance->context->gdi->height-1)};
                if (!instance->context->update->SuppressOutput(instance->context,paused ? 0 : 1,&area)) break;
                wasPaused=paused;
            }
            if (!clipboardEvents(s)) {
                if (!s->callbackFailed) env->CallVoidMethod(owner, s->clipboardFailure);
                break;
            }
            if ((!s->clipboard->dirty && !s->clipboard->awaitingAck && !inputs(s)) || !freerdp_check_event_handles(instance->context)) break;
            env->CallVoidMethod(owner,s->stats);
            if(exception(s)) break;
            Sleep(10);
        }
    }
    if (!s->callbackFailed && !stopped(s)) env->CallVoidMethod(owner,s->failure,static_cast<jlong>(freerdp_get_last_error(instance->context)));
    // Preserve a sink exception across native teardown, whose hooks must not call JNI again.
    jthrowable thrown=env->ExceptionOccurred(); if(thrown) env->ExceptionClear();
    __android_log_print(ANDROID_LOG_INFO,"MiffanRdp","Disconnecting");
    bool disconnectedOk=freerdp_disconnect(instance); (void)disconnectedOk;
    __android_log_print(ANDROID_LOG_INFO,"MiffanRdp","Freeing graphics");
    gdi_free(instance);
    CloseHandle(s->event); delete s->decoder; delete s->clipboard;
    active=nullptr; runtimeDirectory.clear();
    freerdp_context_free(instance); freerdp_free(instance);
    __android_log_print(ANDROID_LOG_INFO,"MiffanRdp","Native session released");
    if(thrown) { env->Throw(thrown); env->DeleteLocalRef(thrown); }
}
