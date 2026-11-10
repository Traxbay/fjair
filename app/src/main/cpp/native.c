/* JNI bridge: runs UxPlay's RAOP/AirPlay server and forwards decrypted audio/video to Kotlin (tv.airplay.Native). */
#include <jni.h>
#include <android/log.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include "raop.h"
#include "dnssd.h"
#include "logger.h"

#define TAG "AirPlayNative"
static JavaVM *vm;
static jclass cls;
static jmethodID m_conn, m_vfmt, m_video, m_afmt, m_audio, m_flush, m_vol, m_client;
static raop_t *raop;
static dnssd_t *dnssd;
static char pkhex[80];

static JNIEnv *env_for_thread(int *attached) {
    JNIEnv *e = NULL;
    *attached = 0;
    if ((*vm)->GetEnv(vm, (void **) &e, JNI_VERSION_1_6) == JNI_OK) return e;
    if ((*vm)->AttachCurrentThread(vm, &e, NULL) != JNI_OK) return NULL;
    *attached = 1;
    return e;
}
#define UP(call) do { int a_; JNIEnv *e = env_for_thread(&a_); if (e) { call; \
    if ((*e)->ExceptionCheck(e)) (*e)->ExceptionClear(e); if (a_) (*vm)->DetachCurrentThread(vm); } } while (0)

static void log_cb(void *c, int level, const char *msg) {
    int p = level <= LOGGER_ERR ? ANDROID_LOG_ERROR : level <= LOGGER_WARNING ? ANDROID_LOG_WARN
          : level <= LOGGER_INFO ? ANDROID_LOG_INFO : ANDROID_LOG_DEBUG;
    __android_log_write(p, TAG, msg);
}

static jbyteArray bytes(JNIEnv *e, const char *s) {
    int n = s ? (int) strnlen(s, 256) : 0;
    jbyteArray a = (*e)->NewByteArray(e, n);
    if (a && n) (*e)->SetByteArrayRegion(e, a, 0, n, (const jbyte *) s);
    return a;
}

static void conn_init(void *c) { UP((*e)->CallStaticVoidMethod(e, cls, m_conn, JNI_TRUE)); }
static void conn_destroy(void *c) { UP((*e)->CallStaticVoidMethod(e, cls, m_conn, JNI_FALSE)); }
static void conn_reset(void *c, int reason) { UP((*e)->CallStaticVoidMethod(e, cls, m_conn, JNI_FALSE)); }
static void conn_feedback(void *c) {}
static void report_client_request(void *c, char *id, char *model, char *name, bool *admit) {
    __android_log_print(ANDROID_LOG_INFO, TAG, "client %s (%s) id=%s", name, model, id);
    *admit = true;
    /* names arrive from the network: pass raw bytes, Kotlin decodes leniently (NewStringUTF aborts on bad UTF-8) */
    UP(jbyteArray n = bytes(e, name); jbyteArray m = bytes(e, model);
       (*e)->CallStaticVoidMethod(e, cls, m_client, n, m);
       (*e)->DeleteLocalRef(e, n); (*e)->DeleteLocalRef(e, m));
}
static int video_set_codec_and_size(void *c, video_codec_t codec, unsigned short ws, unsigned short hs,
                                    unsigned short xo, unsigned short yo, unsigned short w, unsigned short h) {
    UP((*e)->CallStaticVoidMethod(e, cls, m_vfmt, codec == VIDEO_CODEC_H265 ? JNI_TRUE : JNI_FALSE, (jint) ws, (jint) hs));
    return 0;
}
static void video_process(void *c, raop_ntp_t *ntp, video_decode_struct *d) {
    /* AnnexB access unit, already decrypted; pts is the sender's NTP time in ns */
    UP(jobject b = (*e)->NewDirectByteBuffer(e, d->data, d->data_len);
       (*e)->CallStaticVoidMethod(e, cls, m_video, b, (jlong) (d->ntp_time_remote / 1000));
       (*e)->DeleteLocalRef(e, b));
}
static void audio_get_format(void *c, unsigned char *ct, unsigned short *spf, bool *screen, bool *media, uint64_t *fmt) {
    UP((*e)->CallStaticVoidMethod(e, cls, m_afmt, (jint) *ct, (jint) *spf));
}
static void audio_process(void *c, raop_ntp_t *ntp, audio_decode_struct *d) {
    UP(jobject b = (*e)->NewDirectByteBuffer(e, d->data, d->data_len);
       (*e)->CallStaticVoidMethod(e, cls, m_audio, b, (jint) d->ct, (jlong) (d->ntp_time_remote / 1000));
       (*e)->DeleteLocalRef(e, b));
}
static void flush_cb(void *c) { UP((*e)->CallStaticVoidMethod(e, cls, m_flush)); }
static void nop(void *c) {}
static double client_volume(void *c) { return 0.0; }
static void set_volume(void *c, float v) { UP((*e)->CallStaticVoidMethod(e, cls, m_vol, (jfloat) v)); }
static void register_client(void *c, const char *id, const char *pk, const char *name) {}
static bool check_register(void *c, const char *pk) { return true; }
static const char *passwd(void *c, int *len) { *len = 0; return NULL; }

