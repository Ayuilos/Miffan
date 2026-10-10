// SPDX-License-Identifier: GPL-3.0-or-later
#include "transport.h"
#include "client.h"
#include "errors.h"
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>
#include <openssl/rand.h>

typedef struct {
    const char *host, *keydir, *udp, *app, *json, *codec, *rtsp;
    int seconds, width, height, fps, bitrate, http_port;
    bool strict, input;
} Options;
typedef struct { int stage, error; const char *kind; double ms; } Event;
static struct {
    pthread_mutex_t lock;
    Event events[128]; int event_count;
    uint64_t frames, idrs, bytes, audio;
    double first_frame, native_start, started, elapsed;
    bool started_ok, terminated, startup_done, accepting_samples;
    int termination_error, startup_error, format, width, height, fps;
    int input_forward, input_reverse; bool input_sent;
    P6A_NEGOTIATION negotiation; bool negotiation_available;
    struct { double ms, fps; uint64_t frames; } samples[3601];
    int sample_count;
    bool rtt_available; uint32_t rtt, rtt_variance;
} stats = {.lock=PTHREAD_MUTEX_INITIALIZER, .first_frame=-1, .accepting_samples=true};
static double epoch;
static SERVER_INFORMATION *start_server;
static STREAM_CONFIGURATION *start_config;
static CONNECTION_LISTENER_CALLBACKS listener;
static DECODER_RENDERER_CALLBACKS video;
static AUDIO_RENDERER_CALLBACKS audio;

