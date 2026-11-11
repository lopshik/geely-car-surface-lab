package dev.geely.carsurfacelab;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Debug detail is one tap away, but never expands the main movie library. */
public final class DiagnosticsActivity extends Activity {
    private static final int BG = 0xFF0B1420, SURFACE = 0xFF1B2C3B,
            TEXT = 0xFFF5F8FB, ACCENT = 0xFF54D4F3;
    private TextView reportView;

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable bg(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color); d.setCornerRadius(dp(12));
        return d;
    }

    private TextView button(String label, View.OnClickListener onClick) {
        TextView t = new TextView(this);
        t.setText(label); t.setTextSize(14); t.setTextColor(TEXT);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        t.setBackground(bg(SURFACE));
        t.setPadding(dp(12),dp(12),dp(12),dp(12));
        t.setOnClickListener(onClick);
        return t;
    }

    private String makeReport() {
        SharedPreferences p = getSharedPreferences("lab", MODE_PRIVATE);
        return "CarVideo / " + getPackageName()
                + "\nAudio service running: " + (PlaybackForegroundService.active() != null)
                + "\nSpeed: " + p.getFloat("playback_rate",1f)
                + "\nPicture: " + p.getString("picture_mode","fill")
                + "\nFolder: " + MediaCatalog.folderLabel(this)
                + "\nFiles: " + MediaCatalog.all(this).size()
                + "\n---- Audio ----\n" + p.getString("audio_diagnostics","")
                + "\n---- Screen ----\n" + p.getString("diagnostics","")
                + "\n---- Playback events ----\n" + p.getString("audio_events","")
                + "\n---- Surface events ----\n" + p.getString("screen_events","")
                + "\n---- Position ----\n" + p.getLong("last_position_ms",0)
                + "ms, reason=" + p.getString("last_position_reason","")
                + ", interrupted=" + p.getBoolean("resume_interrupted",false)
                + "\n---- Library ----\n" + p.getString("catalog_error","ok");
    }

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setBackgroundColor(BG);
        root.setPadding(dp(18),dp(18),dp(18),dp(12));
        root.setOrientation(LinearLayout.VERTICAL);
        setContentView(root);

        TextView title = new TextView(this);
        title.setText("‹  Диагностика");
        title.setTextSize(23); title.setTextColor(TEXT);
        title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        title.setOnClickListener(v -> finish());
        root.addView(title);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1,dp(54));
        ap.setMargins(0,dp(13),0,dp(9));
        root.addView(actions,ap);
        TextView copy = button("Скопировать",v -> {
            ClipboardManager cb = (ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
            cb.setPrimaryClip(ClipData.newPlainText("CarVideo diagnostic",makeReport()));
            Toast.makeText(this,"Лог скопирован",Toast.LENGTH_SHORT).show();
        });
        actions.addView(copy,new LinearLayout.LayoutParams(0,-1,1));
        TextView share = button("Поделиться",v -> {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_TEXT,makeReport());
            startActivity(Intent.createChooser(send,"Отправить диагностику"));
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0,-1,1);
        sp.setMargins(dp(8),0,0,0);
        actions.addView(share,sp);

        ScrollView scroll = new ScrollView(this);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        reportView = new TextView(this);
        reportView.setTextColor(0xFFBFD2E0);
        reportView.setTextSize(12);
        reportView.setTextIsSelectable(true);
        reportView.setPadding(dp(11),dp(12),dp(11),dp(16));
        reportView.setBackground(bg(SURFACE));
        scroll.addView(reportView);
        reportView.setText(makeReport());
    }

    @Override protected void onResume() {
        super.onResume();
        if (reportView != null) reportView.setText(makeReport());
    }
}
