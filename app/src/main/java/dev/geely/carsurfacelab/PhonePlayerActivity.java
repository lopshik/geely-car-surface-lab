package dev.geely.carsurfacelab;

import android.app.Activity;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

/** Phone-only local playback. Car projection remains a separate, exclusive output. */
public final class PhonePlayerActivity extends Activity {
    private VideoView video;
    private Uri uri;
    private boolean prepared;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        MediaCatalog.Entry selected = MediaCatalog.selected(this);
        if (selected == null || MediaCatalog.prefs(this).getBoolean("surface_connected", false)) {
            Toast.makeText(this, "Выбери фильм; отключи Android Auto для просмотра на телефоне",
                    Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        uri = Uri.parse(selected.uri);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF090F18);
        root.setPadding(12, 22, 12, 16);

        TextView title = new TextView(this);
        title.setText("‹  " + selected.title);
        title.setTextSize(19);
        title.setTextColor(Color.WHITE);
        title.setPadding(14, 10, 8, 15);
        title.setOnClickListener(v -> finish());
        root.addView(title);

        video = new VideoView(this);
        video.setBackgroundColor(Color.BLACK);
        root.addView(video, new LinearLayout.LayoutParams(-1, 0, 1f));
        MediaController controller = new MediaController(this);
        controller.setAnchorView(video);
        video.setMediaController(controller);

        TextView hint = new TextView(this);
        hint.setText("Нажми на видео для перемотки и управления · позиция сохраняется");
        hint.setTextColor(0xFF9DADBE);
        hint.setTextSize(13);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(12, 18, 12, 12);
        root.addView(hint);
        setContentView(root);

        video.setOnPreparedListener(mp -> {
            prepared = true;
            long saved = MediaCatalog.prefs(this).getLong(
                    PlaybackForegroundService.positionKey(uri), 0);
            if (saved > 0 && saved < video.getDuration() - 2500) {
                video.seekTo((int) Math.min(Integer.MAX_VALUE, saved));
            }
            video.start();
        });
        video.setOnCompletionListener(mp -> {
            MediaCatalog.prefs(this).edit()
                    .putLong(PlaybackForegroundService.positionKey(uri), 0)
                    .apply();
        });
        video.setOnErrorListener((mp, what, extra) -> {
            Toast.makeText(this, "Ошибка видео: " + what + "/" + extra,
                    Toast.LENGTH_LONG).show();
            return true;
        });
        video.setVideoURI(uri);
    }

    private void save() {
        if (!prepared || video == null || uri == null) return;
        int position = video.getCurrentPosition();
        if (position >= 0) {
            MediaCatalog.prefs(this).edit()
                    .putLong(PlaybackForegroundService.positionKey(uri), position)
                    .putLong("last_position_ms", position)
                    .putString("last_position_reason", "phone").apply();
        }
    }
    @Override protected void onPause() {
        save();
        if (video != null) video.pause();
        super.onPause();
    }
    @Override protected void onDestroy() {
        save();
        if (video != null) video.stopPlayback();
        super.onDestroy();
    }
}
