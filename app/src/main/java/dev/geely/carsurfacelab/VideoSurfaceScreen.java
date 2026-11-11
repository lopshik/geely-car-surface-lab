package dev.geely.carsurfacelab;

import android.content.Context;
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
import androidx.car.app.model.Template;
import androidx.car.app.model.Action;
import androidx.car.app.model.ActionStrip;
import androidx.car.app.navigation.model.NavigationTemplate;

public class VideoSurfaceScreen extends Screen implements SurfaceCallback {
    private static final String TAG = "CarSurfaceLab";
    private MediaPlayer player;
    private Surface surface;

    public VideoSurfaceScreen(@NonNull CarContext carContext) {
        super(carContext);
        carContext.getCarService(AppManager.class).setSurfaceCallback(this);
    }

    @NonNull
    @Override
    public Template onGetTemplate() {
        return new NavigationTemplate.Builder()
                .setActionStrip(new ActionStrip.Builder()
                        .addAction(new Action.Builder()
                                .setTitle("Play / Pause")
                                .setOnClickListener(this::togglePlayback)
                                .build())
                        .build())
                .build();
    }

    @Override
    public void onSurfaceAvailable(@NonNull SurfaceContainer container) {
        releasePlayer();
        surface = container.getSurface();
        if (surface == null || !surface.isValid()) {
            Log.w(TAG, "Invalid Surface");
            return;
        }
        String video = getCarContext().getSharedPreferences("lab", Context.MODE_PRIVATE)
                .getString("video_uri", null);
        if (video == null) {
            drawTestPattern(container.getWidth(), container.getHeight());
        } else {
            playVideo(Uri.parse(video));
        }
    }

    private void playVideo(Uri uri) {
        try {
            MediaPlayer mp = new MediaPlayer();
            player = mp;
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build());
            mp.setDataSource(getCarContext(), uri);
            mp.setSurface(surface);
            mp.setLooping(true);
            mp.setOnPreparedListener(MediaPlayer::start);
            mp.setOnErrorListener((p, what, extra) -> {
                Log.e(TAG, "Player error " + what + "/" + extra);
                return false;
            });
            mp.prepareAsync();
        } catch (Exception e) {
            Log.e(TAG, "Video load failed", e);
            releasePlayer();
            drawTestPattern(1280, 720);
        }
    }

    private void drawTestPattern(int width, int height) {
        if (surface == null || !surface.isValid()) return;
        Canvas c = null;
        try {
            c = surface.lockCanvas(null);
            int w = c.getWidth(), h = c.getHeight();
            c.drawColor(Color.rgb(10, 19, 32));
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStrokeWidth(3f);
            p.setColor(Color.rgb(24, 70, 105));
            for (int x = 0; x < w; x += Math.max(50, w / 12)) c.drawLine(x, 0, x, h, p);
            for (int y = 0; y < h; y += Math.max(50, h / 7)) c.drawLine(0, y, w, y, p);
            p.setColor(Color.WHITE);
            p.setTextSize(Math.max(26, Math.min(w / 22f, h / 10f)));
            c.drawText("CAR SURFACE LAB", 35, Math.max(70, h / 2f), p);
            p.setTextSize(Math.max(20, Math.min(w / 38f, h / 16f)));
            c.drawText("Surface: " + w + " x " + h + "  |  no Shizuku", 35, Math.max(110, h / 2f + 65), p);
            p.setColor(Color.rgb(100, 220, 160));
            c.drawRect(10, 10, Math.max(12, w - 10), 20, p);
            Log.i(TAG, "Canvas surface=" + w + "x" + h + ", container=" + width + "x" + height);
        } catch (Exception e) {
            Log.e(TAG, "Canvas rendering failed", e);
        } finally {
            if (c != null) surface.unlockCanvasAndPost(c);
        }
    }

    @Override
    public void onClick(float x, float y) {
        togglePlayback();
    }

    private void togglePlayback() {
        if (player == null) return;
        try {
            if (player.isPlaying()) player.pause(); else player.start();
        } catch (IllegalStateException e) {
            Log.w(TAG, "Player not ready", e);
        }
    }

    @Override
    public void onVisibleAreaChanged(@NonNull Rect visibleArea) {
        Log.i(TAG, "Visible area=" + visibleArea.toShortString());
    }

    @Override
    public void onStableAreaChanged(@NonNull Rect stableArea) {
        Log.i(TAG, "Stable area=" + stableArea.toShortString());
    }

    @Override
    public void onSurfaceDestroyed(@NonNull SurfaceContainer container) {
        releasePlayer();
        surface = null;
    }

    private void releasePlayer() {
        if (player != null) {
            try { player.release(); } catch (Exception ignored) {}
            player = null;
        }
    }
}
