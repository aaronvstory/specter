// Native-read blind-spot probe: reads a system property via libc __system_property_get, IN PROCESS.
// Xposed hooks the Java android.os.SystemProperties.get; this path never touches Java, so comparing
// the two answers for the same key tells us whether an NDK-based fingerprinter sees the real device.
#include <jni.h>
#include <sys/system_properties.h>
#include <string>
#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <android/sensor.h>

extern "C" JNIEXPORT jstring JNICALL
Java_com_specter_probe_ProbeActivity_nativeGetprop(JNIEnv *env, jobject, jstring key) {
    const char *k = env->GetStringUTFChars(key, nullptr);
    char buf[PROP_VALUE_MAX] = {0};
    __system_property_get(k, buf);
    env->ReleaseStringUTFChars(key, k);
    return env->NewStringUTF(buf);
}

// Native GPU strings via a headless EGL pbuffer + GLES2 context — the DIRECT path a native
// fingerprinter uses, which Specter's Zygisk glGetString inline hook targets. Reading it here proves
// (or disproves) that the native hook engaged, independent of the Java GLES20 hook. Returns
// "VENDOR|RENDERER|VERSION" or "ERR:<stage>".
static const char *gs(GLenum e) {
    const GLubyte *s = glGetString(e);
    return s ? reinterpret_cast<const char *>(s) : "";
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_specter_probe_ProbeActivity_nativeGlStrings(JNIEnv *env, jobject) {
    EGLDisplay dpy = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (dpy == EGL_NO_DISPLAY) return env->NewStringUTF("ERR:no-display");
    if (!eglInitialize(dpy, nullptr, nullptr)) return env->NewStringUTF("ERR:init");
    const EGLint cfgAttrs[] = {
        EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_NONE
    };
    EGLConfig cfg; EGLint n = 0;
    if (!eglChooseConfig(dpy, cfgAttrs, &cfg, 1, &n) || n < 1)
        return env->NewStringUTF("ERR:config");
    const EGLint pbAttrs[] = { EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE };
    EGLSurface surf = eglCreatePbufferSurface(dpy, cfg, pbAttrs);
    if (surf == EGL_NO_SURFACE) return env->NewStringUTF("ERR:surface");
    const EGLint ctxAttrs[] = { EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE };
    EGLContext ctx = eglCreateContext(dpy, cfg, EGL_NO_CONTEXT, ctxAttrs);
    if (ctx == EGL_NO_CONTEXT) return env->NewStringUTF("ERR:context");
    if (!eglMakeCurrent(dpy, surf, surf, ctx)) return env->NewStringUTF("ERR:makecurrent");
    std::string out = std::string(gs(GL_VENDOR)) + "|" + gs(GL_RENDERER) + "|" + gs(GL_VERSION);
    eglMakeCurrent(dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    eglDestroyContext(dpy, ctx);
    eglDestroySurface(dpy, surf);
    eglTerminate(dpy);
    return env->NewStringUTF(out.c_str());
}

// Native sensor list via the NDK ASensorManager/ASensor path — the direct-JNI reads Specter's
// ASensor_getName/getVendor hooks target (what a native fingerprinter uses). Returns "name|vendor;..."
// or "ERR:<stage>". The COUNT and pointers come from the real manager (unhooked); the names/vendors
// are what the relabel hooks rewrite, so this proves the hook engaged.
extern "C" JNIEXPORT jstring JNICALL
Java_com_specter_probe_ProbeActivity_nativeSensors(JNIEnv *env, jobject) {
    ASensorManager *mgr = ASensorManager_getInstance();
    if (!mgr) return env->NewStringUTF("ERR:no-manager");
    ASensorList list = nullptr;
    int n = ASensorManager_getSensorList(mgr, &list);
    if (n <= 0 || !list) return env->NewStringUTF("ERR:no-sensors");
    std::string out;
    for (int i = 0; i < n; i++) {
        const ASensor *s = list[i];
        if (!s) continue;
        const char *name = ASensor_getName(s);
        const char *vendor = ASensor_getVendor(s);
        if (i > 0) out += ";";
        out += (name ? name : "?");
        out += "|";
        out += (vendor ? vendor : "?");
    }
    return env->NewStringUTF(out.c_str());
}
