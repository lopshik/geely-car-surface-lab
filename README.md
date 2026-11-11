# CSL 05 — focused diagnostic for Android Auto Surface transition

Verified on Geely EX5 EM-i + Xiaomi 15, 2026-11-11:
- v0.3: video + audio from Geely speakers after USB reconnect.
- v0.4: connected Surface 1802×1052 and shared preference callback works at 16:38:32, but direct fallback player setup threw IllegalStateException. Audio service was not active when fallback was attempted; a separate service start was logged at 16:38:38. At 16:38:36 user cleared the selected video URI, explaining why reconnect showed only grid.
- The exact API method causing the exception is not in the v0.4 log.

Changes:
1. Do not draw idle grid with Surface.lockCanvas. Show Android Auto host's MessageInfo waiting UI instead, keeping the video Surface untouched until MediaPlayer attaches. This tests the CPU Canvas/MediaPlayer producer contention hypothesis without claiming it is proven.
2. On file selection from visible phone activity, start mediaPlayback foreground service, record the selected URI, and let Android Auto screen react immediately. Wait for service-ready preference signal or use a time-bounded fallback. No USB reconnect required.
3. Persist method-stage diagnostics (setDataSource, setSurface, prepareAsync etc.) for both MediaPlayers, along with Audio Focus and service lifecycle events. Never catch-and-redraw Canvas on player failure.
4. Short launcher and car app service names: CSL 05. Isolated debug package: dev.geely.carsurfacelab.v05. Does not overwrite working v0.3 or v0.4.
5. Not a general availability or safety guarantee: this prototype uses the navigation surface for non-navigation media; test only on a stationary vehicle.

Test while parked:
- Install through KingInstaller and enable CSL 05 in Android Auto if necessary.
- On the phone tap "Экран ожидания (без видео)", start Android Auto over USB and open CSL 05 in the car. You should see a HOST message, not a colored grid.
- While USB remains connected, choose a local MP4 in the CSL 05 phone app. Audio service is started by the foreground file selection action, no separate tap needed.
- Check for video and audio in Geely. If unsuccessful, copy phone diagnostics including audio/surface event histories; the report will show exactly which API call failed. Do not unplug the USB before checking.
