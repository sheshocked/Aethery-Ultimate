#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>
#include <unistd.h>
#include <pthread.h>

static pthread_mutex_t g_protector_mutex = PTHREAD_MUTEX_INITIALIZER;

#define LOG_TAG "Bridge/BypassSocket"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

struct go_string { const char *str; long n; };

extern int awgStartProxy(
        struct go_string ifname,
        struct go_string settings,
        struct go_string uapipath,
        int bypass,
        struct go_string dnsconfig
);
extern char *awgGetProxyConfig(int handle);
extern int awgUpdateProxyTunnelPeers(int handle, struct go_string settings);
extern void awgTurnProxyTunnelOff(int handle);


extern void setupDnsJni(JNIEnv* env);
extern void teardownDnsJni(JNIEnv* env);

// Global JNI state
JavaVM *g_jvm = NULL;

// Socket protector
static jobject g_protector = NULL;
static jmethodID g_protectMethod = NULL;

// Status callback
static jclass    g_tunnelStatusBridgeClass = NULL;
static jmethodID g_onStatusChangedMethod   = NULL;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    g_jvm = vm;
    LOGD("JNI_OnLoad: g_jvm cached = %p", g_jvm);

    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        LOGE("JNI_OnLoad: Failed to get JNIEnv");
        return JNI_VERSION_1_6;
    }

    jclass clazz = (*env)->FindClass(env, "com/zaneschepke/tunnel/backend/TunnelStatusBridge");
    if (clazz == NULL) {
        LOGE("JNI_OnLoad: CRITICAL - Failed to find TunnelStatusBridge class!");
        return JNI_VERSION_1_6;
    }

    g_tunnelStatusBridgeClass = (*env)->NewGlobalRef(env, clazz);
    (*env)->DeleteLocalRef(env, clazz);

    g_onStatusChangedMethod = (*env)->GetStaticMethodID(
            env,
            g_tunnelStatusBridgeClass,
            "onStatusChanged",
            "(II)V"
    );

    if (g_onStatusChangedMethod == NULL) {
        LOGE("JNI_OnLoad: CRITICAL - Failed to find onStatusChanged method ID!");
    } else {
        LOGD("JNI_OnLoad: TunnelStatusBridge successfully linked up.");
    }

    setupDnsJni(env);

    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM *vm, void *reserved) {
    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) == JNI_OK) {
        if (g_protector != NULL) {
            (*env)->DeleteGlobalRef(env, g_protector);
            g_protector = NULL;
        }
        if (g_tunnelStatusBridgeClass != NULL) {
            (*env)->DeleteGlobalRef(env, g_tunnelStatusBridgeClass);
            g_tunnelStatusBridgeClass = NULL;
        }
        teardownDnsJni(env);
    }
    g_protectMethod = NULL;
    g_onStatusChangedMethod = NULL;
    g_jvm = NULL;
}

JNIEXPORT jint JNICALL
Java_com_zaneschepke_tunnel_backend_ProxyBackend_awgStartProxy(
        JNIEnv *env, jclass c,
        jstring ifname, jstring settings, jstring uapipath, jint bypass,
        jstring dnsConfigJson)
{
    const char *ifname_str = (*env)->GetStringUTFChars(env, ifname, 0);
    size_t ifname_len = (*env)->GetStringUTFLength(env, ifname);
    const char *settings_str = (*env)->GetStringUTFChars(env, settings, 0);
    size_t settings_len = (*env)->GetStringUTFLength(env, settings);
    const char *uapipath_str = (*env)->GetStringUTFChars(env, uapipath, 0);
    size_t uapipath_len = (*env)->GetStringUTFLength(env, uapipath);

    const char *dns_str = "";
    size_t dns_len = 0;
    if (dnsConfigJson != NULL) {
        dns_str = (*env)->GetStringUTFChars(env, dnsConfigJson, 0);
        dns_len = (*env)->GetStringUTFLength(env, dnsConfigJson);
    }

    int ret = awgStartProxy(
            (struct go_string){ .str = ifname_str, .n = ifname_len },
            (struct go_string){ .str = settings_str, .n = settings_len },
            (struct go_string){ .str = uapipath_str, .n = uapipath_len },
            bypass,
            (struct go_string){ .str = dns_str, .n = dns_len }
    );

    (*env)->ReleaseStringUTFChars(env, ifname, ifname_str);
    (*env)->ReleaseStringUTFChars(env, settings, settings_str);
    (*env)->ReleaseStringUTFChars(env, uapipath, uapipath_str);
    if (dnsConfigJson != NULL) {
        (*env)->ReleaseStringUTFChars(env, dnsConfigJson, dns_str);
    }
    return ret;
}

