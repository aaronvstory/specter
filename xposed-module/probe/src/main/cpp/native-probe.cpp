// Native-read blind-spot probe: reads a system property via libc __system_property_get, IN PROCESS.
// Xposed hooks the Java android.os.SystemProperties.get; this path never touches Java, so comparing
// the two answers for the same key tells us whether an NDK-based fingerprinter sees the real device.
#include <jni.h>
#include <sys/system_properties.h>

extern "C" JNIEXPORT jstring JNICALL
Java_com_specter_probe_ProbeActivity_nativeGetprop(JNIEnv *env, jobject, jstring key) {
    const char *k = env->GetStringUTFChars(key, nullptr);
    char buf[PROP_VALUE_MAX] = {0};
    __system_property_get(k, buf);
    env->ReleaseStringUTFChars(key, k);
    return env->NewStringUTF(buf);
}
