# R8 rules for the release (distributable) build. Goal: rename/shrink everything so a decompile reads as
# a.a(b), while keeping the handful of names something OUTSIDE the Java compiler resolves by string.

# --- Xposed / LSPosed entry point ---------------------------------------------------------------------
# assets/xposed_init names this class as a STRING; LSPosed loads it by that name. Renaming it produces an
# APK that installs and silently hooks NOTHING (verified class of bug). Keep the class + all its members.
-keep class com.specter.module.HookEntry { *; }
# Any hook callback the framework invokes by name (load-package / zygote-init / resources).
-keep class * implements de.robv.android.xposed.IXposedHookLoadPackage { *; }
-keep class * implements de.robv.android.xposed.IXposedHookZygoteInit { *; }
-keep class * implements de.robv.android.xposed.IXposedHookInitPackageResources { *; }
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
