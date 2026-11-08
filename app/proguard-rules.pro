# ---- Mura release rules (R8) ----
# Keep Mura's own classes un-renamed: stack traces stay readable and nothing reflective breaks.
# (Obfuscation isn't a privacy feature — the code is meant to be auditable.)
-keep class com.mura.mesh.** { *; }

# Tor for Android: JNI entry points and the control library.
-keep class org.torproject.jni.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
-keep class net.freehaven.tor.control.** { *; }
-dontwarn net.freehaven.tor.control.**
-dontwarn org.torproject.**

# Tink
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-keep class com.google.crypto.tink.** { *; }

# ZXing scanner
-keep class com.journeyapps.barcodescanner.** { *; }

# WorkManager workers are created by name.
-keep class * extends androidx.work.ListenableWorker { <init>(android.content.Context, androidx.work.WorkerParameters); }

# Strip verbose logging from release builds (less on-device metadata).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# IPtProxy (Tor bridges: Lyrebird/obfs4, Snowflake) — gomobile bindings are called from native code.
-keep class IPtProxy.** { *; }
-keep class go.** { *; }
-dontwarn go.**
