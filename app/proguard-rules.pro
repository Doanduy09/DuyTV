# Proguard rules for DuyTV
-keep class com.duytv.app.data.** { *; }
-keep class com.duytv.app.util.GeminiVoiceAction { *; }
-keep class com.duytv.app.util.VoiceContext { *; }
-keep class com.duytv.app.util.AppVersionInfo { *; }
-keep class com.duytv.app.service.DuyVoiceAccessibilityService { *; }

-keepattributes Signature, *Annotation*, EnclosingMethod, InnerClasses
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Gson & TypeToken
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Media3 / ExoPlayer
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**
