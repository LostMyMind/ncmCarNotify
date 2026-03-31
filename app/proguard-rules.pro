# Xposed module ProGuard rules

# Keep the main module class
-keep class io.github.lostmymind.ncm.car.notify.ModuleMain { *; }

# Keep all inner Hooker classes
-keep class io.github.lostmymind.ncm.car.notify.ModuleMain$* { *; }

# Keep libxposed API classes
-keep class io.github.libxposed.api.** { *; }

# Keep all classes with XposedHooker annotation
-keep @io.github.libxposed.api.annotations.XposedHooker class * { *; }
