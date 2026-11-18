# Add project specific ProGuard rules here.
# Keep the boot receiver and service discoverable if R8 is ever enabled.
-keep class com.volumemanager.BootReceiver { *; }
-keep class com.volumemanager.OverlayVolumeService { *; }
