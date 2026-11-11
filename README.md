# Car Surface Lab v0.3 — audio routing diagnostic

**Verified on Xiaomi 15 + Geely EX5 EM-i (11 Nov 2026):** v0.2 from KingInstaller was listed in Android Auto, rendered a grid and an MP4 on the vehicle screen. The projected `Surface` was 1802×1052, with stable/visible rectangles [14,100][1788,1038]. **Audio was absent.** This is user-observed, not a CI result.

**Hypothesis:** the v0.2 MediaPlayer lived in the CarAppService, with no foreground media service, media session or Audio Focus request. Android 15+ targeting API 35 rejects audio focus requests unless the app is topmost or runs a foreground service (Android Developers). Neither the root cause nor the vehicle's audio route is established yet.

## v0.3 changes

- New app ID `dev.geely.carsurfacelab.v03`: installs alongside v0.1 and v0.2; avoids debug signing key mismatch.
- User-started **foreground media playback service**, with platform `MediaSession`, notification, Audio Focus request and explicit audio attributes.
- If the service is running, it owns MediaPlayer and renders to the existing car Surface. If it is not running, the working v0.2 video path is retained.
- The phone app reports Audio Focus `GRANTED/FAILED`, volume, audio track count, audio session and best-effort routed audio device. It contains a button to copy this diagnostic block.
- The original visual Surface code is preserved; no changes to Fermata, AnyAuto or the Geely head unit.

## Testing while parked

1. Install v0.3 using the working KingInstaller method; check it is listed in Android Auto.
2. Choose an MP4 with audible audio on the phone. Press **Включить аудиосервис** in the v0.3 phone app **before connecting to the car** (one notification will appear). Keep the same video file for comparison.
3. Connect over USB, open v0.3 on the car screen. See whether sound plays; raise **media** volume in the car. Do not play other media apps concurrently.
4. After stopping playback return to the v0.3 phone app; press **Копировать диагностику** and share text. Audio focus and routed device are hypotheses to diagnose, not proof of where hardware emitted sound.
5. Stop foreground service with **Выключить аудиосервис** in phone app after testing. On restart this service must be enabled again manually.

If no service was started, the app intentionally falls back to the old v0.2 video-only path. No root/Shizuku/runtime privileges needed. The app uses a navigation surface for a non-navigation experiment and is not an officially supported Android Auto video app. Not intended for driver viewing.