static double clock_seconds(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (double)t.tv_sec + t.tv_nsec/1e9;
}
static void pause_ms(long ms) {
    struct timespec t = {ms/1000, (ms%1000)*1000000};
    nanosleep(&t, NULL);
}
static void signal_handler(int sig) { probe_interrupted = 1; }
static void event(const char *kind, int stage, int error) {
    pthread_mutex_lock(&stats.lock);
    double ms = (clock_seconds()-epoch)*1000;
    if (stats.event_count < 128) stats.events[stats.event_count++] = (Event){stage, error, kind, ms};
    printf("stage_event=%s stage=%d name=\"%s\" elapsed_ms=%.3f error=%d\n", kind, stage,
           stage >= 0 ? LiGetStageName(stage) : "HTTP", ms, error);
    pthread_mutex_unlock(&stats.lock);
}
static void stage_start(int stage) { event("starting", stage, 0); }
static void stage_complete(int stage) {
    if (stage == STAGE_RTSP_HANDSHAKE) {
        pthread_mutex_lock(&stats.lock);
        stats.negotiation_available = true;
        LiGetP6aNegotiation(&stats.negotiation);
        pthread_mutex_unlock(&stats.lock);
    }
    event("complete", stage, 0);
}
static void stage_failed(int stage, int error) { event("failed", stage, error); }
static void connected(void) {
    pthread_mutex_lock(&stats.lock);
    stats.started_ok = true; stats.started = clock_seconds();
    printf("connection_started_ms=%.3f\n", (stats.started-epoch)*1000);
    pthread_mutex_unlock(&stats.lock);
}
static void terminated(int error) {
    pthread_mutex_lock(&stats.lock);
    stats.terminated = true; stats.termination_error = error;
    printf("connection_terminated_ms=%.3f error=%d\n", (clock_seconds()-epoch)*1000, error);
    pthread_mutex_unlock(&stats.lock);
}
static void native_log(const char *format, ...) {
    // Do not echo native debug payloads/RTSP identifiers. Structured stages carry errors.
}
static int video_setup(int format, int width, int height, int fps, void *context, int flags) {
    pthread_mutex_lock(&stats.lock);
    stats.format = format; stats.width = width; stats.height = height; stats.fps = fps;
    printf("negotiated_video_format=0x%x width=%d height=%d fps=%d\n", format, width, height, fps);
    pthread_mutex_unlock(&stats.lock);
    return 0;
}
static int frame(PDECODE_UNIT unit) {
    pthread_mutex_lock(&stats.lock);
    if (!stats.accepting_samples) {
        pthread_mutex_unlock(&stats.lock);
        return DR_OK;
    }
    if (!stats.frames) {
        stats.first_frame = (clock_seconds()-stats.native_start)*1000;
        printf("first_complete_frame_ms=%.3f\n", stats.first_frame);
    }
    ++stats.frames;
    if (unit->frameType == FRAME_TYPE_IDR) ++stats.idrs;
    stats.bytes += (uint64_t)unit->fullLength;
    pthread_mutex_unlock(&stats.lock);
    return DR_OK;
}
static int audio_init(int config, const POPUS_MULTISTREAM_CONFIGURATION opus, void *context, int flags) { return 0; }
static void audio_packet(char *data, int size) {
    pthread_mutex_lock(&stats.lock);
    if (stats.accepting_samples) ++stats.audio;
    pthread_mutex_unlock(&stats.lock);
}
static void *start_thread(void *unused) {
    int result = LiStartConnection(start_server, start_config, &listener, &video, &audio, NULL, 0, NULL, 0);
    pthread_mutex_lock(&stats.lock);
    stats.startup_error = result;
    LiGetP6aNegotiation(&stats.negotiation);
    stats.startup_done = true;
    pthread_mutex_unlock(&stats.lock);
    return NULL;
}
static void usage(const char *command) {
    puts("p6a-probe: Mac headless Sunshine transport/encryption probe (no decoding)");
    if (!command || !strcmp(command,"pair"))
        puts("  pair --http-host 127.0.0.1 --keydir DIR [--name miffan-p6a]");
    if (!command || !strcmp(command,"stream"))
        puts("  stream --http-host 127.0.0.1 --keydir DIR --udp-host NUMERIC_IP\n"
             "         --rtsp-tcp 127.0.0.1:PORT [--app Desktop] [--seconds 30]\n"
             "         [--width 1920 --height 1080 --fps 60] [--bitrate 15000]\n"
             "         [--codec hevc|h264] [--strict | --no-strict] [--input-test]\n"
             "         [--json NEW_FILE]");
    puts("  Optional local forwarding ports: --http-port 47989 --https-port 47984\n"
         "  --help, pair --help, stream --help\n"
         "HTTP/HTTPS and RTSP relay accept only 127.0.0.1; no proxy, redirects,\n"
         "cancel or unpair. stream requires a previously paired private keydir.");
}
static int number(const char *s, int min, int max) {
    char *end; errno=0;
    long n = strtol(s, &end, 10);
    if (errno || !*s || *end || n < min || n > max) return -1;
    return (int)n;
}
static bool numeric_ip(const char *s) {
    struct in6_addr a;
    return inet_pton(AF_INET,s,&a)==1 || inet_pton(AF_INET6,s,&a)==1;
}
static void json_string(FILE *f, const char *s) {
    fputc('"',f);
    for (const unsigned char *p=(const unsigned char*)(s ? s : ""); *p; ++p) {
        if (*p=='"' || *p=='\\') { fputc('\\',f); fputc(*p,f); }
        else if (*p<32) fprintf(f,"\\u%04x",*p);
        else fputc(*p,f);
    }
    fputc('"',f);
}
static const char *bool_text(bool b) { return b ? "true" : "false"; }
static int report(const Options *o, int result, const char *error) {
    double duration = stats.elapsed;
    double fps = duration > 0 ? stats.frames / duration : 0;
    P6A_NEGOTIATION *n = &stats.negotiation;
    printf("result=%d elapsed_ms=%.3f frames=%llu idr=%llu bytes=%llu average_fps=%.3f audio_packets=%llu\n",
           result, (clock_seconds()-epoch)*1000, (unsigned long long)stats.frames,
           (unsigned long long)stats.idrs, (unsigned long long)stats.bytes, fps, (unsigned long long)stats.audio);
    printf("negotiation_available=%s encryption_supported=0x%x requested=0x%x enabled=0x%x "
           "video_encrypted=%s audio_encrypted=%s control_encrypted=%s control_v2=%s\n",
           bool_text(stats.negotiation_available), n->supported,n->requested,n->enabled,
           bool_text(n->videoEncrypted),bool_text(n->audioEncrypted),bool_text(n->controlEncrypted),bool_text(n->controlV2));
    printf("rtt_available=%s rtt_ms=%u rtt_variance_ms=%u\n", bool_text(stats.rtt_available),stats.rtt,stats.rtt_variance);
    if (error) fprintf(stderr,"Error: %s\n",error);
    if (!o->json) return result;
    int fd = open(o->json,O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW,0600);
    if (fd<0) { fprintf(stderr,"Cannot create JSON output (must be a new file): %s\n",strerror(errno)); return 2; }
    FILE *f=fdopen(fd,"w");
    if (!f) { close(fd); return 2; }
    fprintf(f,"{\n  \"schema_version\":1,\"result\":%d,\"error\":",result);
    if (error) json_string(f,error); else fputs("null",f);
    fputs(",\"udp_host\":",f); json_string(f,o->udp);
    fprintf(f,",\"strict\":%s,\"interrupted\":%s,\n  \"stages\":[",bool_text(o->strict),bool_text(probe_interrupted));
    for (int i=0;i<stats.event_count;++i) {
        Event *e=&stats.events[i];
        fprintf(f,"%s{\"kind\":\"%s\",\"stage\":%d,\"ms\":%.3f,\"error\":%d}",i?",":"",e->kind,e->stage,e->ms,e->error);
    }
    fprintf(f,"],\n  \"startup_error\":%d,\"connection_terminated\":%s,\"termination_error\":",
            stats.startup_error,bool_text(stats.terminated));
    if (stats.terminated) fprintf(f,"%d",stats.termination_error); else fputs("null",f);
    fprintf(f,",\"connection_started_ms\":%.3f,\n  \"negotiation\":{\"available\":%s,"
            "\"supported\":%u,\"requested\":%u,\"enabled\":%u,\"video_encrypted\":%s,"
            "\"audio_encrypted\":%s,\"control_encrypted\":%s,\"control_v2\":%s,\"audio_cipher\":\"AES-CBC\"},\n",
            stats.started_ok?(stats.started-epoch)*1000:-1,bool_text(stats.negotiation_available),
            n->supported,n->requested,n->enabled,bool_text(n->videoEncrypted),bool_text(n->audioEncrypted),
            bool_text(n->controlEncrypted),bool_text(n->controlV2));
    fprintf(f,"  \"video\":{\"format\":%d,\"width\":%d,\"height\":%d,\"requested_fps\":%d,"
            "\"first_complete_frame_ms\":%.3f,\"frames\":%llu,\"idr\":%llu,\"bytes\":%llu,"
            "\"observed_seconds\":%.3f,\"average_fps\":%.3f,\"samples\":[",
            stats.format,stats.width,stats.height,stats.fps,stats.first_frame,(unsigned long long)stats.frames,
            (unsigned long long)stats.idrs,(unsigned long long)stats.bytes,duration,fps);
    for (int i=0;i<stats.sample_count;++i)
        fprintf(f,"%s{\"ms\":%.3f,\"fps\":%.3f,\"frames\":%llu}",i?",":"",stats.samples[i].ms,
                stats.samples[i].fps,(unsigned long long)stats.samples[i].frames);
    fprintf(f," ]},\n  \"audio_packets\":%llu,\"input_test\":{\"sent\":%s,\"forward\":%d,\"reverse\":%d},"
            "\n  \"rtt\":{\"available\":%s,\"ms\":%u,\"variance_ms\":%u}\n}\n",
            (unsigned long long)stats.audio,bool_text(stats.input_sent),stats.input_forward,stats.input_reverse,
            bool_text(stats.rtt_available),stats.rtt,stats.rtt_variance);
    bool ok = !ferror(f);
    if (fclose(f)) ok=false;
    if (!ok) { fprintf(stderr,"JSON output write failed\n"); return 2; }
    return result;
}
static int run_stream(Options *o, SERVER_DATA *server) {
    PAPP_LIST list=NULL;
    if (gs_applist(server,&list)!=GS_OK) return report(o,1,gs_error?gs_error:"Cannot fetch application list");
    int app=0;
    for (PAPP_LIST p=list;p;p=p->next) if (p->name && !strcmp(p->name,o->app)) app=p->id;
    while (list) { PAPP_LIST next=list->next; free(list->name); free(list); list=next; }
    if (!app) return report(o,1,"Requested application was not found");
    STREAM_CONFIGURATION cfg;
    LiInitializeStreamConfiguration(&cfg);
    cfg.width=o->width; cfg.height=o->height; cfg.fps=o->fps; cfg.bitrate=o->bitrate;
    cfg.packetSize=1024; cfg.streamingRemotely=STREAM_CFG_AUTO;
    cfg.audioConfiguration=AUDIO_CONFIGURATION_STEREO;
    cfg.supportedVideoFormats=VIDEO_FORMAT_H264 | (!strcmp(o->codec,"hevc")?VIDEO_FORMAT_H265:0);
    cfg.clientRefreshRateX100=o->fps*100; cfg.encryptionFlags=ENCFLG_ALL;
    printf("http_action=%s app=%s app_id=%d\n",server->currentGame?"resume":"launch",o->app,app);
    if (gs_start_app(server,&cfg,app,false,false,0)!=GS_OK) return report(o,1,gs_error?gs_error:"Launch/resume failed");
    // HTTP operations have finished; common-c alone uses the true UDP address.
    char relay[64];
    snprintf(relay,sizeof(relay),"%s",o->rtsp);
    char *colon=strrchr(relay,':'); *colon++=0;
    server->serverInfo.address=o->udp;
    server->serverInfo.rtspTcpAddress=relay;
    server->serverInfo.rtspTcpPort=(unsigned short)atoi(colon);
    server->serverInfo.requireEncryptedStreams=o->strict;
    LiInitializeConnectionCallbacks(&listener);
    listener.stageStarting=stage_start; listener.stageComplete=stage_complete;
    listener.stageFailed=stage_failed; listener.connectionStarted=connected;
    listener.connectionTerminated=terminated; listener.logMessage=native_log;
    LiInitializeVideoCallbacks(&video); video.setup=video_setup; video.submitDecodeUnit=frame;
    video.capabilities=CAPABILITY_DIRECT_SUBMIT;
    LiInitializeAudioCallbacks(&audio); audio.init=audio_init; audio.decodeAndPlaySample=audio_packet;
    audio.capabilities=CAPABILITY_DIRECT_SUBMIT;
    start_server=&server->serverInfo; start_config=&cfg;
    stats.native_start=clock_seconds();
    pthread_t worker;
    if (pthread_create(&worker,NULL,start_thread,NULL)) return report(o,1,"Cannot start native startup thread");
    for (;;) {
        pthread_mutex_lock(&stats.lock); bool done=stats.startup_done; pthread_mutex_unlock(&stats.lock);
        if (done) break;
        if (probe_interrupted || clock_seconds()-stats.native_start>=45) LiInterruptConnection();
        pause_ms(50);
    }
    pthread_join(worker,NULL);
    if (stats.startup_error) {
        stats.accepting_samples=false;
        stats.elapsed=clock_seconds()-stats.native_start;
        const char *error=stats.startup_error==ML_ERROR_ENCRYPTION_REQUIRED?"Required encryption unavailable":
                          stats.startup_error==ML_ERROR_UNSUPPORTED_TRANSPORT?"Split transport/session identifiers unavailable":
                          "Native startup failed; see stageFailed error code";
        return report(o,probe_interrupted?130:1,error);
    }
    int result=0;
    const char *error=NULL;
    double last=stats.native_start;
    uint64_t last_frames=0;
    while (!probe_interrupted) {
        double current=clock_seconds();
        pthread_mutex_lock(&stats.lock);
        bool ended=stats.terminated; uint64_t frames=stats.frames;
        if (current-last>=1 && stats.sample_count<3601) {
            int i=stats.sample_count++;
            stats.samples[i].ms=(current-stats.native_start)*1000;
            stats.samples[i].fps=(frames-last_frames)/(current-last);
            stats.samples[i].frames=frames;
            printf("sample_ms=%.3f frames=%llu fps=%.3f audio_packets=%llu\n",stats.samples[i].ms,
                   (unsigned long long)frames,stats.samples[i].fps,(unsigned long long)stats.audio);
            last=current; last_frames=frames;
        }
        pthread_mutex_unlock(&stats.lock);
        if (ended) { result=1; error="Stream terminated; see connectionTerminated error code"; break; }
        if (!frames && current-stats.started>=10) { result=1; error="No complete video frame within 10 seconds"; break; }
        if (current-stats.started>=o->seconds) break;
        if (o->input && !stats.input_sent && frames>=2 && current-stats.started>=1) {
            stats.input_forward=LiSendMouseMoveEvent(1,0);
            pause_ms(20);
            stats.input_reverse=LiSendMouseMoveEvent(-1,0);
            stats.input_sent=true;
            printf("input_test forward=%d reverse=%d\n",stats.input_forward,stats.input_reverse);
        }
        pause_ms(50);
    }
    stats.rtt_available=LiGetEstimatedRttInfo(&stats.rtt,&stats.rtt_variance);
    pthread_mutex_lock(&stats.lock);
    stats.accepting_samples=false;
    stats.elapsed=clock_seconds()-stats.native_start;
    pthread_mutex_unlock(&stats.lock);
    // Stop joins callbacks before final stats/JSON are read, even after SIGINT.
    LiStopConnection();
    if (probe_interrupted) result=130;
    if (!stats.frames && result==0) { result=1; error="No complete video frames received"; }
    return report(o,result,error);
}
int main(int argc, char **argv) {
    setvbuf(stdout,NULL,_IOLBF,0);
    umask(077);
    epoch=clock_seconds();
    if (argc<2 || !strcmp(argv[1],"--help")) { usage(NULL); return argc<2?2:0; }
    bool pairing=!strcmp(argv[1],"pair");
    if (!pairing && strcmp(argv[1],"stream")) { usage(NULL); return 2; }
    for (int i=2;i<argc;++i) if (!strcmp(argv[i],"--help")) { usage(argv[1]); return 0; }
    Options o={.host="127.0.0.1",.app="Desktop",.codec="hevc",.seconds=30,.width=1920,
               .height=1080,.fps=60,.bitrate=15000,.strict=true,.http_port=47989};
    for (int i=2;i<argc;++i) {
        const char *a=argv[i];
        if (!strcmp(a,"--strict")) { o.strict=true; continue; }
        if (!strcmp(a,"--no-strict")) { o.strict=false; continue; }
        if (!strcmp(a,"--input-test")) { o.input=true; continue; }
        if (++i>=argc) { fprintf(stderr,"Missing value for %s\n",a); return 2; }
        const char *v=argv[i];
        if (!strcmp(a,"--http-host")) o.host=v;
        else if (!strcmp(a,"--keydir")) o.keydir=v;
        else if (!strcmp(a,"--udp-host")) o.udp=v;
        else if (!strcmp(a,"--rtsp-tcp")) o.rtsp=v;
        else if (!strcmp(a,"--app")) o.app=v;
        else if (!strcmp(a,"--json")) o.json=v;
        else if (!strcmp(a,"--codec")) o.codec=v;
        else if (!strcmp(a,"--name")) probe_client_name=v;
        else {
            int *target=NULL, max=0, min=1;
            if (!strcmp(a,"--seconds")) { target=&o.seconds; max=3600; }
            if (!strcmp(a,"--width")) { target=&o.width; max=8192; min=16; }
            if (!strcmp(a,"--height")) { target=&o.height; max=8192; min=16; }
            if (!strcmp(a,"--fps")) { target=&o.fps; max=240; }
            if (!strcmp(a,"--bitrate")) { target=&o.bitrate; max=200000; }
            if (!strcmp(a,"--http-port")) { target=&o.http_port; max=65535; }
            if (!strcmp(a,"--https-port")) {
                int n=number(v,1,65535);
                if (n<0) { fprintf(stderr,"Invalid HTTPS port\n"); return 2; }
                probe_https_port=(unsigned short)n; continue;
            }
            if (!target || (*target=number(v,min,max))<0) { fprintf(stderr,"Unknown/invalid option: %s\n",a); return 2; }
        }
    }
    if (!o.keydir || strcmp(o.host,"127.0.0.1") ||
        strspn(probe_client_name,"abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_")!=strlen(probe_client_name) ||
        strlen(probe_client_name)<1 || strlen(probe_client_name)>64 ||
        (strcmp(o.codec,"hevc") && strcmp(o.codec,"h264")) || (o.height&1) ||
        (!pairing && (!o.udp || !numeric_ip(o.udp) || !o.rtsp || strlen(o.rtsp)>=64 ||
                     strncmp(o.rtsp,"127.0.0.1:",10) || number(o.rtsp+10,1,65535)<0))) {
        fprintf(stderr,"Invalid arguments: use a private keydir, numeric UDP IP and loopback forwarding endpoints\n"); return 2;
    }
    struct sigaction action={0}; action.sa_handler=signal_handler;
    sigaction(SIGINT,&action,NULL); sigaction(SIGTERM,&action,NULL);
    signal(SIGPIPE,SIG_IGN);
    probe_pairing=pairing;
    if (probe_prepare_keydir(o.keydir,pairing)) return report(&o,1,gs_error);
    SERVER_DATA server={0};
    event("starting",-1,0);
    if (gs_init(&server,(char*)o.host,(unsigned short)o.http_port,o.keydir,0,true)!=GS_OK) {
        event("failed",-1,GS_IO_ERROR);
        int r=report(&o,probe_interrupted?130:1,gs_error?gs_error:"Cannot contact local forward; check ssh -L");
        http_cleanup(); return r;
    }
    event("complete",-1,0);
    int result;
    if (pairing) {
        unsigned int random;
        if (RAND_bytes((unsigned char*)&random,sizeof(random))!=1) result=report(&o,1,"PIN generation failed");
        else {
            char pin[5]; snprintf(pin,sizeof(pin),"%04u",random%10000);
            printf("PIN=%s\n在 Sunshine 网页里输入 PIN（最多等待 180 秒）。\n",pin);
            if (gs_pair(&server,pin)!=GS_OK) result=report(&o,probe_interrupted?130:1,gs_error?gs_error:"Pairing failed");
            else if (probe_save_pin(o.keydir)!=GS_OK) result=report(&o,1,gs_error);
            else { puts("pairing_complete=true"); result=0; }
        }
    } else result=run_stream(&o,&server);
    http_cleanup();
    return result;
}
