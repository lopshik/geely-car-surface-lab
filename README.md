# Car Surface Lab v0.1

Experimental, independent Android Auto display-surface project for **Geely EX5 EM-i + Xiaomi 15**.

**Status:** unverified prototype. A successful APK build does not prove it works on the car display.

- Select a local MP4 on the phone using the system file picker.
- The Android Auto service requests a navigation drawing surface.
- The service draws a static test pattern or attempts local MP4 rendering with Android MediaPlayer.
- No root, Shizuku, Wi-Fi, server or vehicle firmware changes.
- This is not a Google-supported video/navigation use of the navigation template; Android Auto may hide the app or reject the content.
- Only test while parked. No speed-interlock modifications are included.

## GitHub Actions

Pull requests to main run `Build Android APK`. Download `CarSurfaceLab-debug-apk` from the successful run's **Artifacts** section. It contains `app-debug.apk` (debug-signed, not intended for production).

1. Install APK on Xiaomi 15.
2. In its phone app choose a local MP4, or leave blank for test pattern.
3. If Android Auto lists the app, open it over USB while parked and check surface dimensions, video and controls.
4. Keep working Fermata/AnyAuto installed; this app is a separate experiment.

Reports of `not listed`, `blank screen`, or `surface works but MP4 doesn't` are valuable negative results, not build failures.
