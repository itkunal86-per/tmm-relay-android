# Keep OkHttp and JSON reflection-friendly members used by Android
-dontwarn okhttp3.**
-dontwarn org.conscrypt.**

# Keep Trimble Catalyst SDK classes
-keep class com.trimble.catalyst.** { *; }
-dontwarn com.trimble.catalyst.**

# Keep Trimble BlueBottle Protocol Buffers classes (required for subscription management)
-keep class com.trimble.bluebottle.** { *; }
-keep class com.trimble.bluebottle.remoteapi.protocolbuffers.** { *; }
-dontwarn com.trimble.bluebottle.**

# Keep Protocol Buffers classes
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**
