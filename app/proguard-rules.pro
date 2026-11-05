# Proguard rules for DuyTV
-keep class com.duytv.app.data.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
