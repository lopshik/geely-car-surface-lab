package dev.geely.carsurfacelab;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final int PICK_VIDEO = 101;
    private TextView status;
    private TextView diagnostic;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(30, 30, 30, 40);
        layout.setGravity(Gravity.TOP);
        scroll.addView(layout);

        TextView title = new TextView(this);
        title.setText("Car Surface Lab v0.2\nUSB / Surface / no Shizuku");
        title.setTextSize(24);
        layout.addView(title);

        status = new TextView(this);
        status.setTextSize(16);
        status.setPadding(0, 22, 0, 18);
        layout.addView(status);

        Button choose = new Button(this);
        choose.setText("Выбрать локальное видео");
        choose.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("video/*");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(intent, PICK_VIDEO);
        });
        layout.addView(choose, new LinearLayout.LayoutParams(-1, -2));

        Button clear = new Button(this);
        clear.setText("Тестовая сетка (без видео)");
        clear.setOnClickListener(v -> {
            getSharedPreferences("lab", MODE_PRIVATE).edit().remove("video_uri").apply();
            updateStatus();
        });
        layout.addView(clear, new LinearLayout.LayoutParams(-1, -2));

        TextView heading = new TextView(this);
        heading.setText("Диагностика последнего подключения к Android Auto");
        heading.setTextSize(18);
        heading.setPadding(0, 30, 0, 8);
        layout.addView(heading);
        diagnostic = new TextView(this);
        diagnostic.setTextSize(14);
        layout.addView(diagnostic);

        Button copy = new Button(this);
        copy.setText("Копировать диагностику");
        copy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("Car Surface Lab diagnostics", diagnostic.getText()));
        });
        layout.addView(copy, new LinearLayout.LayoutParams(-1, -2));

        TextView help = new TextView(this);
        help.setText("Для первого теста оставь режим сетки, затем открой приложение в Android Auto. "
            + "После подключения вернись на телефон и скопируй диагностику. "
            + "Fermata и AnyAuto не изменяются. Проверять на стоянке.");
        help.setPadding(0, 20, 0, 0);
        layout.addView(help);
        setContentView(scroll);
        updateStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    private void updateStatus() {
        String uri = getSharedPreferences("lab", MODE_PRIVATE).getString("video_uri", null);
        status.setText(uri == null ? "Режим: тестовая сетка" :
                "Режим: видео\n" + Uri.parse(uri).getLastPathSegment());
        String report = getSharedPreferences("lab", MODE_PRIVATE)
                .getString("diagnostics", "Ещё не подключалось к Android Auto");
        diagnostic.setText("Package: " + getPackageName() + "\n" + report);
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
