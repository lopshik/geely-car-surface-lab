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
