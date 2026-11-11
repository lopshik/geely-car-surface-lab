# CarVideo 0.7 Preview — Xiaomi 15 / Geely EX5 EM-i

## Known-good reference, do not break

CarVideo 0.6, commit \`12cb53753a6587db7ae23a7d0ca326896fcf0102\`:
- **owner verified on real Geely (2026-11-11):** movie selection directly from car grid, 1920x1080 video on host Surface 1802x1052, sound through Geely speakers (remote-submix type=25), pause on tap, resume from car library.
- **owner verified:** on reverse camera interruption, the app releases video/decoder and saves playback position. On return, it seeks to exactly the saved position **paused** until explicitly resumed. Example 26623ms and 35558ms were both restored.
- Archived immutable code branch: \`archive/verified-carvideo06-20261111\`.
- The car app uses an unsupported experimental navigation Surface for video; do not describe this as an officially approved Android Auto video application.

## 0.7 scope (not yet verified on Geely)

- **Phone:** search by filename/folder, one-tap favorites, a favorites-only filter, real small video thumbnails. UI remains dark and native, playback/seek preserved.
- **Geely:** keep tested grid/selection behavior; add **Favorites** and **Folders** categories, navigable folder list, pagination, real thumbnails from the phone-created cache. Selecting a file in a nested folder returns to the car player rather than leaving multiple stacked menus.
- **Catalog:** SAF manually selected MP4s remain in the library after a MediaStore rescan; no all-files permission. Caching is bounded to 250 catalog entries, at most 24 decoded bitmaps in memory, and JPEG thumbnails generated one at a time off the UI thread, on demand. Cache is stored under the app's disposable cache directory; no permanent background scanning.
- **Appearance:** short vehicle launcher label \`CV 07\`; native gradient poster fallback when a source video has no thumbnail.
- **Isolation:** new package \`dev.geely.carvideo.preview07\`, debug-signed CI build for KingInstaller. Do not uninstall working 0.6 until 0.7 passes on the real car.

### In-car acceptance checklist (parked only)

1. Install v0.7 using KingInstaller, start phone app, grant video library access, choose **Найти видео**. Some folders are visible only according to granted Android media permissions. Add selected videos via **+ MP4** if needed.
2. Confirm phone list contains thumbnails, type into **Поиск**, add 1–2 items to favorites using ☆/★ and check favorites filter.
3. Connect USB, launch **CV 07**, open **Видеотека** on Geely. Check first-page **★ Избранное**, **📁 Папки**, navigate to a file and select it *on the car*. Check video and sound.
4. Check touch pause, selection of a second movie, return to player from nested folder, reverse camera priority with resume paused at preserved timestamp. **If anything breaks, revert to v0.6.**
5. Copy diagnostic report if any failure. Source is a preview: no proven endurance/thermal measurements, release signature or update migration. Additional codecs/subtitles remain future work.

### Known constraints

- AndroidX GridTemplate and ListTemplate appearance, item capacity, and task depth are controlled by Android Auto. Folder and favorites layouts are experimental; do not expect unlimited template navigation.
- App draws no idle grid into the same video Surface, preventing the old v0.4 handoff issue.
- Foreground-service start from the car may be rejected by Android 16. The phone app allows explicit audio-service start when needed.
- Android Auto can change compatibility after updates; pin a known-good setup and keep a rollback.
- Final seamless release updating needs a persistent private signing key; no private key is checked into GitHub.

References: AndroidX 1.7.0 (stable), MediaStore loadThumbnail, Car App Library template limits.
