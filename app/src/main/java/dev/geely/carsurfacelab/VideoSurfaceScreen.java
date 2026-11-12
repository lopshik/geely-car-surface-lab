package dev.geely.carsurfacelab;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.car.app.AppManager;
import androidx.car.app.CarToast;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.SurfaceCallback;
import androidx.car.app.SurfaceContainer;
import androidx.car.app.model.Action;
import androidx.car.app.model.ActionStrip;
import androidx.car.app.model.CarIcon;
import androidx.core.graphics.drawable.IconCompat;
import androidx.car.app.model.Template;
import androidx.car.app.navigation.model.MessageInfo;
import androidx.car.app.navigation.model.NavigationTemplate;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import java.util.Date;

/** v0.5: do NOT Canvas-lock Android Auto's decoder output Surface for idle graphics. */
public final class VideoSurfaceScreen extends Screen implements SurfaceCallback {
    private static final String TAG = "CSL05.Screen";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Rect stable = new Rect(), visible = new Rect();
    private Surface surface;
    private MediaPlayer player;
    private boolean servicePlayback, videoMode, closed, screenStarted;
    private boolean controlsVisible;
    private static final long CONTROLS_HIDE_MS = 12000L;
    private final Runnable hideControls = () -> {
        if (controlsVisible && !closed) {
            controlsVisible = false;
            record("Car playback controls hidden");
            invalidate();
        }
    };
    private final Runnable refreshControls = new Runnable() {
        @Override public void run() {
            if (!controlsVisible || closed || !screenStarted) return;
            invalidate();
            handler.postDelayed(this, 4000L);
        }
    };
    private int width, height, sourceGeneration;
    private String errorMessage;

    private final SharedPreferences.OnSharedPreferenceChangeListener selectionListener = (p, key) -> {
        if ("video_uri".equals(key)) {
            handler.post(() -> {
                if (closed) return;
                record("video_uri changed; Surface " + (surface != null && surface.isValid()));
                if (screenStarted && surface != null && surface.isValid()) startSelectedSource();
            });
        } else if ("audio_service_ready".equals(key)) {
            handler.post(() -> {
                if (closed || !screenStarted || surface == null || !surface.isValid() || servicePlayback) return;
                if (p.getString("video_uri", null) == null) return;
                if (PlaybackForegroundService.active() != null) {
                    record("Foreground audio service became ready");
                    startSelectedSource();
                }
            });
        }
    };

    VideoSurfaceScreen(@NonNull CarContext c) {
        super(c);
        c.getCarService(AppManager.class).setSurfaceCallback(this);
        prefs().registerOnSharedPreferenceChangeListener(selectionListener);
        record("CarAppService screen opened");
        getLifecycle().addObserver(new DefaultLifecycleObserver() {
            @Override public void onStart(@NonNull LifecycleOwner owner) {
                screenStarted = true;
                handler.post(() -> {
                    if (!closed && surface != null && surface.isValid()
                            && prefs().getString("video_uri", null) != null) {
                        record("Car screen resumed: restore selected movie");
                        startSelectedSource();
                    }
                });
            }
            @Override public void onStop(@NonNull LifecycleOwner owner) {
                screenStarted = false;
                controlsVisible = false;
                handler.removeCallbacks(hideControls);
                handler.removeCallbacks(refreshControls);
                PlaybackForegroundService service = PlaybackForegroundService.active();
                if (servicePlayback && service != null) {
                    service.suspendForCarUi("Car screen stopped / OEM camera or library");
                }
                releasePlayer();
                record("Screen stopped; playback position saved");
            }
            @Override public void onDestroy(@NonNull LifecycleOwner owner) {
                closed = true;
                sourceGeneration++;
                prefs().unregisterOnSharedPreferenceChangeListener(selectionListener);
                handler.removeCallbacksAndMessages(null);
                releasePlayer();
                surface = null;
                prefs().edit().putBoolean("surface_connected", false).apply();
                c.getCarService(AppManager.class).setSurfaceCallback(null);
                record("Screen destroyed");
            }
        });
    }

    private SharedPreferences prefs() {
        return getCarContext().getSharedPreferences("lab", Context.MODE_PRIVATE);
    }

