# Scoova Ride — ProGuard / R8 rules
#
# v1 ships with minification disabled (isMinifyEnabled=false), so these
# rules don't run yet. They exist so that flipping the switch later doesn't
# require a separate setup pass.

# Keep our public SDK surface — anything an integrator might reflect on.
-keep class com.scoova.navlayer.core.** { public *; }
-keep class com.scoova.navlayer.ui.** { public *; }
-keep class com.scoova.navlayer.scoova.** { public *; }
-keep class com.scoova.navlayer.google.** { public *; }

# Keep our app entry points.
-keep class com.scoova.ride.ScoovaRideApp { *; }
-keep class com.scoova.ride.RideActivity { *; }

# OkHttp / kotlinx-serialization are well-behaved with default rules;
# nothing extra needed.

# MapLibre uses reflection on a few internal classes.
-keep class org.maplibre.android.** { *; }
-dontwarn org.maplibre.android.**
