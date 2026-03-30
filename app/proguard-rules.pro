# Xposed module ProGuard rules

# Keep the main module class
-keep class io.github.lostmymind.ncm.car.notify.ModuleMain { *; }

# Keep Xposed interface (API 82)
-keep class de.robv.android.xposed.** { *; }
-keepclassmembers class * implements de.robv.android.xposed.IXposedHookLoadPackage {
    public void handleLoadPackage(de.robv.android.xposed.callbacks.XC_LoadPackage$LoadPackageParam);
}

# Keep all inner classes
-keep class io.github.lostmymind.ncm.car.notify.ModuleMain$* { *; }

# Keep Android MediaSession related classes
-keep class android.media.session.** { *; }
-keep class android.media.MediaMetadata { *; }
-keep class android.media.session.PlaybackState { *; }
-keep class android.media.session.MediaSession { *; }
-keep class android.media.session.MediaController { *; }

# Keep BroadcastReceiver
-keep class * extends android.content.BroadcastReceiver { *; }
