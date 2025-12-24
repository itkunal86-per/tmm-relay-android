# Keep OkHttp and JSON reflection-friendly members used by Android
-dontwarn okhttp3.**
-dontwarn org.conscrypt.**

# Keep Trimble Catalyst SDK classes
-keep class com.trimble.catalyst.** { *; }
-dontwarn com.trimble.catalyst.**
