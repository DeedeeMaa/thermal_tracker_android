# 海康 HCNetSDK 相关
-keep class com.hikvision.** { *; }
-keep class com.hik.sdk.** { *; }
-keep class com.hcnetsdk.** { *; }

# JNA（海康安卓 SDK 依赖 JNA 加载 so）
-keep class com.sun.jna.** { *; }
-dontwarn com.sun.jna.**

# OpenCV
-keep class org.opencv.** { *; }

# Media3
-dontwarn androidx.media3.**
