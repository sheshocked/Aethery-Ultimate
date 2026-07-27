#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "Bridge/Dns", __VA_ARGS__)

extern JavaVM *g_jvm;

static jclass g_dnsResolverClass = NULL;
static jmethodID g_onResolutionCompleteMethod = NULL;
static jmethodID g_lookupOnUnderlayNetworkMethod = NULL;

extern void StartResolveBootstrap(int64_t id, const char* host, const char* protocol, const char* resolvedUpstream, const char* originalUpstream, int bypass);

extern void GoSetUnderlayNetworkHandle(int64_t handle);

void setupDnsJni(JNIEnv* env) {
    jclass clazz = (*env)->FindClass(env, "com/zaneschepke/tunnel/backend/dns/NativeDnsResolver");
    if (clazz == NULL) {
        LOGE("Failed to find NativeDnsResolver class");
        return;
    }
    g_dnsResolverClass = (*env)->NewGlobalRef(env, clazz);
    (*env)->DeleteLocalRef(env, clazz);

    g_onResolutionCompleteMethod = (*env)->GetStaticMethodID(
            env, g_dnsResolverClass, "onResolutionComplete", "(JLjava/lang/String;)V");

    g_lookupOnUnderlayNetworkMethod = (*env)->GetStaticMethodID(
            env, g_dnsResolverClass, "lookupOnUnderlayNetwork",
            "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
    if (g_lookupOnUnderlayNetworkMethod == NULL) {
        LOGE("Failed to find lookupOnUnderlayNetwork");
    }
}

void teardownDnsJni(JNIEnv* env) {
    if (g_dnsResolverClass != NULL) {
        (*env)->DeleteGlobalRef(env, g_dnsResolverClass);
        g_dnsResolverClass = NULL;
    }
    g_onResolutionCompleteMethod = NULL;
    g_lookupOnUnderlayNetworkMethod = NULL;
}

// Called by Go to push the result back to Kotlin
void NotifyDnsResult(int64_t id, const char* result) {
    if (g_jvm == NULL || g_dnsResolverClass == NULL || g_onResolutionCompleteMethod == NULL) return;

    JNIEnv *env = NULL;
    jint rs = (*g_jvm)->GetEnv(g_jvm, (void **)&env, JNI_VERSION_1_6);

    if (rs == JNI_EDETACHED) {
        if ((*g_jvm)->AttachCurrentThreadAsDaemon(g_jvm, (JNIEnv **)&env, NULL) != JNI_OK) return;
    } else if (rs != JNI_OK) {
        return;
    }

    jstring jresult = (*env)->NewStringUTF(env, result);
    (*env)->CallStaticVoidMethod(env, g_dnsResolverClass, g_onResolutionCompleteMethod, (jlong)id, jresult);

    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    (*env)->DeleteLocalRef(env, jresult);
}

JNIEXPORT void JNICALL
Java_com_zaneschepke_tunnel_backend_dns_NativeDnsResolver_startBootstrapResolution(
        JNIEnv* env,
        jclass clazz,
        jlong id,
        jstring host,
        jstring protocol,
        jstring resolvedUpstream,
        jstring originalUpstream,
        jint bypass)
{

    const char* chost = host ? (*env)->GetStringUTFChars(env, host, NULL) : "";
    const char* cprotocol = protocol ? (*env)->GetStringUTFChars(env, protocol, NULL) : "";
    const char* cresolvedUpstream = resolvedUpstream ? (*env)->GetStringUTFChars(env, resolvedUpstream, NULL) : "";
    const char* coriginalUpstream = originalUpstream ? (*env)->GetStringUTFChars(env, originalUpstream, NULL) : "";

    // Function will synchronously copy before returning
    StartResolveBootstrap(
            (int64_t)id,
            chost,
            cprotocol,
            cresolvedUpstream,
            coriginalUpstream,
            bypass ? 1 : 0
    );

    // Release everything after copied
    if (host) (*env)->ReleaseStringUTFChars(env, host, chost);
    if (protocol) (*env)->ReleaseStringUTFChars(env, protocol, cprotocol);
    if (resolvedUpstream) (*env)->ReleaseStringUTFChars(env, resolvedUpstream, cresolvedUpstream);
    if (originalUpstream) (*env)->ReleaseStringUTFChars(env, originalUpstream, coriginalUpstream);
}

// Called by go as fallback network bound DNS requests for < Android 10
char* JniLookupOnUnderlayNetwork(const char* host, const char* networkFamily) {
    if (g_jvm == NULL || g_dnsResolverClass == NULL || g_lookupOnUnderlayNetworkMethod == NULL) {
        return NULL;
    }

    JNIEnv *env = NULL;
    jint rs = (*g_jvm)->GetEnv(g_jvm, (void **)&env, JNI_VERSION_1_6);
    if (rs == JNI_EDETACHED) {
        if ((*g_jvm)->AttachCurrentThreadAsDaemon(g_jvm, (JNIEnv **)&env, NULL) != JNI_OK) {
            return NULL;
        }
    } else if (rs != JNI_OK) {
        return NULL;
    }

    jstring jhost = (*env)->NewStringUTF(env, host ? host : "");
    jstring jfam  = (*env)->NewStringUTF(env, networkFamily ? networkFamily : "");
    jstring jresult = (jstring)(*env)->CallStaticObjectMethod(
            env, g_dnsResolverClass, g_lookupOnUnderlayNetworkMethod, jhost, jfam);

    char *out = NULL;
    if (!(*env)->ExceptionCheck(env) && jresult != NULL) {
        const char *utf = (*env)->GetStringUTFChars(env, jresult, NULL);
        if (utf != NULL) {
            out = strdup(utf);
            (*env)->ReleaseStringUTFChars(env, jresult, utf);
        }
    }
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);

    if (jhost) (*env)->DeleteLocalRef(env, jhost);
    if (jfam) (*env)->DeleteLocalRef(env, jfam);
    if (jresult) (*env)->DeleteLocalRef(env, jresult);

    return out;
}

JNIEXPORT void JNICALL
Java_com_zaneschepke_tunnel_backend_dns_NativeDnsResolver_setUnderlayNetworkHandleNative(
        JNIEnv* env, jclass clazz, jlong handle)
{
    GoSetUnderlayNetworkHandle((int64_t) handle);
}

