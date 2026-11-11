# Car Surface Lab v0.2 — independent diagnostic build

Experimental Android Auto surface test: **Xiaomi 15 / Android 16 / Geely EX5 EM-i (USB)**.

### Cross-source review

- Fermata's main car app uses its legacy `CarActivityService`/projection path and works for video on this user's car in a restricted 2/3 layout.
- AnyAuto uses an AndroidX `NavigationTemplate` drawing `Surface`, with `minCarApiLevel=5`, `Action.PAN`, stable/visible area handling and a Shizuku-controlled virtual display.
- Our app uses the template surface directly with a local `MediaPlayer`. A virtual display is not needed to play one chosen MP4, but this approach is **not yet verified on the car**.

Fixes from v0.1:
- Include AndroidX `app-projected:1.7.0`, the official Android Auto projected dependency.
- Declare min Car App API 5 at application level, consistent with surface click callbacks.
- Add `Action.PAN` for gesture compatibility.
- Track surface, visible and stable rectangles; draw diagnostic borders.
- Stop the player and release callbacks at lifecycle teardown.
- Show last Surface/MediaPlayer status in the phone app; it can be copied without Termux or ADB.
- Separate application ID `dev.geely.carsurfacelab.v02` because v0.1's ephemeral GitHub Actions debug key cannot sign updates. **Do not uninstall v0.1 or Fermata.**

### Build

Pull request CI generates an experimental debug-signed APK in `CarSurfaceLab-debug-apk` artifact.

### Parking-only test sequence

1. Install this APK with the same trusted installation workflow used for other Android Auto test apps. It installs alongside v0.1.
2. Open the phone app, leave **test pattern** selected. In Android Auto's phone settings, enable `Car Surface Lab v0.2` if listed.
3. On the parked car, connect Xiaomi by USB, start Car Surface Lab v0.2 and inspect the grid. Green outline = full Surface; yellow = stable area; cyan = visible area.
4. After reconnecting phone UI, open Car Surface Lab v0.2 and copy the diagnostic block. Report dimensions, blank screen or host rejection.
5. Only after successful pattern rendering, choose a local MP4 and test sound and decoding.

No assumptions about display safety while the vehicle moves; this is experimental use of a navigation template for non-navigation content. It is not a supported Google Play in-car video category and may be blocked by the host.