static raop_callbacks_t cbs;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *v, void *r) { vm = v; return JNI_VERSION_1_6; }

/* Returns the RTSP port, or a negative error code. */
JNIEXPORT jint JNICALL Java_tv_airplay_Native_start(JNIEnv *e, jclass k, jstring jname, jstring jmac, jstring jkey) {
    if (raop) return -1;
    cls = (*e)->NewGlobalRef(e, k);
    m_conn = (*e)->GetStaticMethodID(e, k, "onConn", "(Z)V");
    m_vfmt = (*e)->GetStaticMethodID(e, k, "onVideoFormat", "(ZII)V");
    m_video = (*e)->GetStaticMethodID(e, k, "onVideo", "(Ljava/nio/ByteBuffer;J)V");
    m_afmt = (*e)->GetStaticMethodID(e, k, "onAudioFormat", "(II)V");
    m_audio = (*e)->GetStaticMethodID(e, k, "onAudio", "(Ljava/nio/ByteBuffer;IJ)V");
    m_flush = (*e)->GetStaticMethodID(e, k, "onFlush", "()V");
    m_vol = (*e)->GetStaticMethodID(e, k, "onVolume", "(F)V");
    m_client = (*e)->GetStaticMethodID(e, k, "onClient", "([B[B)V");

    const char *name = (*e)->GetStringUTFChars(e, jname, NULL);
    const char *mac = (*e)->GetStringUTFChars(e, jmac, NULL);
    const char *key = (*e)->GetStringUTFChars(e, jkey, NULL);
    int rc = -2;
    unsigned char hw[6]; unsigned int b[6];
    if (sscanf(mac, "%x:%x:%x:%x:%x:%x", &b[0], &b[1], &b[2], &b[3], &b[4], &b[5]) != 6) { rc = -3; goto out; }
    for (int i = 0; i < 6; i++) hw[i] = (unsigned char) b[i];

    int derr = 0;
    dnssd = dnssd_init(name, (int) strlen(name), (const char *) hw, 6, 0, &derr);
    if (!dnssd) goto out;

    memset(&cbs, 0, sizeof cbs);
    cbs.conn_init = conn_init; cbs.conn_destroy = conn_destroy; cbs.conn_reset = conn_reset;
    cbs.conn_feedback = conn_feedback;
    cbs.audio_process = audio_process; cbs.video_process = video_process;
    cbs.audio_flush = flush_cb; cbs.video_flush = flush_cb;
    cbs.video_pause = nop; cbs.video_resume = nop;
    cbs.audio_set_client_volume = client_volume; cbs.audio_set_volume = set_volume;
    cbs.audio_get_format = audio_get_format;
    cbs.report_client_request = report_client_request;
    cbs.register_client = register_client; cbs.check_register = check_register; cbs.passwd = passwd;
    cbs.video_set_codec_and_size = video_set_codec_and_size;

    raop = raop_init(&cbs);
    if (!raop) goto fail;
    raop_set_log_callback(raop, log_cb, NULL);
    raop_set_log_level(raop, LOGGER_INFO);
    if (raop_init2(raop, 1, mac, key)) goto fail;
    ntp_global_init();
    unsigned short port = 0;
    if (raop_start_httpd(raop, &port) < 0) goto fail;
    raop_set_port(raop, port);
    raop_set_dnssd(raop, dnssd);          /* installs the public key into dnssd */
    dnssd_register_raop(dnssd, port);     /* builds the TXT records served in /info (announcement is NSD's job) */
    dnssd_register_airplay(dnssd, port);
    strncpy(pkhex, dnssd->pk, sizeof(pkhex) - 1);
    rc = port;
    goto out;
fail:
    if (raop) { raop_destroy(raop); raop = NULL; }
    dnssd_destroy(dnssd); dnssd = NULL;
out:
    (*e)->ReleaseStringUTFChars(e, jname, name);
    (*e)->ReleaseStringUTFChars(e, jmac, mac);
    (*e)->ReleaseStringUTFChars(e, jkey, key);
    return rc;
}

JNIEXPORT jstring JNICALL Java_tv_airplay_Native_publicKey(JNIEnv *e, jclass k) {
    return (*e)->NewStringUTF(e, pkhex);
}

JNIEXPORT void JNICALL Java_tv_airplay_Native_stop(JNIEnv *e, jclass k) {
    if (raop) { raop_stop_httpd(raop); raop_destroy(raop); raop = NULL; }
    if (dnssd) { dnssd_destroy(dnssd); dnssd = NULL; }
}

/* which: 0 = _raop._tcp TXT, 1 = _airplay._tcp TXT (length-prefixed key=value, DNS-SD wire format) */
JNIEXPORT jbyteArray JNICALL Java_tv_airplay_Native_txt(JNIEnv *e, jclass k, jint which) {
    if (!dnssd) return NULL;
    int len = 0;
    const char *p = which ? dnssd_get_airplay_txt(dnssd, &len) : dnssd_get_raop_txt(dnssd, &len);
    jbyteArray a = (*e)->NewByteArray(e, len);
    if (a && len > 0) (*e)->SetByteArrayRegion(e, a, 0, len, (const jbyte *) p);
    return a;
}
