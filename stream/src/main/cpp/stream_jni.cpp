// SPDX-License-Identifier: GPL-3.0-or-later
#include <jni.h>
extern "C" {
#include "Limelight.h"
}
#include <openssl/ssl.h>
#include <openssl/pem.h>
#include <openssl/rsa.h>
#include <openssl/crypto.h>
#include <unistd.h>
#include <fcntl.h>
#include <poll.h>
#include <sys/socket.h>
#include <arpa/inet.h>
#include <cerrno>
#include <chrono>
#include <string>
#include <vector>
#include <mutex>
#include <atomic>
#include <cstring>

static JavaVM* vm;
static jobject owner;
static jmethodID openMethod, setupMethod, frameMethod, eventMethod, stoppedMethod, audioSetupMethod, audioDataMethod, receivedMethod;
static std::mutex inputMutex;
static bool acceptingInput;
static std::atomic<long long> audioPackets{0}, receivedBytes{0};
static void receivedMedia(int bytes) { receivedBytes += bytes; }
static void publishReceived(JNIEnv* e) {
    auto count = receivedBytes.exchange(0);
    if (count > 0) e->CallVoidMethod(owner, receivedMethod, (jlong)count);
    if (e->ExceptionCheck()) e->ExceptionClear();
}
static std::atomic<long long> fecFailureEvents{0}, idrRequestsSent{0};
static JNIEnv* env(bool& attached) {
    JNIEnv* e = nullptr; attached = vm->GetEnv((void**)&e, JNI_VERSION_1_6) != JNI_OK;
    if (attached) vm->AttachCurrentThread(&e, nullptr);
    return e;
}
static void detach(bool attached) { if (attached) vm->DetachCurrentThread(); }
static void event(int kind, int stage, int code) {
    bool attached; auto e = env(attached);
    e->CallVoidMethod(owner, eventMethod, kind, stage, code);
    if (e->ExceptionCheck()) e->ExceptionClear();
    detach(attached);
}
static int openTcp(unsigned short port) {
    bool attached; auto e = env(attached);
    int fd = e->CallIntMethod(owner, openMethod, (int)port);
    if (e->ExceptionCheck()) { e->ExceptionClear(); fd = -1; }
    if (fd < 0) errno = ECONNREFUSED;
    detach(attached); return fd;
}
static int setup(int format, int w, int h, int fps, void*, int) {
    bool attached; auto e = env(attached);
    bool ok = e->CallBooleanMethod(owner, setupMethod, format, w, h, fps);
    if (e->ExceptionCheck()) { e->ExceptionClear(); ok = false; }
    detach(attached); return ok ? 0 : -200;
}
static int submit(PDECODE_UNIT unit) {
    if (unit->fullLength <= 0 || unit->fullLength > 16 * 1024 * 1024) return DR_NEED_IDR;
    bool attached; auto e = env(attached);
    auto bytes = e->NewByteArray(unit->fullLength); int offset = 0;
    if (!bytes) { if (e->ExceptionCheck()) e->ExceptionClear(); detach(attached); return DR_NEED_IDR; }
    for (auto p = unit->bufferList; p; p = p->next) {
        if (p->length < 0 || p->length > unit->fullLength - offset) { e->DeleteLocalRef(bytes); detach(attached); return DR_NEED_IDR; }
        e->SetByteArrayRegion(bytes, offset, p->length, (jbyte*)p->data); offset += p->length;
    }
    bool ok = e->CallBooleanMethod(owner, frameMethod, bytes, (jlong)unit->presentationTimeUs,
        unit->frameNumber, unit->frameType == FRAME_TYPE_IDR);
    if (e->ExceptionCheck()) { e->ExceptionClear(); ok = false; }
    e->DeleteLocalRef(bytes); detach(attached); return ok ? DR_OK : DR_NEED_IDR;
}
static void stageStarting(int stage) { event(0, stage, 0); }
static void stageFailed(int stage, int code) { event(1, stage, code); }
static void started() {
    { std::lock_guard<std::mutex> lock(inputMutex); acceptingInput = true; }
    event(2, 0, 0);
}
static void terminated(int code) { event(3, 0, code); }
static void silentLog(const char* format, ...) {
    // Count fixed diagnostic events; never format/log native arguments or URLs.
    if (strncmp(format, "Unrecoverable frame ", 20) == 0) ++fecFailureEvents;
    if (strcmp(format, "IDR frame request sent\n") == 0) ++idrRequestsSent;
}
static int audioInit(int, const POPUS_MULTISTREAM_CONFIGURATION config, void*, int) {
    bool attached; auto e = env(attached);
    auto mapping = e->NewByteArray(config->channelCount);
    if (mapping) {
        e->SetByteArrayRegion(mapping, 0, config->channelCount, (jbyte*)config->mapping);
        e->CallVoidMethod(owner, audioSetupMethod, config->sampleRate, config->channelCount,
            config->streams, config->coupledStreams, config->samplesPerFrame, mapping);
        e->DeleteLocalRef(mapping);
    }
    if (e->ExceptionCheck()) e->ExceptionClear();
    detach(attached); return 0; // Audio failure never tears down video; disabled audio still decrypts.
}
static void audioData(char* data, int length) {
    ++audioPackets;
    if (length <= 0 || length > 65536) return;
    bool attached; auto e = env(attached);
    auto bytes = e->NewByteArray(length);
    if (bytes) {
        e->SetByteArrayRegion(bytes, 0, length, (jbyte*)data);
        e->CallVoidMethod(owner, audioDataMethod, bytes);
        e->DeleteLocalRef(bytes);
    }
    if (e->ExceptionCheck()) e->ExceptionClear();
    detach(attached);
}
static std::string str(JNIEnv* e, jstring s) { const char* p = e->GetStringUTFChars(s, nullptr); std::string r(p); e->ReleaseStringUTFChars(s, p); return r; }
static void fail(JNIEnv* e, const char* message) { auto c = e->FindClass("java/io/IOException"); e->ThrowNew(c, message); e->DeleteLocalRef(c); }
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* v, void*) { vm = v; return JNI_VERSION_1_6; }
#define JNI(name) extern "C" JNIEXPORT
JNI(identity) jobjectArray JNICALL Java_me_rerere_stream_StreamNative_identity(JNIEnv* e, jobject) {
    EVP_PKEY_CTX* ctx = EVP_PKEY_CTX_new_id(EVP_PKEY_RSA, nullptr); EVP_PKEY* key = nullptr;
    X509* cert = X509_new(); BIO* c = BIO_new(BIO_s_mem()); BIO* k = BIO_new(BIO_s_mem());
    bool ok = ctx && cert && c && k && EVP_PKEY_keygen_init(ctx) == 1 &&
        EVP_PKEY_CTX_set_rsa_keygen_bits(ctx, 2048) == 1 && EVP_PKEY_keygen(ctx, &key) == 1;
    if (ok) {
        X509_set_version(cert, 2); ASN1_INTEGER_set(X509_get_serialNumber(cert), 1);
        X509_gmtime_adj(X509_getm_notBefore(cert), -86400); X509_gmtime_adj(X509_getm_notAfter(cert), 86400L * 3650);
        X509_set_pubkey(cert, key); auto name = X509_get_subject_name(cert);
        X509_NAME_add_entry_by_txt(name, "CN", MBSTRING_ASC, (const unsigned char*)"Miffan Stream Client", -1, -1, 0);
        X509_set_issuer_name(cert, name);
        ok = X509_sign(cert, key, EVP_sha256()) > 0 && PEM_write_bio_X509(c, cert) == 1 &&
             PEM_write_bio_PrivateKey(k, key, nullptr, nullptr, 0, nullptr, nullptr) == 1;
    }
    jobjectArray result = nullptr;
    if (ok) {
        auto stringClass = e->FindClass("java/lang/String"); result = e->NewObjectArray(2, stringClass, nullptr); e->DeleteLocalRef(stringClass);
        char* p; long n = BIO_get_mem_data(c, &p); auto cs = e->NewStringUTF(std::string(p, n).c_str());
        e->SetObjectArrayElement(result, 0, cs); e->DeleteLocalRef(cs);
        n = BIO_get_mem_data(k, &p); auto ks = e->NewStringUTF(std::string(p, n).c_str());
        e->SetObjectArrayElement(result, 1, ks); e->DeleteLocalRef(ks);
    } else fail(e, "Identity generation failed");
    BIO_free(c); BIO_free(k); X509_free(cert); EVP_PKEY_free(key); EVP_PKEY_CTX_free(ctx); return result;
}
JNI(http) jbyteArray JNICALL Java_me_rerere_stream_StreamNative_http(JNIEnv* e, jobject, jint fd,
    jstring certificate, jstring privateKey, jstring pinString, jbyteArray request, jint timeout) {
    auto pin = str(e, pinString); SSL_CTX* ctx = nullptr; SSL* ssl = nullptr;
    std::vector<unsigned char> result; const char* error = nullptr;
    auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(timeout);
    fcntl(fd, F_SETFL, fcntl(fd, F_GETFL) | O_NONBLOCK);
    auto wait = [&](short events) {
        while (std::chrono::steady_clock::now() < deadline) {
            pollfd p{fd, events, 0}; int r = poll(&p, 1, 50);
            if (r > 0) return !(p.revents & POLLNVAL);
            if (r < 0 && errno != EINTR) return false;
        } return false;
    };
    auto tlsWait = [&](int r) {
        int code = SSL_get_error(ssl, r);
        return code == SSL_ERROR_WANT_READ ? wait(POLLIN) : code == SSL_ERROR_WANT_WRITE && wait(POLLOUT);
    };
    if (!pin.empty()) {
        ctx = SSL_CTX_new(TLS_client_method());
        if (!ctx) error = "TLS initialization failed";
        if (!error) {
            SSL_CTX_set_min_proto_version(ctx, TLS1_2_VERSION); SSL_CTX_set_verify(ctx, SSL_VERIFY_NONE, nullptr);
            SSL_CTX_set_options(ctx, SSL_OP_IGNORE_UNEXPECTED_EOF);
            auto cp = str(e, certificate), kp = str(e, privateKey);
            BIO* cb = BIO_new_mem_buf(cp.data(), cp.size()); BIO* kb = BIO_new_mem_buf(kp.data(), kp.size());
            X509* cert = PEM_read_bio_X509(cb, nullptr, nullptr, nullptr); EVP_PKEY* key = PEM_read_bio_PrivateKey(kb, nullptr, nullptr, nullptr);
            bool ok = cert && key && SSL_CTX_use_certificate(ctx, cert) == 1 && SSL_CTX_use_PrivateKey(ctx, key) == 1 && SSL_CTX_check_private_key(ctx) == 1;
            OPENSSL_cleanse(kp.data(), kp.size()); X509_free(cert); EVP_PKEY_free(key); BIO_free(cb); BIO_free(kb);
            if (!ok) error = "TLS identity failed";
        }
        if (!error) {
            ssl = SSL_new(ctx); if (!ssl || SSL_set_fd(ssl, fd) != 1) error = "TLS socket failed";
        }
        if (!error) { int r; while ((r = SSL_connect(ssl)) != 1) { if (!tlsWait(r)) { error = "TLS handshake failed"; break; } } }
        if (!error) {
            X509* peer = SSL_get1_peer_certificate(ssl); unsigned char digest[32]; unsigned int n = 0;
            bool ok = peer && X509_digest(peer, EVP_sha256(), digest, &n) == 1 && n == 32 && pin.size() == 64;
            unsigned char expected[32]{};
            for (int i = 0; ok && i < 32; ++i) { unsigned int b; ok = sscanf(pin.c_str() + i * 2, "%2x", &b) == 1; expected[i] = b; }
            X509_free(peer);
            if (!ok || CRYPTO_memcmp(expected, digest, 32)) error = "CERTIFICATE_MISMATCH";
        }
    }
    std::vector<unsigned char> req(e->GetArrayLength(request)); e->GetByteArrayRegion(request, 0, req.size(), (jbyte*)req.data());
    size_t sent = 0;
    while (!error && sent < req.size()) {
        int r = ssl ? SSL_write(ssl, req.data() + sent, req.size() - sent) : send(fd, req.data() + sent, req.size() - sent, MSG_NOSIGNAL);
        if (r > 0) sent += r;
        else if (!(ssl ? tlsWait(r) : (errno == EAGAIN || errno == EINTR) && wait(POLLOUT))) error = "HTTP write failed";
    }
    OPENSSL_cleanse(req.data(), req.size());
    unsigned char buf[8192];
    while (!error) {
        int r = ssl ? SSL_read(ssl, buf, sizeof(buf)) : recv(fd, buf, sizeof(buf), 0);
        if (r > 0) {
            if (result.size() + r > 4 * 1024 * 1024) { error = "HTTP response too large"; break; }
            result.insert(result.end(), buf, buf + r);
        } else if (r == 0 && (!ssl || SSL_get_error(ssl, r) == SSL_ERROR_ZERO_RETURN)) break;
        else if (!(ssl ? tlsWait(r) : (errno == EAGAIN || errno == EINTR) && wait(POLLIN))) { error = "HTTP read failed"; break; }
    }
    SSL_free(ssl); SSL_CTX_free(ctx); close(fd);
    if (error) { fail(e, error); return nullptr; }
    auto bytes = e->NewByteArray(result.size()); e->SetByteArrayRegion(bytes, 0, result.size(), (jbyte*)result.data()); return bytes;
}
JNI(run) jint JNICALL Java_me_rerere_stream_StreamNative_run(JNIEnv* e, jobject, jobject session,
    jstring address, jstring version, jstring gfe, jint codecSupport, jstring url,
    jint w, jint h, jint fps, jint bitrate, jint formats, jbyteArray key, jbyteArray iv) {
    auto a = str(e, address), v = str(e, version), g = str(e, gfe), u = str(e, url);
    unsigned char numeric[16]; if (inet_pton(AF_INET, a.c_str(), numeric) != 1 && inet_pton(AF_INET6, a.c_str(), numeric) != 1) return -111;
    owner = e->NewGlobalRef(session); auto cls = e->GetObjectClass(session);
    openMethod = e->GetMethodID(cls, "openTcp", "(I)I"); setupMethod = e->GetMethodID(cls, "setupDecoder", "(IIII)Z");
    frameMethod = e->GetMethodID(cls, "submitFrame", "([BJIZ)Z"); eventMethod = e->GetMethodID(cls, "nativeEvent", "(III)V");
    stoppedMethod = e->GetMethodID(cls, "waitForStop", "()V");
    audioSetupMethod = e->GetMethodID(cls, "setupAudio", "(IIIII[B)V");
    audioDataMethod = e->GetMethodID(cls, "submitAudio", "([B)V");
    receivedMethod = e->GetMethodID(cls, "receivedUdpBytes", "(J)V"); e->DeleteLocalRef(cls);
    receivedBytes = 0; audioPackets = 0; fecFailureEvents = 0; idrRequestsSent = 0;
    SERVER_INFORMATION server; LiInitializeServerInformation(&server);
    server.address = a.c_str(); server.serverInfoAppVersion = v.c_str(); server.serverInfoGfeVersion = g.c_str();
    server.serverCodecModeSupport = codecSupport; server.rtspSessionUrl = u.c_str(); server.requireEncryptedStreams = true; server.openRtspTcp = openTcp; server.receivedMediaBytes = receivedMedia;
    STREAM_CONFIGURATION config; LiInitializeStreamConfiguration(&config);
    config.width = w; config.height = h; config.fps = fps; config.bitrate = bitrate; config.packetSize = 1024;
    config.streamingRemotely = STREAM_CFG_REMOTE; config.audioConfiguration = AUDIO_CONFIGURATION_STEREO;
    config.supportedVideoFormats = formats; config.encryptionFlags = ENCFLG_ALL;
    e->GetByteArrayRegion(key, 0, 16, (jbyte*)config.remoteInputAesKey); e->GetByteArrayRegion(iv, 0, 16, (jbyte*)config.remoteInputAesIv);
    CONNECTION_LISTENER_CALLBACKS listener; LiInitializeConnectionCallbacks(&listener);
    listener.stageStarting = stageStarting; listener.stageFailed = stageFailed; listener.connectionStarted = started;
    listener.connectionTerminated = terminated; listener.logMessage = silentLog;
    DECODER_RENDERER_CALLBACKS video; LiInitializeVideoCallbacks(&video); video.setup = setup; video.submitDecodeUnit = submit;
    video.capabilities = CAPABILITY_DIRECT_SUBMIT;
    AUDIO_RENDERER_CALLBACKS audio; LiInitializeAudioCallbacks(&audio); audio.init = audioInit; audio.decodeAndPlaySample = audioData;
    audio.capabilities = CAPABILITY_DIRECT_SUBMIT;
    int r = LiStartConnection(&server, &config, &listener, &video, &audio, nullptr, 0, nullptr, 0);
    if (r == 0) {
        e->CallVoidMethod(owner, stoppedMethod);
        { std::lock_guard<std::mutex> lock(inputMutex); acceptingInput = false; }
        LiStopConnection();
    }
    { std::lock_guard<std::mutex> lock(inputMutex); acceptingInput = false; }
    publishReceived(e);
    OPENSSL_cleanse(config.remoteInputAesKey, 16);
    e->DeleteGlobalRef(owner); owner = nullptr; return r;
}
JNI(interrupt) void JNICALL Java_me_rerere_stream_StreamNative_interrupt(JNIEnv*, jobject) { LiInterruptConnection(); }
JNI(idr) void JNICALL Java_me_rerere_stream_StreamNative_idr(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(inputMutex); if (acceptingInput) LiRequestIdrFrame();
}
JNI(input) jint JNICALL Java_me_rerere_stream_StreamNative_input(JNIEnv*, jobject, jint kind, jint a, jint b, jint c, jint d) {
    std::lock_guard<std::mutex> lock(inputMutex); if (!acceptingInput) return -1;
    switch (kind) {
        case 0: return LiSendMouseMoveEvent(a, b);
        case 1: return LiSendMousePositionEvent(a, b, c, d);
        case 2: return LiSendMouseButtonEvent(b ? BUTTON_ACTION_PRESS : BUTTON_ACTION_RELEASE, a);
        case 3: return LiSendHighResScrollEvent(a);
        case 4: return LiSendHighResHScrollEvent(a);
        case 5: return LiSendKeyboardEvent2(a | 0x8000, b ? KEY_ACTION_DOWN : KEY_ACTION_UP, c, 0);
        default: return -1;
    }
}
JNI(networkStats) jlongArray JNICALL Java_me_rerere_stream_StreamNative_networkStats(JNIEnv* e, jobject) {
    std::lock_guard<std::mutex> lock(inputMutex);
    publishReceived(e);
    P6A_NEGOTIATION n{}; uint32_t rtt = 0, variance = 0;
    if (acceptingInput) LiGetP6aNegotiation(&n);
    bool have = acceptingInput && LiGetEstimatedRttInfo(&rtt, &variance);
    jlong values[] = {n.videoEncrypted, n.audioEncrypted, n.controlV2, have ? (jlong)rtt : -1,
        audioPackets.load(), fecFailureEvents.load(), idrRequestsSent.load()};
    auto result = e->NewLongArray(7); e->SetLongArrayRegion(result, 0, 7, values); return result;
}