    /** All playback controls are supplied by the Android Auto host. */
    private CarIcon carIcon(int resource) {
        return new CarIcon.Builder(
                IconCompat.createWithResource(getCarContext(), resource)).build();
    }

    private static String speedText(float value) {
        if (value == 1f) return "1×";
        return String.format(java.util.Locale.US, "%s×", value);
    }

    @NonNull @Override public Template onGetTemplate() {
        ActionStrip.Builder header = new ActionStrip.Builder();
        header.addAction(new Action.Builder()
                .setTitle("Видеотека")
                .setOnClickListener(() -> {
                    controlsVisible = false;
                    handler.removeCallbacks(hideControls);
                    handler.removeCallbacks(refreshControls);
                    getScreenManager().push(new CarLibraryScreen(getCarContext()));
                }).build());

        if (controlsVisible && videoMode) {
            header.addAction(new Action.Builder()
                    .setIcon(carIcon(android.R.drawable.ic_media_ff))
                    .setOnClickListener(this::changeSpeedOnSurface).build());
            header.addAction(new Action.Builder()
                    .setIcon(carIcon(android.R.drawable.ic_menu_crop))
                    .setOnClickListener(this::changePictureOnSurface).build());
        }

        NavigationTemplate.Builder template = new NavigationTemplate.Builder()
                .setActionStrip(header.build());

        if (controlsVisible && videoMode) {
            // Three host-owned touch targets. No separate settings Screen:
            // the decoder and foreground service remain alive, and playback
            // does not stop merely because the user wants a different speed.
            ActionStrip strip = new ActionStrip.Builder()
                    .addAction(new Action.Builder()
                            .setIcon(carIcon(android.R.drawable.ic_media_rew))
                            .setOnClickListener(() -> seekOnSurface(-10000)).build())
                    .addAction(new Action.Builder()
                            .setIcon(carIcon(isPlaying() ?
                                    android.R.drawable.ic_media_pause :
                                    android.R.drawable.ic_media_play))
                            .setOnClickListener(() -> {
                                togglePlayback();
                                showControls("Car play/pause action");
                            }).build())
                    .addAction(new Action.Builder()
                            .setIcon(carIcon(android.R.drawable.ic_media_ff))
                            .setOnClickListener(() -> seekOnSurface(10000)).build())
                    .build();
            template.setMapActionStrip(strip);
            PlaybackForegroundService playback = PlaybackForegroundService.active();
            long position = playback == null ? 0 : playback.position();
            long duration = playback == null ? 0 : playback.duration();
            String time = MediaCatalog.time(position) + " / " +
                    (duration > 0 ? MediaCatalog.time(duration) : "--:--");
            String aspect = "fit".equals(prefs().getString("picture_mode","fill"))
                    ? "Целиком" : "Заполнить";
            String details = speedText(prefs().getFloat("playback_rate",1f))
                    + " • " + aspect + " • повторный тап: пауза/пуск";
            template.setNavigationInfo(new MessageInfo.Builder(time)
                    .setText(details).build());
        } else if (!videoMode || errorMessage != null) {
            String title = errorMessage != null ? "Ошибка воспроизведения" : "Выбери фильм";
            String description = errorMessage != null ? errorMessage :
                    "Открой «Видеотека» или выбери папку на телефоне.";
            template.setNavigationInfo(
                    new MessageInfo.Builder(title).setText(description).build());
        }
        return template.build();
    }

    private boolean isPlaying() {
        PlaybackForegroundService service = PlaybackForegroundService.active();
        if (servicePlayback && service != null) return service.playing();
        if (player != null) {
            try { return player.isPlaying(); }
            catch (RuntimeException ignored) {}
        }
        return false;
    }

    private void showControls(String reason) {
        controlsVisible = true;
        record(reason);
        handler.removeCallbacks(hideControls);
        handler.removeCallbacks(refreshControls);
        handler.postDelayed(hideControls, CONTROLS_HIDE_MS);
        handler.postDelayed(refreshControls, 4000L);
        invalidate();
    }

