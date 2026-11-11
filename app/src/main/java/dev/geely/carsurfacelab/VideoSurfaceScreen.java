package dev.geely.carsurfacelab;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
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
import androidx.car.app.model.Template;
import androidx.car.app.navigation.model.NavigationTemplate;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import java.util.Date;

public class VideoSurfaceScreen extends Screen implements SurfaceCallback {
    private static final String TAG = "CarSurfaceLab";
    private MediaPlayer player;
    private boolean servicePlayback;
    private Surface surface;
    private int width, height;
    private final Rect stable = new Rect(), visible = new Rect();
    private boolean videoMode;
    private boolean closed;

    public VideoSurfaceScreen(@NonNull CarContext carContext) {
        super(carContext);
        carContext.getCarService(AppManager.class).setSurfaceCallback(this);
        saveStatus("CarAppService session opened");
        getLifecycle().addObserver(new DefaultLifecycleObserver() {
            @Override
            public void onDestroy(@NonNull LifecycleOwner owner) {
                closed = true;
                releasePlayer();
                surface = null;
                carContext.getCarService(AppManager.class).setSurfaceCallback(null);
                saveStatus("Screen destroyed");
            }
        });
    }

    @NonNull
    @Override
    public Template onGetTemplate() {
        // Keep the regular action: even if touch callbacks are not delivered on a head unit,
        // the user can still toggle playback with a host-rendered button.
        return new NavigationTemplate.Builder()
                .setActionStrip(new ActionStrip.Builder()
                        .addAction(new Action.Builder().setTitle("Play / Pause")
                                .setOnClickListener(this::togglePlayback).build())
                        .build())
                .setMapActionStrip(new ActionStrip.Builder().addAction(Action.PAN).build())
                .setPanModeListener(enabled -> Log.i(TAG, "Pan mode " + enabled))
                .build();
    }

    @Override
    public void onSurfaceAvailable(@NonNull SurfaceContainer container) {
        releasePlayer();
        surface = container.getSurface();
        width = container.getWidth();
        height = container.getHeight();
        stable.setEmpty();
        visible.setEmpty();
        if (surface == null || !surface.isValid()) {
            saveStatus("Surface unavailable or invalid: " + width + "x" + height);
            return;
        }
        saveStatus("Surface available: " + width + "x" + height + " dpi=" + container.getDpi());
        String video = prefs().getString("video_uri", null);
        videoMode = video != null;
        if (!videoMode) drawTestPattern();
        else {
            PlaybackForegroundService service = PlaybackForegroundService.active();
            if (service != null) {
                servicePlayback = true;
                saveStatus("Foreground service: using audio focus player");
                service.play(Uri.parse(video), surface);
            } else {
                servicePlayback = false;
                prefs().edit().putString("audio_diagnostics",
                        "Foreground service OFF; video plays with v0.2 fallback (sound not guaranteed)").apply();
                playVideo(Uri.parse(video));
            }
        }
    }

    private SharedPreferences prefs() {
        return getCarContext().getSharedPreferences("lab", Context.MODE_PRIVATE);
    }

    private void saveStatus(String event) {
        Log.i(TAG, event);
        String report = "Last event: " + event + "\nDate: " + new Date()
                + "\nSurface: " + width + "x" + height
                + "\nVisible: " + visible.toShortString()
                + "\nStable: " + stable.toShortString()
                + "\nMode: " + (videoMode ? "video" : "test pattern");
        prefs().edit().putString("diagnostics", report).apply();
    }

