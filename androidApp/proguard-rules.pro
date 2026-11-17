# R8 rules for release, benchmark and share builds (shrinking, optimisation and
# obfuscation are on; see buildTypes in build.gradle.kts). The defaults come from
# proguard-android-optimize.txt; library rules (AndroidX, Compose, kotlinx
# serialization, ML Kit, CameraX) come with the libraries.

# --- Rust engine bridge ------------------------------------------------------
# The UniFFI bindings call the Rust engine through JNA, and the JNA AAR ships no
# consumer rules. libjnidispatch.so finds JNA classes by name, and JNA matches
# Structure fields by name through reflection, so R8 must not rename either.
# ("extends" also covers classes implementing JNA interfaces, such as callbacks.)
-keep class com.sun.jna.* { *; }
-keep class * extends com.sun.jna.* { *; }
# Desktop-only references in JNA.
-dontwarn java.awt.**

# --- Crash reports -----------------------------------------------------------
# Keep line numbers so a crash report a user chooses to send can be traced back
# with this build's mapping file (build/outputs/mapping/<type>/mapping.txt; keep
# it for every version you release). File names are replaced by "SourceFile",
# so the report shows no source layout, and class names stay obfuscated.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- Logging -----------------------------------------------------------------
# Release builds print nothing below warnings: R8 removes these calls entirely,
# with their arguments. Warnings and errors stay for the system log.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static boolean isLoggable(java.lang.String, int);
}

# --- Hardening ---------------------------------------------------------------
# Move all renamed classes into one package: smaller DEX and less structure left
# for anyone decompiling the APK. Kept classes (JNA, Android entry points) keep
# their names and packages.
-repackageclasses ''
-allowaccessmodification

# Optional diagnostics backend is instantiated by manifest metadata via reflection.
-keep class com.ezfiles.android.ConsentedCrashBackendFactory { public <init>(); public *; }

# sherpa-onnx (Moonshine speech): its JNI reads these classes and fields by name.
-keep class com.k2fsa.sherpa.onnx.** { *; }