JNIEXPORT void JNICALL
Java_com_zaneschepke_tunnel_backend_ProxyBackend_awgTurnProxyTunnelOff(JNIEnv *env, jclass c, jint handle)
{
    awgTurnProxyTunnelOff(handle);
}

JNIEXPORT jstring JNICALL
Java_com_zaneschepke_tunnel_backend_ProxyBackend_awgGetProxyConfig(JNIEnv *env, jclass c, jint handle)
{
    jstring ret;
    char *config = awgGetProxyConfig(handle);
    if (!config)
        return NULL;
    ret = (*env)->NewStringUTF(env, config);
    free(config);
    return ret;
}

JNIEXPORT void JNICALL
Java_com_zaneschepke_tunnel_backend_ProxyBackend_awgSetSocketProtector(
        JNIEnv *env, jclass c, jobject protector) {
    pthread_mutex_lock(&g_protector_mutex);

    // Clear old protector
    if (g_protector != NULL) {
        (*env)->DeleteGlobalRef(env, g_protector);
        g_protector = NULL;
        g_protectMethod = NULL;
    }

    if (protector != NULL) {
        g_protector = (*env)->NewGlobalRef(env, protector);

        jclass protectorClass = (*env)->GetObjectClass(env, protector);
        if (protectorClass != NULL) {
            g_protectMethod = (*env)->GetMethodID(env, protectorClass, "bypass", "(I)I");
            (*env)->DeleteLocalRef(env, protectorClass);
        }

        if (g_protectMethod != NULL) {
            LOGD("awgSetSocketProtector: successfully registered (methodID = %p)", g_protectMethod);
        } else {
            LOGE("awgSetSocketProtector: Socket protector failed to get bypass method ID");
        }
    } else {
        LOGD("awgSetSocketProtector: Socket protector cleared successfully");
    }
    pthread_mutex_unlock(&g_protector_mutex);
}

JNIEXPORT jint JNICALL
Java_com_zaneschepke_tunnel_backend_ProxyBackend_awgUpdateProxyTunnelPeers(JNIEnv *env, jclass c, jint handle, jstring settings)
{
    const char *settings_str = (*env)->GetStringUTFChars(env, settings, 0);
    size_t settings_len = (*env)->GetStringUTFLength(env, settings);
    int ret = awgUpdateProxyTunnelPeers(handle, (struct go_string){
        .str = settings_str,
        .n = settings_len
    });
    (*env)->ReleaseStringUTFChars(env, settings, settings_str);
    return ret;
}

