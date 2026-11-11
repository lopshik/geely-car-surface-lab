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

    @NonNull @Override public Template onGetTemplate() {
        NavigationTemplate.Builder b = new NavigationTemplate.Builder()
                .setActionStrip(new ActionStrip.Builder()
                        .addAction(new Action.Builder().setTitle("Видеотека")
                                .setOnClickListener(() -> getScreenManager().push(
                                        new CarLibraryScreen(getCarContext())))
                                .build())
                        .addAction(new Action.Builder()
                                .setIcon(new CarIcon.Builder(IconCompat.createWithResource(
                                        getCarContext(), android.R.drawable.ic_media_play)).build())
                                .setOnClickListener(this::togglePlayback).build())
                        .addAction(new Action.Builder()
                                .setIcon(new CarIcon.Builder(IconCompat.createWithResource(
                                        getCarContext(), android.R.drawable.ic_menu_manage)).build())
                                .setOnClickListener(() -> getScreenManager().push(
                                        new PlaybackOptionsScreen(getCarContext())))
                                .build())
                        .build())
                .setMapActionStrip(new ActionStrip.Builder().addAction(Action.PAN).build())
                .setPanModeListener(on -> Log.i(TAG, "Pan: " + on));
        // MessageInfo is rendered by the Android Auto host, not by lockCanvas().
        // Prevents mixing a CPU Canvas producer with a MediaPlayer video producer.
        if (!videoMode || errorMessage != null) {
            String title = errorMessage != null ? "Playback error" : "Выбери видео на Xiaomi";
            String description = errorMessage != null ? errorMessage
                    : "Открой «Видеотека» или выбери папку на телефоне.";
            b.setNavigationInfo(new MessageInfo.Builder(title).setText(description).build());
        }
        return b.build();
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
        if (videoMode) togglePlayback();
        else record("Touch on waiting screen");
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
