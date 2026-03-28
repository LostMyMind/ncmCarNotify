# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep Xposed hook class
-keep class com.example.neteasemedianotification.MediaNotificationHook { *; }
-keep class de.robv.android.xposed.** { *; }

# Keep Android components
-keep class com.example.neteasemedianotification.MainActivity { *; }