# Add project specific ProGuard rules here.
# Keep the Accessibility service, Quick Settings tile, and shade-collapse launcher discoverable if R8 is enabled.
-keep class com.volumemanager.VolumeAccessibilityService { *; }
-keep class com.volumemanager.VolumeTileService { *; }
-keep class com.volumemanager.PanelLaunchActivity { *; }