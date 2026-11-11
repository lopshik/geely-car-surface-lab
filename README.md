# Car Surface Lab v0.4 — live selection without reconnecting USB

User-observed on Geely EX5 EM-i and Xiaomi 15 (2026-11-11):
- v0.2: surface and MP4 video work, sound absent.
- v0.3: MP4 video and **audio from Geely speakers** work after unplugging/replugging USB, but changing from test grid to a video on the phone while Android Auto is already connected leaves the grid visible. The captured audio focus status was `not requested`; this is not proof that audio focus never was obtained.

## Root-cause in v0.3
`VideoSurfaceScreen` reads the `video_uri` preference **only** in `onSurfaceAvailable()`. Selecting MP4 via the phone's file picker saves the preference, but does not restart the player when the surface is already alive. Replugging recreates the Surface and triggers that code.

## v0.4 fix
- Register a `SharedPreferences.OnSharedPreferenceChangeListener` scoped to the CarAppService screen lifecycle.
- If `video_uri` changes while the Surface is valid, release the previous player and start the new selected file using the **existing** Surface. If no usable Surface exists, the selection is retained until the next `onSurfaceAvailable`.
- Preserve fallback to v0.3's MediaPlayer if the foreground audio service is not active.
- Keep capped event histories of video surface changes and Audio Focus/route events for post-test inspection on the phone.
- No Geely firmware changes. No dependency on Shizuku during playback.

## Test while parked
1. Install with KingInstaller (v0.4 is a separately signed debug package, `dev.geely.carsurfacelab.v04`, and does not replace v0.3).
2. Choose **test grid** on the phone and enable **audio service**.
3. Connect by USB and open Car Surface Lab v0.4 in Android Auto. The grid should appear.
4. **Without unplugging USB**, open the phone app and select an MP4 with sound.
5. Return to the car view. **Expected:** grid is replaced by MP4, Geely speakers play the soundtrack.
6. Open the phone app, press **Copy diagnostics**; it now includes an audio event history and a surface event history.
7. Optional: press **test grid** again; the video should stop and the grid reappear, still without USB reconnection.

This app is an experimental use of Android Auto's navigation surface; it is not an officially supported video app and must be tested while parked.
