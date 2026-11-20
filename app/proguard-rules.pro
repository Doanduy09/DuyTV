# Proguard rules for DuyTV (Surgical Anti-Bloat: chỉ giữ các model JSON/Gson, cho phép R8 tối ưu & cắt bỏ code thừa)
-keep class com.duytv.app.data.*Dto { *; }
-keep class com.duytv.app.data.SourceConfig { *; }
-keep class com.duytv.app.data.SourcesContainer { *; }
-keep class com.duytv.app.data.RemoteConfig { *; }
-keep class com.duytv.app.data.MovieListResponse { *; }
-keep class com.duytv.app.data.MovieDetailResponse { *; }
-keep class com.duytv.app.data.CategoryItem { *; }
-keep class com.duytv.app.data.FootballMatch { *; }
-keep class com.duytv.app.data.FootballMatchGroup { *; }
-keep class com.duytv.app.data.MatchStreamItem { *; }
-keep class com.duytv.app.data.TvChannel { *; }
-keep class com.duytv.app.data.TvSportsData { *; }
-keep class com.duytv.app.data.HomeCacheData { *; }
-keep class com.duytv.app.data.WatchHistoryItem { *; }
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

# Media3 / ExoPlayer (AAR đã có sẵn consumer rules, chỉ tắt cảnh báo optional)
-dontwarn androidx.media3.**
