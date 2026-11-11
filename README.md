# CarVideo 0.6 Preview — Geely EX5 EM-i + Xiaomi 15

**Goal:** Deliver a polished, functional offline movie library on **both phone and Geely Android Auto head unit**, retain the proven car Surface/audio projection, and fix movie restart from 00:00 after OEM reverse-camera override.

## Verified baseline vs new features

**Confirmed on real Geely (November 11, 2026):** CSL 05 commit `5df680737f966d7c8a5f1944ddf8e927743ffc7c` displayed local 1920×1080 video, with audio from Geely speakers via `remote-submix` (type 25), granted audio focus, and play/pause. File selection while USB was connected worked. The car provided a Surface 1802×1052. Working baseline preserved at branch `archive/verified-csl05-20261111`.

**NEW / UNVERIFIED ON CAR:** CarVideo 06. Successful CI compilation, if any, must not be interpreted as live proof.

Features:
- Dark, readable, native Android phone UI with responsive transport controls, seek, video browser, local video preview, and copyable diagnostics.
- Opt-in MediaStore scan via Android 13–16 video permissions (partial video access supported). Files selected through SAF persist and appear even without media-library permission.
- Cached movie library, attractive in-memory poster tiles, paged GridTemplate on Geely; car-screen selection no longer requires picking individual files on Xiaomi after the library was scanned.
- The proven foreground-service MediaPlayer path with a single selected movie and vehicle audio focus, no persistent Shizuku or root.
- Per-movie URI-keyed position bookmarks (on pause, 3s while playing, stop, interrupted Surface, service death). Following disappearance of the AA Surface (including camera override), save the position and release decoder. On return, restore the same movie at saved timestamp **paused**, requiring deliberate Play; never suppress or overlay OEM camera.
- Distinct local phone-only player with position storage. Avoid simultaneous phone/car output.
- Short car application label **CarVideo 06**.
- Separate preview package `dev.geely.carvideo.preview06`; does **not** replace stable CSL 05 or rely on a new production signing key.

### First-time setup on phone
1. Install through the same working KingInstaller method used for the verified lab builds.
2. Open CarVideo 06; press **Найти видео** and allow access to videos. Android 14+ may allow only selected items; the list will reflect that permission. Alternatively press **+ MP4** to add individual files without broad access.
3. The movie tiles should appear in the phone library and be available on the car.

### In-car parked-only acceptance
1. Park safely. Connect USB and open CarVideo 06. Press **Видеотека** on car screen.
2. Select movie from Geely (without touching phone). Verify car screen returns to video and sound plays via Geely speakers. Background FGS start may be blocked by Android 16; if the diagnostic says so, start **Звук для ГУ** from the phone once.
3. Press Pause and note timestamp, resume; test seek and phone controls.
4. With video on the car, engage reverse with vehicle stopped: the OEM rear camera **must** take priority. Exit reverse and return to CarVideo. Player should preserve the timestamp and remain paused until Play is pressed; do not attempt video while vehicle moves.
5. Test USB disconnect/reconnect, a second file, switching car library and back, then copy technical report.
6. Run longer tests on battery/thermal use and CI/release signing only after in-car acceptance.

### Limitations
- This is an experimental usage of AndroidX Car App Library NAVIGATION Surface to project video; **not officially supported as an Android Auto video-app class**, so future Android Auto updates may alter eligibility or host UI controls.
- Android Auto controls the template appearance and may limit poster grid size and interaction. Car catalog appearance/functionality must be verified on the actual host.
- Reverse-gear/car speed is not exposed to this app; it responds to host lifecycle/Surface events, does not read gear and cannot guarantee an auto-resume at a safe speed. Paused restore is intentional safety.
- Debug APKs use temporary GitHub CI signing keys; only a stable private signing key + release pipeline will support seamless future updates. Never commit that key to this public repository.

Official guidance:
- AndroidX Surface lifecycle: https://developer.android.com/training/cars/apps/library/draw-maps
- Template navigation limits: https://developer.android.com/training/cars/apps/library/template-restrictions
- MediaStore partial access: https://developer.android.com/about/versions/14/changes/partial-photo-video-access
- Media playback resume: https://developer.android.com/media/media3/session/background-playback
