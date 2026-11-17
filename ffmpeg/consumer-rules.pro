# DefaultRenderersFactory creates the FFmpeg renderer by class name.
-keep class androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer { <init>(...); }
# Called from ffmpeg_jni.cc.
-keepclasseswithmembernames class androidx.media3.decoder.ffmpeg.** { native <methods>; }