int bypass_socket(int fd) {
    if (fd < 0) {
        LOGE("bypass_socket: Invalid FD %d", fd);
        return 0;
    }

    JNIEnv *env = NULL;
    if (g_jvm == NULL) {
        LOGE("bypass_socket: g_jvm is NULL");
        return 0;
    }

    jint rs = (*g_jvm)->GetEnv(g_jvm, (void **)&env, JNI_VERSION_1_6);

    // Short retry for AttachCurrentThreadAsDaemon
    if (rs == JNI_EDETACHED) {
        int retries = 3;
        while (retries-- > 0) {
            if ((*g_jvm)->AttachCurrentThreadAsDaemon(g_jvm, (JNIEnv **)&env, NULL) == JNI_OK) {
                break;
            }
            usleep(5000);
        }
        if (env == NULL) {
            LOGE("bypass_socket: AttachCurrentThreadAsDaemon failed after retries (fd=%d)", fd);
            return 0;
        }
    } else if (rs != JNI_OK) {
        LOGE("bypass_socket: GetEnv failed with code %d (fd=%d)", rs, fd);
        return 0;
    }

    if (env == NULL) {
        LOGE("bypass_socket: env is NULL after attach/GetEnv (fd=%d)", fd);
        return 0;
    }

    // Short retry when protector is not yet visible
    const int maxRetries = 4;
    const useconds_t backoff = 8000;

    for (int attempt = 0; attempt < maxRetries; attempt++) {
        pthread_mutex_lock(&g_protector_mutex);

        if (g_protector != NULL && g_protectMethod != NULL) {
            jobject local = (*env)->NewLocalRef(env, g_protector);
            jmethodID method = g_protectMethod;
            pthread_mutex_unlock(&g_protector_mutex);

            if (local == NULL) {
                LOGE("bypass_socket: NewLocalRef failed (fd=%d)", fd);
                return 0;
            }

            if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);

            int result = (*env)->CallIntMethod(env, local, method, fd);

            if ((*env)->ExceptionCheck(env)) {
                LOGE("bypass_socket: Exception from protector.bypass() (fd=%d)", fd);
                (*env)->ExceptionDescribe(env);
                (*env)->ExceptionClear(env);
                result = 0;
            }

            (*env)->DeleteLocalRef(env, local);

            if (attempt > 0) {
                LOGD("bypass_socket: succeeded after %d retries (fd=%d)", attempt, fd);
            }
            LOGD("bypass_socket: fd=%d result=%d", fd, result);
            return result;
        }

        pthread_mutex_unlock(&g_protector_mutex);

        if (attempt == 0) {
            LOGD("bypass_socket: protector not visible yet, retrying (fd=%d)", fd);
        }

        if (attempt < maxRetries - 1) {
            usleep(backoff);
        }
    }

    LOGE("bypass_socket: protector still not ready after retries (fd=%d)", fd);
    return 0;
}

#undef LOG_TAG
#define LOG_TAG "Bridge/TunnelStatus"

void awgNotifyStatus(int32_t handle, int32_t code) {
    if (g_jvm == NULL) {
        LOGE("awgNotifyStatus: g_jvm is NULL, dropping event.");
        return;
    }

    JNIEnv *env = NULL;
    jint rs = (*g_jvm)->GetEnv(g_jvm, (void **)&env, JNI_VERSION_1_6);

    if (rs == JNI_EDETACHED) {
        if ((*g_jvm)->AttachCurrentThreadAsDaemon(g_jvm, (JNIEnv **)&env, NULL) != JNI_OK) {
            LOGE("awgNotifyStatus: Failed to attach native Go thread to JVM.");
            return;
        }
    } else if (rs != JNI_OK) {
        LOGE("awgNotifyStatus: GetEnv failed with code %d", rs);
        return;
    }

    if (env == NULL) return;

    // Check if JNI_OnLoad failed to resolve the references earlier
    if (g_tunnelStatusBridgeClass == NULL || g_onStatusChangedMethod == NULL) {
        LOGE("awgNotifyStatus: Cannot callback; cached class/method references are missing.");
        return;
    }

    LOGD("awgNotifyStatus: Forwarding event to Kotlin (handle=%d, code=%d)", handle, code);

    (*env)->CallStaticVoidMethod(
            env,
            g_tunnelStatusBridgeClass,
            g_onStatusChangedMethod,
            (jint)handle,
            (jint)code
    );

    // Check for exceptions thrown inside Kotlin code execution
    if ((*env)->ExceptionCheck(env)) {
        LOGE("awgNotifyStatus: Exception occurred within TunnelStatusBridge.onStatusChanged");
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
    }
}