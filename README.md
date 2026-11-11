# CarVideo 0.7.1 Preview — simplified small library

User explicitly declined favorites and search: typical on-phone video collection is small, and the primary requirement is selecting movies on either the Xiaomi 15 or Geely EX5 EM-i.

### Implemented
- **Direct car grid** — opening the library shows movies immediately. No favorites, search, or folder submenu. One paging tile only when the collection exceeds host limits. Same on-car file selection, audio focus, Surface video and camera-interruption restoration path as owner-tested v0.6.
- **Simple phone list** — no search box, favorites filter or star buttons. Two ways to build the small library: "Видео телефона" scans selected/media-permitted videos, "+ Файл" adds an individual video through SAF. Small film list is shown directly; "Показать остальные" appears only when more than 30 items exist.
- **Real video thumbnails with bounded cache** retained.
- **Removed favorites/filtering helpers** from catalog and disconnected folder browser; preserves manual SAF additions across MediaStore rescans.
- Short name in Android Auto: **CV 071**.
- Preview package `dev.geely.carvideo.preview071`. Uses CI debug signing, installs alongside verified CarVideo 0.6 and the earlier 0.7; no deletion is needed.

### Protected accepted baseline
Owner-confirmed CarVideo 0.6, commit `12cb53753a6587db7ae23a7d0ca326896fcf0102`, archived at `archive/verified-carvideo06-20261111`: selected movie plays on Geely with sound, touch pause works, camera has priority, and time resumes from saved position while paused. Do not change playback service or Surface lifecycle for this UI simplification.

### Car test while parked
1. Install with KingInstaller. Open phone app and add videos using "Видео телефона" or "+ Файл".
2. On Geely open "CV 071" → "Видеотека": should show video tiles immediately, without favorites or folders. Tap any movie and confirm sound/image.
3. Check pause, return after rear camera, and preserved timestamp. If any regression, CarVideo 0.6 is intact.
4. Next user feedback can guide polishing; this build does not yet claim to be a production signed release.
