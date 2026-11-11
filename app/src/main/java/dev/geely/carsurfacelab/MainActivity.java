package dev.geely.carsurfacelab;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final int PICK_VIDEO = 101;
    private TextView status;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(30, 36, 30, 30);
        layout.setGravity(Gravity.TOP);

        TextView title = new TextView(this);
        title.setText("Car Surface Lab v0.1\nUSB / local MP4 / no Shizuku");
        title.setTextSize(23);
        layout.addView(title);

        status = new TextView(this);
        status.setTextSize(17);
        status.setPadding(0, 24, 0, 24);
        layout.addView(status);

        Button pick = new Button(this);
        pick.setText("Выбрать MP4 / видео на телефоне");
        pick.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("video/*");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(intent, PICK_VIDEO);
        });
        layout.addView(pick, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button clear = new Button(this);
        clear.setText("Очистить выбор / вывести тестовую картинку");
        clear.setOnClickListener(v -> {
            getSharedPreferences("lab", MODE_PRIVATE).edit().remove("video_uri").apply();
            updateStatus();
        });
        layout.addView(clear, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView help = new TextView(this);
        help.setText("Выбери локальный видеофайл, затем открой Car Surface Lab в Android Auto.\n"
            + "Касание экрана автомобиля переключает паузу. Если файл не выбран, рисуется статическая тестовая сетка.\n"
            + "Исследовательская сборка; проверять на стоянке.");
        help.setPadding(0, 25, 0, 0);
        layout.addView(help);
        setContentView(layout);
        updateStatus();
    }

    private void updateStatus() {
        String value = getSharedPreferences("lab", MODE_PRIVATE).getString("video_uri", null);
        status.setText(value == null ? "Файл не выбран: режим тестового изображения" : "Выбран файл:\n" + Uri.parse(value).getLastPathSegment());
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_VIDEO || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {}
        getSharedPreferences("lab", MODE_PRIVATE).edit().putString("video_uri", uri.toString()).apply();
        updateStatus();
    }
}
