# CarVideo 0.8 Preview — focused player

## Verified reference
Owner-tested CarVideo 0.7.1 commit \`35274be50982d9a6123ddc19911be388405439f8\` on Xiaomi 15 + Geely EX5 EM-i:
- Decodes 1916×800 and 1920×1080 on projected Surface 1802×1052.
- Geely speaker audio via remote-submix and GRANTED audio focus; on-car selection, play/pause work.
- Per-file position resume: restored at 00:13.282 and continued roughly 21 min without logged playback errors.
- Reverse camera position restore separately verified in 0.6.
- Reference archived as \`archive/verified-carvideo071-20261111\` and older 0.6 archive.
**0.8 is not verified on Geely yet; CI success does not equal runtime acceptance.**

## Product direction (user requirements)
- Remove test/developer texts, device/USB branding, phone standalone player.
- One user-selected folder via \`ACTION_OPEN_DOCUMENT_TREE\` (persistable grant), then just **Обновить**; single nonrecursive folder enumeration via DocumentsContract, no broad media permission. Android 11+ forbids choosing the Download root; use an ordinary subfolder such as Movies/MyVideos.
- Small movie list on Xiaomi with local thumbnails, chosen file launches Geely playback on car; no favorites, search, MP4 picker, or nested menus.
- Car \`Видеотека\` remains direct; a new settings action opens a 2-row speed/image list.
- Speed: 0.75/1/1.25/1.5/2 via PlaybackParams with pause-aware behavior, preserving pitch when supported.
- Image: **Заполнить** is direct MediaPlayer SCALE_TO_FIT_WITH_CROPPING, aspect-preserving and efficient (default). **Целиком** routes decoder frames through a frame-driven external-OES EGL renderer, letterboxes while preserving source aspect. Aspect-preserving fit adds GPU cost and requires on-car testing; renderer may fall back to direct fill if EGL init fails.
- The fixed Android Auto Surface can still have OEM template cropping/strips. Embedded black bars in source media cannot be removed without cropping more of the picture.
- Diagnostics moved to separate native screen; its Copy and Share actions are pinned above the scrollable log so long movie lists don't impede collection.
- Rear camera and car host lifecycle retain priority, existing per-file resume with paused return is preserved.
- No root, permanently enabled Shizuku, head unit modification, moving-vehicle video guarantee or safety bypass.

## How to test on parked Geely
1. Install Preview 08 next to 0.7.1 through the same KingInstaller workflow. Confirm icon/title simply **CarVideo**.
2. Open phone app, choose video folder once and grant Android read access, then **Обновить**. Videos should appear (no + MP4, search, favorites, or phone playback test).
3. On Geely open **CarVideo** -> **Видеотека**, select movie with sound. Verify unchanged sound/selection.
4. Test settings icon on the car player: speed 1x→1.25x, then 2x, toggle **Заполнить** vs **Целиком** for one 1916×800 and one 1920×1080 movie. The first mode may crop outer parts while filling; the second should show entire frame with letterboxing and never stretch. Send diagnostics if FIT EGL fails or audio desynchronizes.
5. Check camera override and paused resume from correct timestamp. Separate **Лог** at the top of phone app copies report in one tap.
6. If regression, revert to archived v0.7.1. No measured long-run power/thermal results yet.

This preview build uses an ephemeral CI debug signing key and a new package \`dev.geely.carvideo.preview08\`. Final release requires a stable private signing key and validated update path; do not present this package as signed production release.

Primary Android references:
- MediaPlayer VIDEO_SCALING_MODE_SCALE_TO_FIT stretches rather than necessarily preserving aspect; SCALE_TO_FIT_WITH_CROPPING preserves aspect: https://developer.android.com/reference/android/media/MediaPlayer
- PlaybackParams speed change may start paused player: https://developer.android.com/reference/android/media/MediaPlayer#setPlaybackParams(android.media.PlaybackParams)
- One-folder SAF access and limitations: https://developer.android.com/training/data-storage/shared/documents-files
- DocumentsContract folder enumerator: https://developer.android.com/reference/android/provider/DocumentsContract

## 0.8.1 finishing fixes
- No Xiaomi name on the car waiting screen; generic "Выбери фильм".
- Audio FGS manual rescue button is tucked inside the separate diagnostic screen ("Лог" → "Звук для ГУ"). This is for Android 16 builds where a car-initiated foreground service start is rejected.
- Updated preview package ID to `dev.geely.carvideo.preview081`; code reference includes the optional FIT graphical renderer and safe-area behavior. Still requires actual Geely testing.

## CarVideo 0.8.2 — compact Xiaomi UI (requires device acceptance)
Based on owner's screenshot of 0.8.1:
- MainActivity and DiagnosticsActivity handle Android 15/16 status and gesture bar safe-area insets via WindowInsets; Log button is no longer positioned under battery icons.
- More compact title/header. Empty now-playing card hides transport and seek controls until movie selected; settings speed and aspect controls are behind a gear button.
- Compact 57dp thumbnail movie rows and inline folder/refresh actions allow more movies per screen.
- Titles are cleaned for display only (year preserved and release tags separated), without renaming any source files or changing content URIs.
- Real frame thumbnails from MediaMetadataRetriever when SAF provider doesn't return them. Off-main-thread worker and disk cache are retained.
- Geely library titles are cleaned; no changes to playback service, foreground audio, video Surface, rear camera or per-file bookmark handling.
- Preview package dev.geely.carvideo.preview082; short Android Auto label CV 082. Separate from 0.8.1 and verified 0.7.1.
- CI compilation and Android car acceptance are different milestones; do not call this production release before vehicle verification.

## CarVideo 0.8.3 — on-car controls and readable movie list

Based on owner's verified v0.8.2 car log (2026-11-12 07:31–07:39 GMT+03):
- 1920×1038 video FILL succeeded, audio focus GRANTED and routed to remote-submix, per-file bookmark restored at 16.589s, 89.710s, 123.743s.
- FIT EGL renderer was ready and decoded video, with 4m50s recorded until screen stopped; user reports playback works. Whether specific display modes have the intended aesthetic requires visual confirmation.
- Working v0.8.2 commit pinned in archive/verified-carvideo082-20261112 before these UI changes.

New 0.8.3:
- First touch anywhere on projected video only exposes controls; second touch on bare video toggles play/pause. The host draws controls so no Canvas conflicts with MediaPlayer.
- While exposed, top bar shows library and two quick actions to cycle speed and picture mode. Right action strip shows seek -10s, pause/play, seek +10s. Position, total time, speed and current fit/fill mode are provided as a host MessageInfo. After 12 seconds, controls auto-hide. No settings Screen is entered, so opening speed/size controls does not trigger screen onStop or unnecessary audio/video pause.
- NavigationTemplate refresh is supported by Car App Library, but exact positioning/visibility of host-owned controls varies by Android Auto head unit, and host auto-hides action strips after inactivity. This behavior requires in-car acceptance. Video Surface untouched.
- Library switches from GridTemplate to ListTemplate, no large thumbnails taking away name width. For long titles, first line + wrapping secondary text shows more of the film name; files remain sorted as before, no user search/favorites. Paging obeys host's CONTENT_LIMIT_TYPE_LIST, tested on Geely only after installation.
- All previously tested playback, audio focus, Surface lifecycle, reverse camera paused resume and one-folder phone library retained. No geely firmware changes. Only use video while the vehicle is parked.
- Install isolated Preview 083 via existing KingInstaller workflow. Previous 0.8.2 remains available; CI debug APK is not a production release.

## CarVideo 0.8.4 — icon clarity + seek-state bugfix

Owner supplied actual car log from v0.8.3 on 2026-11-12: two-stage tap worked, speed 1.25× worked, foreground audio focus GRANTED and remote-submix route, per-movie paused resume at 1475921ms and 1693667ms. The user is happy with the text-list video library but wants skip actions marked numerically “−10” and “+10”, with a distinct speed pictogram.

**Confirmed bug discovered in code:** MediaPlayer.OnSeekCompleteListener installed in onPrepared called finishPreparing on EVERY subsequent user seek, resulting in numerous duplicate “Playing” reports and repeated p.start()/applyRate(), potentially inadvertently resuming a paused film. Android reference explicitly documents the callback for each seekTo. v0.8.4 makes the initial restore seek listener one-shot and clears it before calling finishPreparing. Ordinary seek now stores requested timestamp rather than stale getCurrentPosition, and new skipBy() rate-limits held/repeating AA action events to one every 550ms, using a cumulative target.
 
AA icon changes: the two skip actions show −10 / +10 as high-contrast bitmap action icons; speed action uses a distinct “×” icon rather than the fast-forward arrow. Host may tint or resize custom icons; final legibility requires Geely test. All other controls and the ListTemplate movie catalog are preserved.

**Not claimed:** no image-scaling defect can be established from the user's supplied log alone. Visible [14,14][1788,1038] differs from stable [14,100][1788,1038] as Android Auto host UI changes, not necessarily erroneous aspect ratio. FIT renderer untouched.

Source archive of owner-verified v0.8.3 at archive/verified-carvideo083-20261112. Build and on-car tests are separate. New package dev.geely.carvideo.preview084, short label CV 084, does not replace v0.8.3.
