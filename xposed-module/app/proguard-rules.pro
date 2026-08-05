# R8 rules for the release (distributable) build. Goal: rename/shrink everything so a decompile reads as
# a.a(b), while keeping the handful of names something OUTSIDE the Java compiler resolves by string.

# --- Xposed / LSPosed entry point ---------------------------------------------------------------------
# assets/xposed_init names this class as a STRING; LSPosed loads it by that name. Renaming it produces an
# APK that installs and silently hooks NOTHING (verified class of bug). Keep the class, its members, AND
# its nested classes (HookEntry$1 …) — the anonymous hook callbacks live there and are SEPARATE classes.
-keep class com.specter.module.HookEntry { *; }
-keep class com.specter.module.HookEntry$* { *; }
# Any hook callback the framework invokes by name (load-package / zygote-init / resources).
-keep class * implements de.robv.android.xposed.IXposedHookLoadPackage { *; }
-keep class * implements de.robv.android.xposed.IXposedHookZygoteInit { *; }
-keep class * implements de.robv.android.xposed.IXposedHookInitPackageResources { *; }
# THE critical rule: every XC_MethodHook subclass (all the anonymous before/afterHookedMethod callbacks,
# wherever they live) must keep its class identity AND method names — LSPosed's XposedBridge dispatches to
# beforeHookedMethod/afterHookedMethod by override, and R8 merging/renaming them silently kills every hook
# while the module still loads (native layer keeps working, so only the Java-side fields leak — the exact
# failure this rule fixes). Proven on-device: without it, build_model/fingerprint/board leak REAL.
-keep class * extends de.robv.android.xposed.XC_MethodHook { *; }
-keepclassmembers class * extends de.robv.android.xposed.XC_MethodHook {
    public protected void beforeHookedMethod(...);
    public protected void afterHookedMethod(...);
}
# The Xposed API is provided at runtime (compileOnly stub), never packaged — just don't warn about it.
-dontwarn de.robv.android.xposed.**
-dontwarn android.**

# --- Things the app reads reflectively / by contract --------------------------------------------------
# BuildConfig carries VERSION_NAME (the status-attestation heartbeat, matched by the dex-verify checks) and
# the SEED_* fields MainActivity reads. Keep it whole so those field names survive.
-keep class com.specter.module.BuildConfig { *; }

# String LITERALS (version markers, the activation public key, prop-alias keys) are NOT obfuscated by R8 by
# default — no string-encryption is enabled — so the dex marker-string checks keep working. This comment is
# the reminder: if string encryption is ever added, exempt those markers or update the verify checks.

# Keep enough metadata that any reflective model access still resolves; cheap, and avoids subtle breakage.
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*
