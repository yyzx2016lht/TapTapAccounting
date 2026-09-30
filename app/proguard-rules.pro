# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# sherpa-onnx: JNI side may look up classes/methods by original names.
# Keep them to avoid NoClassDefFoundError in release builds.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepnames class com.k2fsa.sherpa.onnx.**
-keepclassmembers class com.k2fsa.sherpa.onnx.** {
    native <methods>;
}

# Backup/restore uses Gson + TypeToken to deserialize persisted JSON into
# Room entity models. Release builds enable R8, so keep the generic metadata
# that reflection-based parsers rely on to make debug/release behavior match.
#
# Signature is required for Gson TypeToken and Retrofit generic parsing.
# InnerClasses/EnclosingMethod are required so anonymous TypeToken subclasses
# and suspend API signatures can still be reconstructed correctly at runtime.
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, AnnotationDefault
-keepattributes *Annotation*

-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# Retrofit suspend APIs parse generic Continuation signatures via reflection.
# If these signatures are stripped in release, Retrofit can crash with:
# "java.lang.Class cannot be cast to java.lang.reflect.ParameterizedType".
-keep class kotlin.coroutines.Continuation
-keep,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

-keep class com.taostudio.tapaccounting.data.local.entity.** { *; }

# Built-in category/asset presets are deserialized from raw JSON with Gson in
# release builds. Keep field names stable so raw data loads exactly like debug.
-keep class com.taostudio.tapaccounting.BuiltInCategory { *; }

# Retrofit/Gson DTOs used by AI config, chat, and model testing.
-keep class com.taostudio.tapaccounting.SiliconFlowApi { *; }
-keep class com.taostudio.tapaccounting.ChatRequest { *; }
-keep class com.taostudio.tapaccounting.Message { *; }
-keep class com.taostudio.tapaccounting.MultimodalMessage { *; }
-keep class com.taostudio.tapaccounting.ContentPart { *; }
-keep class com.taostudio.tapaccounting.ImageUrl { *; }
-keep class com.taostudio.tapaccounting.MessageUnion { *; }
-keep class com.taostudio.tapaccounting.MessageUnion$* { *; }
-keep class com.taostudio.tapaccounting.MessageUnionSerializer { *; }
-keep class com.taostudio.tapaccounting.ResponseFormat { *; }
-keep class com.taostudio.tapaccounting.ChatResponse { *; }
-keep class com.taostudio.tapaccounting.Choice { *; }
-keep class com.taostudio.tapaccounting.AudioResponse { *; }
-keep class com.taostudio.tapaccounting.ModelsResponse { *; }
-keep class com.taostudio.tapaccounting.ModelItem { *; }

# Shizuku screenshot path uses reflection to call Shizuku.newProcess(...).
# R8 may strip/rename reflectively-accessed members in release, causing
# debug works but release returns empty screencap bytes or fails silently.
-keep class rikka.shizuku.Shizuku { *; }
-keep class rikka.shizuku.ShizukuRemoteProcess { *; }
-keep class com.taostudio.tapaccounting.ShizukuHelper { *; }
-keep class com.taostudio.tapaccounting.ShizukuShell { *; }

# TensorFlow Lite
-keep class org.tensorflow.lite.** { *; }
-keepclassmembers class org.tensorflow.lite.** { *; }
-keep class com.taostudio.tapaccounting.tap.** { *; }

# tflite-support 的 TensorAudio 用 AutoValue 生成 Builder，但 auto-value 只是
# 编译期注解处理器、不在运行时类路径上，R8 因此报 "Missing class AutoValue"。
# 仅存在于未调用的辅助类中，属编译期噪音，用 -dontwarn 抑制即可（官方推荐做法）。
-dontwarn com.google.auto.value.AutoValue
-dontwarn com.google.auto.value.AutoValue$Builder