    private void changeSpeedOnSurface() {
        float[] rates = {0.75f, 1f, 1.25f, 1.5f, 2f};
        float existing = prefs().getFloat("playback_rate",1f);
        int index = 1;
        for (int i = 0; i < rates.length; i++) {
            if (Math.abs(rates[i]-existing) < 0.001f) { index = i; break; }
        }
        float next = rates[(index+1)%rates.length];
        PlaybackForegroundService service = PlaybackForegroundService.active();
        if (service != null) service.changeRate(next);
        else prefs().edit().putFloat("playback_rate", next).apply();
        CarToast.makeText(getCarContext(), "Скорость " + speedText(next),
                CarToast.LENGTH_SHORT).show();
        showControls("Speed changed to " + next + "x on Surface");
    }

    private void changePictureOnSurface() {
        String next = "fit".equals(prefs().getString("picture_mode","fill")) ?
                "fill" : "fit";
        PlaybackForegroundService service = PlaybackForegroundService.active();
        if (service != null) service.changePicture(next);
        else prefs().edit().putString("picture_mode",next).apply();
        CarToast.makeText(getCarContext(),
                "Кадр: " + ("fit".equals(next) ? "Целиком" : "Заполнить"),
                CarToast.LENGTH_SHORT).show();
        showControls("Picture mode changed to " + next + " on Surface");
    }

    private void seekOnSurface(long delta) {
        PlaybackForegroundService service = PlaybackForegroundService.active();
        if (service != null) {
            service.seekTo(service.position()+delta);
            showControls("Seek " + delta + "ms on Surface");
        } else {
            record("Seek requested without service player");
            showControls("No service player for seek");
        }
    }

    @Override public void onSurfaceAvailable(@NonNull SurfaceContainer s) {
        sourceGeneration++;
        releasePlayer();
        surface = s.getSurface();
        width = s.getWidth();
        height = s.getHeight();
        prefs().edit().putBoolean("surface_connected", surface != null && surface.isValid())
                .putInt("surface_width", width).putInt("surface_height", height).apply();
        stable.setEmpty();
        visible.setEmpty();
        if (surface == null || !surface.isValid()) {
            record("Surface invalid " + width + "x" + height);
            return;
        }
        record("Surface available " + width + "x" + height + " dpi=" + s.getDpi());
        startSelectedSource();
    }

    private void startSelectedSource() {
        if (closed || surface == null || !surface.isValid()) {
            record("Selection deferred: no usable Surface");
            return;
        }
        int generation = ++sourceGeneration;
        releasePlayer();
        String chosen = prefs().getString("video_uri", null);
        videoMode = chosen != null && !chosen.isEmpty();
        errorMessage = null;
        controlsVisible = false;
        handler.removeCallbacks(hideControls);
        handler.removeCallbacks(refreshControls);
        if (!videoMode) {
            record("Waiting for video: Surface untouched (NO Canvas.lockCanvas)");
            invalidate();
            return;
        }
        invalidate();
        PlaybackForegroundService service = PlaybackForegroundService.active();
        if (service != null) {
            servicePlayback = true;
            boolean interrupted = prefs().getBoolean("resume_interrupted", false);
            record("Start video through foreground service; resumePaused=" + interrupted);
            service.play(Uri.parse(chosen), surface, !interrupted);
            return;
        }
        // File picker is visible on the phone while it starts the audio FGS.
        // Allow its onCreate() to run before falling back, without disconnecting USB.
        if (prefs().getBoolean("await_audio_service", false)) {
            record("Audio service pending; awaiting initialization");
            handler.postDelayed(() -> {
                if (closed || generation != sourceGeneration || surface == null || !surface.isValid()) return;
                prefs().edit().putBoolean("await_audio_service", false).apply();
                record("Audio service not ready after 2s; fallback player");
                startSelectedSource();
            }, 2000);
            return;
        }
        servicePlayback = false;
        record("Foreground service unavailable; starting fallback MediaPlayer");
        playFallback(Uri.parse(chosen));
    }