    private void playVideo(Uri uri) {
        try {
            MediaPlayer mp = new MediaPlayer();
            player = mp;
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build());
            mp.setDataSource(getCarContext(), uri);
            mp.setSurface(surface);
            mp.setLooping(true);
            mp.setOnVideoSizeChangedListener((p, w, h) ->
                    saveStatus("Video decoded: " + w + "x" + h));
            mp.setOnPreparedListener(p -> {
                if (closed || player != p) return;
                try {
                    p.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT);
                    p.start();
                    saveStatus("Video playback started");
                } catch (RuntimeException e) {
                    saveStatus("Video start error: " + e.getClass().getSimpleName());
                }
            });
            mp.setOnErrorListener((p, what, extra) -> {
                saveStatus("MediaPlayer error: " + what + "/" + extra);
                return true;
            });
            mp.prepareAsync();
            saveStatus("Video preparing");
        } catch (Exception e) {
            saveStatus("Video load failed: " + e.getClass().getSimpleName());
            releasePlayer();
            videoMode = false;
            drawTestPattern();
        }
    }

    private void drawTestPattern() {
        if (surface == null || !surface.isValid()) return;
        Canvas c = null;
        try {
            c = surface.lockCanvas(null);
            int w = c.getWidth(), h = c.getHeight();
            c.drawColor(Color.rgb(10, 19, 32));
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStrokeWidth(2f);
            p.setColor(Color.rgb(30, 72, 110));
            for (int x = 0; x < w; x += Math.max(60, w / 12)) c.drawLine(x, 0, x, h, p);
            for (int y = 0; y < h; y += Math.max(60, h / 8)) c.drawLine(0, y, w, y, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(7f);
            p.setColor(Color.GREEN);
            c.drawRect(4, 4, w - 4, h - 4, p);
            if (!stable.isEmpty()) {
                p.setColor(Color.YELLOW);
                c.drawRect(stable, p);
            }
            if (!visible.isEmpty()) {
                p.setColor(Color.CYAN);
                c.drawRect(visible, p);
            }
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.WHITE);
            p.setTextSize(Math.max(26, Math.min(w / 22f, h / 9f)));
            c.drawText("CAR SURFACE LAB v0.2", 22, Math.max(56, h / 2f), p);
            p.setTextSize(Math.max(20, Math.min(w / 35f, h / 15f)));
            c.drawText("Canvas " + w + " x " + h + " | no Shizuku", 22,
                    Math.max(95, h / 2f + 55), p);
            saveStatus("Test pattern drawn: " + w + "x" + h);
        } catch (Exception e) {
            saveStatus("Canvas error: " + e.getClass().getSimpleName());
            Log.e(TAG, "Canvas rendering failed", e);
        } finally {
            if (c != null) {
                try { surface.unlockCanvasAndPost(c); }
                catch (RuntimeException e) { saveStatus("Canvas post failed"); }
            }
        }
    }

    @Override
    public void onClick(float x, float y) {
        if (videoMode) togglePlayback();
        else saveStatus("Touch received: " + (int)x + "," + (int)y);
    }

    private void togglePlayback() {
        if (player == null) {
            saveStatus("Play/Pause: no player");
            return;
        }
        try {
            if (player.isPlaying()) {
                player.pause();
                saveStatus("Video paused");
            } else {
                player.start();
                saveStatus("Video resumed");
            }
        } catch (IllegalStateException e) {
            saveStatus("Play/Pause: player not ready");
        }
    }

    @Override
    public void onVisibleAreaChanged(@NonNull Rect visibleArea) {
        visible.set(visibleArea);
        saveStatus("Visible area changed");
        if (!videoMode) drawTestPattern();
    }

    @Override
    public void onStableAreaChanged(@NonNull Rect stableArea) {
        stable.set(stableArea);
        saveStatus("Stable area changed");
        if (!videoMode) drawTestPattern();
    }

    @Override
    public void onSurfaceDestroyed(@NonNull SurfaceContainer container) {
        releasePlayer();
        surface = null;
        saveStatus("Surface destroyed");
    }

    private void releasePlayer() {
        if (servicePlayback && PlaybackForegroundService.active() != null) {
            PlaybackForegroundService.active().stopPlayback();
        }
        servicePlayback = false;
        if (player != null) {
            try { player.release(); } catch (RuntimeException ignored) {}
            player = null;
        }
    }
}
