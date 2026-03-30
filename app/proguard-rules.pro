# Xposed module ProGuard rules

# Keep the main module class
-keep class io.github.lostmymind.ncm.car.notify.ModuleMain { *; }

# Keep all inner Hooker classes
-keep class io.github.lostmymind.ncm.car.notify.ModuleMain$* { *; }

# Keep libxposed API classes
-keep class io.github.libxposed.api.** { *; }

# Keep Android MediaSession related classes (for reflection)
-keep class android.media.session.** { *; }
-keep class android.media.MediaMetadata { *; }
-keep class android.media.session.PlaybackState { *; }
-keep class android.media.session.MediaSession { *; }
-keep class android.media.session.MediaController { *; }

# Keep BroadcastReceiver
-keep class * extends android.content.BroadcastReceiver { *; }

# Keep all classes with @XposedHooker annotation
-keep @io.github.libxposed.api.annotations.XposedHooker class * { *; }