    private void playFallback(Uri uri) {
        String step = "create";
        try {
            MediaPlayer mp = new MediaPlayer();
            player = mp;
            step = "audio attributes";
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build());
            step = "setDataSource";
            mp.setDataSource(getCarContext(), uri);
            step = "setSurface";
            mp.setSurface(surface);
            step = "setLooping";
            mp.setLooping(true);
            mp.setOnVideoSizeChangedListener((p, w, h) -> record("Fallback decoded " + w + "x" + h));
            mp.setOnPreparedListener(p -> {
                if (closed || player != p) return;
                String stage = "setVideoScalingMode";
                try {
                    p.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
                    stage = "start";
                    p.start();
                    record("Fallback playback STARTED");
                } catch (RuntimeException e) {
                    fail("fallback prepared: " + stage, e);
                }
            });
            mp.setOnErrorListener((p, what, extra) -> {
                errorMessage = "MediaPlayer async error " + what + "/" + extra;
                record(errorMessage);
                invalidate();
                return true;
            });
            step = "prepareAsync";
            mp.prepareAsync();
            record("Fallback preparing asynchronously");
        } catch (Exception e) {
            fail("fallback setup: " + step, e);
            releasePlayer();
            // No Canvas fallback: painting here reintroduces producer contention.
        }
    }

    private void fail(String stage, Throwable exception) {
        errorMessage = stage + ": " + exception.getClass().getSimpleName();
        record("FAILED at " + errorMessage);
        Log.e(TAG, "Failure at " + stage, exception);
        invalidate();
    }

    private void record(String event) {
        Log.i(TAG, event);
        SharedPreferences p = prefs();
        String report = "Last event: " + event + "\nDate: " + new Date()
                + "\nSurface: " + width + "x" + height
                + "\nVisible: " + visible.toShortString()
                + "\nStable: " + stable.toShortString()
                + "\nMode: " + (videoMode ? "video" : "waiting");
        String history = p.getString("screen_events", "") + new Date() + " | " + event + "\n";
        if (history.length() > 6000) history = history.substring(history.length() - 6000);
        p.edit().putString("diagnostics", report).putString("screen_events", history).apply();
    }

    @Override public void onVisibleAreaChanged(@NonNull Rect r) {
        visible.set(r); record("Visible area changed");
    }

    @Override public void onStableAreaChanged(@NonNull Rect r) {
        stable.set(r);
        prefs().edit().putInt("video_safe_left",r.left)
                .putInt("video_safe_top",r.top)
                .putInt("video_safe_right",r.right)
                .putInt("video_safe_bottom",r.bottom).apply();
        PlaybackForegroundService s = PlaybackForegroundService.active();
        if (s != null) s.updateVisibleVideoArea(r.left,r.top,r.right,r.bottom);
        record("Stable area changed");
    }

    @Override public void onClick(float x, float y) {
        if (!videoMode) {
            record("Touch on waiting screen");
            return;
        }
        if (!controlsVisible) {
            // First touch only reveals the host-rendered controls and time.
            // Critically: do not touch MediaPlayer or suspend playback here.
            showControls("First tap: show video controls");
        } else {
            // Second touch of the video (not a host button) toggles pause/play.
            togglePlayback();
            showControls("Second tap: toggle play/pause");
        }
    }

    private void togglePlayback() {
        if (servicePlayback) {
            PlaybackForegroundService svc = PlaybackForegroundService.active();
            if (svc != null) { svc.toggle(); return; }
            record("Play/Pause failed: audio service unavailable"); return;
        }
        if (player == null) { record("Play/Pause: no player"); return; }
        try {
            if (player.isPlaying()) { player.pause(); record("Fallback paused"); }
            else { player.start(); record("Fallback resumed"); }
        } catch (RuntimeException e) { fail("fallback play/pause", e); }
    }

    @Override public void onSurfaceDestroyed(@NonNull SurfaceContainer s) {
        sourceGeneration++;
        controlsVisible = false;
        handler.removeCallbacks(hideControls);
        handler.removeCallbacks(refreshControls);
        PlaybackForegroundService svc = PlaybackForegroundService.active();
        if (servicePlayback && svc != null) {
            svc.suspendForCarUi("Surface destroyed / OEM rear camera");
        }
        releasePlayer();
        surface = null;
        prefs().edit().putBoolean("surface_connected", false).apply();
        record("Surface destroyed; playback position retained");
    }

    private void releasePlayer() {
        if (servicePlayback) {
            PlaybackForegroundService svc = PlaybackForegroundService.active();
            if (svc != null) svc.stopPlayback();
        }
        servicePlayback = false;
        if (player != null) {
            try { player.release(); } catch (RuntimeException ignored) {}
            player = null;
        }
    }
}
