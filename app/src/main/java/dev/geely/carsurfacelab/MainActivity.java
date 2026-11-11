package dev.geely.carsurfacelab;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.EditText;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.InputType;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** Native Android UI: dark, economical and fully usable without the car. */
public final class MainActivity extends Activity {
    private static final int PICK_VIDEO = 101;
    private static final int ACCESS_VIDEO = 102;
    private static final int BG = 0xFF09121D, PANEL = 0xFF142235,
            TEXT = 0xFFF4F8FF, MUTED = 0xFF9AAEC3, ACCENT = 0xFF55D5FF;
    private TextView playingName, playingStatus, playbackTime, scanStatus, diagnosticText;
    private LinearLayout catalog;
    private EditText searchBox;
    private TextView allFilter, favoritesFilter;
    private boolean favoritesOnly;
    private SeekBar timeline;
    private int catalogShown = 12;
    private boolean diagnosticsVisible;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            showPlaybackStatus();
            ui.postDelayed(this, 1100);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        scroller.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(26), dp(18), dp(36));
        scroller.addView(root);
        setContentView(scroller);

        TextView eyebrow = txt("GEELY  /  XIAOMI 15", 12, ACCENT, true);
        eyebrow.setLetterSpacing(0.15f);
        root.addView(eyebrow);
        root.addView(txt("CarVideo", 36, TEXT, true));
        TextView subtitle = txt("Локальный кинотеатр · USB · без Shizuku", 14, MUTED, false);
        subtitle.setPadding(0, dp(5), 0, dp(17));
        root.addView(subtitle);

        LinearLayout hero = panel();
        hero.setBackground(roundGradient(new int[]{0xFF203F5A, 0xFF102236}, 24));
        TextView badge = txt("СЕЙЧАС ВЫБРАНО", 11, ACCENT, true);
        hero.addView(badge);
        playingName = txt("Фильм не выбран", 21, TEXT, true);
        playingName.setPadding(0, dp(12), 0, dp(6));
        hero.addView(playingName);
        playingStatus = txt("Подключи Geely или выбери видео", 13, MUTED, false);
        hero.addView(playingStatus);

        timeline = new SeekBar(this);
        timeline.setMax(1000);
        timeline.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        timeline.setThumbTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        timeline.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int p, boolean fromUser) {}
            @Override public void onStartTrackingTouch(SeekBar b) {}
            @Override public void onStopTrackingTouch(SeekBar b) {
                PlaybackForegroundService s = PlaybackForegroundService.active();
                if (s != null && s.duration() > 0) s.seekTo(s.duration() * b.getProgress() / 1000);
            }
        });
        hero.addView(timeline);
        playbackTime = txt("00:00 / --:--", 12, MUTED, false);
        hero.addView(playbackTime);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setPadding(0, dp(15), 0, 0);
        controls.addView(button("−10 c", false, v -> seekRelative(-10000)), weight(1));
        controls.addView(button("▶ / Ⅱ", true, v -> {
            PlaybackForegroundService s = PlaybackForegroundService.active();
            if (s != null) s.toggle();
            else Toast.makeText(this, "Запусти видео на Geely", Toast.LENGTH_SHORT).show();
            showPlaybackStatus();
        }), weight(2));
        controls.addView(button("+10 c", false, v -> seekRelative(10000)), weight(1));
        hero.addView(controls);
        root.addView(hero);

        section(root, "Видеотека", "Выбор работает и на телефоне, и на экране Geely");
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.addView(button("Найти видео", true, v -> scanOrRequest()), weight(1));
        buttons.addView(button("+ MP4", false, v -> pickFile()), weight(1));
        root.addView(buttons);

        scanStatus = txt("Выбери файл или разреши доступ к видеотеке", 12, MUTED, false);
        scanStatus.setPadding(dp(5), dp(13), 0, dp(10));
        root.addView(scanStatus);

        searchBox = new EditText(this);
        searchBox.setSingleLine(true);
        searchBox.setTextSize(15);
        searchBox.setTextColor(TEXT);
        searchBox.setHintTextColor(MUTED);
        searchBox.setHint("Поиск по названию или папке");
        searchBox.setInputType(InputType.TYPE_CLASS_TEXT);
        searchBox.setPadding(dp(14), dp(11), dp(14), dp(11));
        searchBox.setBackground(round(PANEL, 15));
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(-1, dp(52));
        searchParams.setMargins(0, dp(5), 0, dp(12));
        root.addView(searchBox, searchParams);
        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                catalogShown = 12;
                displayCatalog();
            }
            @Override public void afterTextChanged(Editable e) {}
        });

        LinearLayout filters = new LinearLayout(this);
        filters.setOrientation(LinearLayout.HORIZONTAL);
        allFilter = button("Все видео", true, v -> setFavoritesOnly(false));
        favoritesFilter = button("★ Избранное", false, v -> setFavoritesOnly(true));
        filters.addView(allFilter, weight(1));
        filters.addView(favoritesFilter, weight(1));
        root.addView(filters);

        catalog = new LinearLayout(this);
        catalog.setOrientation(LinearLayout.VERTICAL);
        root.addView(catalog);

        TextView more = button("Показать ещё фильмы", false, v -> {
            catalogShown = Math.min(240, catalogShown + 20);
            displayCatalog();
        });
        root.addView(more);

        section(root, "Воспроизведение", "Видео на телефоне или на большом экране");
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.addView(button("На телефоне", false, v -> playPhone()), weight(1));
        actions.addView(button("Звук для ГУ", true, v -> startAudioService()), weight(1));
        root.addView(actions);
        root.addView(button("Остановить звук и освободить ресурсы", false, v -> {
            stopService(new Intent(this, PlaybackForegroundService.class));
            ui.postDelayed(this::showPlaybackStatus, 300);
        }));

        section(root, "Диагностика", "Доступна по запросу; не влияет на воспроизведение");
        TextView diagnosticsToggle = button("Показать / скрыть технический отчёт", false, v -> {
            diagnosticsVisible = !diagnosticsVisible;
            diagnosticText.setVisibility(diagnosticsVisible ? View.VISIBLE : View.GONE);
            updateDiagnostics();
        });
        root.addView(diagnosticsToggle);
        diagnosticText = txt("", 11, MUTED, false);
        diagnosticText.setTextIsSelectable(true);
        diagnosticText.setPadding(dp(9), dp(12), dp(9), dp(12));
        diagnosticText.setVisibility(View.GONE);
        root.addView(diagnosticText);
        root.addView(button("Копировать диагностику", false, v -> {
            updateDiagnostics();
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("CarVideo diagnostic", diagnosticText.getText()));
            Toast.makeText(this, "Отчёт скопирован", Toast.LENGTH_SHORT).show();
        }));

        TextView foot = txt("CarVideo PREVIEW 07 · испытания только на стоянке", 11, MUTED, false);
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(0, dp(24), 0, 0);
        root.addView(foot);
        displayCatalog();
        showPlaybackStatus();
    }

    private int dp(int d) { return Math.round(d * getResources().getDisplayMetrics().density); }
    private LinearLayout.LayoutParams weight(int w) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(48), w);
        p.setMargins(dp(2), 0, dp(2), 0);
        return p;
    }
    private GradientDrawable round(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        return d;
    }
    private GradientDrawable roundGradient(int[] colors, int radius) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TL_BR, colors);
        d.setCornerRadius(dp(radius));
        return d;
    }
    private TextView txt(String text, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text); t.setTextSize(size); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }
    private LinearLayout panel() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(18), dp(18), dp(18), dp(17));
        p.setBackground(round(PANEL, 24));
        LinearLayout.LayoutParams m = new LinearLayout.LayoutParams(-1, -2);
        m.setMargins(0, dp(7), 0, dp(11));
        p.setLayoutParams(m);
        return p;
    }
    private TextView button(String text, boolean primary, View.OnClickListener onClick) {
        TextView b = txt(text, 14, primary ? BG : TEXT, true);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(48));
        b.setPadding(dp(12), dp(10), dp(12), dp(10));
        b.setBackground(round(primary ? ACCENT : 0xFF203145, 14));
        b.setOnClickListener(onClick);
        b.setClickable(true);
        b.setFocusable(true);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, dp(4), 0, dp(4));
        b.setLayoutParams(p);
        return b;
    }
    private void section(LinearLayout root, String title, String subtitle) {
        TextView heading = txt(title, 22, TEXT, true);
        heading.setPadding(0, dp(18), 0, dp(5));
        root.addView(heading);
        TextView desc = txt(subtitle, 12, MUTED, false);
        desc.setPadding(0, 0, 0, dp(10));
        root.addView(desc);
    }
    private void setFavoritesOnly(boolean enabled) {
        favoritesOnly = enabled;
        catalogShown = 12;
        allFilter.setBackground(round(enabled ? 0xFF203145 : ACCENT, 14));
        allFilter.setTextColor(enabled ? TEXT : BG);
        favoritesFilter.setBackground(round(enabled ? ACCENT : 0xFF203145, 14));
        favoritesFilter.setTextColor(enabled ? BG : TEXT);
        displayCatalog();
    }

    private void displayCatalog() {
        if (catalog == null) return;
        catalog.removeAllViews();
        String query = searchBox == null ? "" : searchBox.getText().toString();
        List<MediaCatalog.Entry> entries = MediaCatalog.filtered(this, favoritesOnly, null, query);
        if (entries.isEmpty()) {
            TextView hint = txt("Пока пусто. Нажми «Найти видео» или «+ MP4».", 14, MUTED, false);
            hint.setPadding(dp(10), dp(22), dp(10), dp(22));
            catalog.addView(hint);
            return;
        }
        String selectedUri = MediaCatalog.prefs(this).getString("video_uri", "");
        for (int i = 0; i < Math.min(catalogShown, entries.size()); i++) {
            MediaCatalog.Entry entry = entries.get(i);
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(dp(10), dp(10), dp(12), dp(10));
            item.setBackground(round(entry.uri.equals(selectedUri) ? 0xFF24445A : PANEL, 15));
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, dp(82));
            rowParams.setMargins(0, dp(3), 0, dp(6));
            catalog.addView(item, rowParams);

            ImageView cover = new ImageView(this);
            cover.setImageBitmap(MediaCatalog.poster(this, entry));
            cover.setTag(entry.uri);
            MediaCatalog.preparePoster(this, entry, thumb -> {
                if (entry.uri.equals(cover.getTag())) cover.setImageBitmap(thumb);
            });
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            LinearLayout.LayoutParams icon = new LinearLayout.LayoutParams(dp(57), dp(57));
            icon.setMargins(0, 0, dp(13), 0);
            item.addView(cover, icon);

            LinearLayout title = new LinearLayout(this);
            title.setOrientation(LinearLayout.VERTICAL);
            TextView name = txt(entry.title, 15, TEXT, true);
            name.setSingleLine(true); name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            title.addView(name);
            TextView sub = txt(entry.folder + "  •  " +
                    (entry.durationMs > 0 ? MediaCatalog.time(entry.durationMs) : "MP4"),
                    11, MUTED, false);
            sub.setSingleLine(true);
            sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            sub.setPadding(0, dp(6), 0, 0);
            title.addView(sub);
            item.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView favorite = txt(MediaCatalog.favorite(this, entry) ? "★" : "☆",
                    24, ACCENT, true);
            favorite.setPadding(dp(10), dp(8), dp(6), dp(8));
            favorite.setOnClickListener(v -> {
                MediaCatalog.toggleFavorite(this, entry);
                displayCatalog();
            });
            item.addView(favorite);
            TextView play = txt("▶", 19, ACCENT, true);
            play.setPadding(dp(7), 0, 0, 0);
            item.addView(play);
            item.setOnClickListener(v -> selectMovie(entry));
        }
    }

    private void selectMovie(MediaCatalog.Entry entry) {
        // Only pre-warm FGS when actually connected to car; saves battery otherwise.
        if (MediaCatalog.prefs(this).getBoolean("surface_connected", false)) startAudioService();
        MediaCatalog.choose(this, entry);
        displayCatalog();
        showPlaybackStatus();
    }
    private void playPhone() {
        if (MediaCatalog.selected(this) == null) {
            Toast.makeText(this, "Сначала выбери фильм", Toast.LENGTH_SHORT).show();
            return;
        }
        if (MediaCatalog.prefs(this).getBoolean("surface_connected", false)) {
            Toast.makeText(this, "Видео сейчас выводится на Geely", Toast.LENGTH_SHORT).show();
            return;
        }
        startActivity(new Intent(this, PhonePlayerActivity.class));
    }
    private void pickFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("video/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, PICK_VIDEO);
    }
    private boolean hasMediaPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED)
                return true;
            return Build.VERSION.SDK_INT >= 34 &&
                    checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                            == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }
    private void scanOrRequest() {
        if (!hasMediaPermission()) {
            if (Build.VERSION.SDK_INT >= 34) {
                requestPermissions(new String[]{Manifest.permission.READ_MEDIA_VIDEO,
                        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED}, ACCESS_VIDEO);
            } else if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(new String[]{Manifest.permission.READ_MEDIA_VIDEO}, ACCESS_VIDEO);
            } else {
                requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, ACCESS_VIDEO);
            }
            return;
        }
        scanInBackground();
    }
    private void scanInBackground() {
        scanStatus.setText("Поиск видео на телефоне…");
        new Thread(() -> {
            int count = MediaCatalog.scan(getApplicationContext());
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                scanStatus.setText(count >= 0 ? ("Найдено видео: " + count)
                        : ("Нет доступа: " + MediaCatalog.prefs(this)
                        .getString("catalog_error", "неизвестная ошибка")));
                displayCatalog();
            });
        }, "video-catalog-scan").start();
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request == ACCESS_VIDEO) {
            if (hasMediaPermission()) scanInBackground();
            else Toast.makeText(this, "Можно добавить отдельный MP4 без доступа к библиотеке",
                    Toast.LENGTH_LONG).show();
        }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_VIDEO || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
        catch (SecurityException ignored) {}
        selectMovie(MediaCatalog.openDocument(this, uri));
    }
    private void startAudioService() {
        try {
            if (Build.VERSION.SDK_INT >= 26)
                startForegroundService(new Intent(this, PlaybackForegroundService.class));
            else startService(new Intent(this, PlaybackForegroundService.class));
        } catch (RuntimeException ex) {
            MediaCatalog.prefs(this).edit().putString("audio_diagnostics",
                    "Start foreground service: " + ex.getClass().getSimpleName()).apply();
            Toast.makeText(this, "Сервис звука не запущен", Toast.LENGTH_LONG).show();
        }
    }
    private void seekRelative(long delta) {
        PlaybackForegroundService service = PlaybackForegroundService.active();
        if (service != null) service.seekTo(service.position() + delta);
        showPlaybackStatus();
    }
    private void showPlaybackStatus() {
        if (playingName == null) return;
        MediaCatalog.Entry entry = MediaCatalog.selected(this);
        playingName.setText(entry == null ? "Выбери фильм" : entry.title);
        PlaybackForegroundService service = PlaybackForegroundService.active();
        long position = service == null ?
                MediaCatalog.prefs(this).getLong("last_position_ms", 0) : service.position();
        long duration = service == null ? 0 : service.duration();
        boolean connected = MediaCatalog.prefs(this).getBoolean("surface_connected", false);
        playingStatus.setText(connected ?
                (service != null && service.playing() ? "▶ Играет на Geely" : "● Geely подключён")
                : "Телефон · готово к подключению");
        playbackTime.setText(MediaCatalog.time(position) + " / " +
                (duration > 0 ? MediaCatalog.time(duration) : "--:--"));
        if (duration > 0) timeline.setProgress((int) Math.min(1000, position * 1000 / duration));
    }
    private void updateDiagnostics() {
        SharedPreferences p = MediaCatalog.prefs(this);
        String info = "Package: " + getPackageName()
                + "\nAudio service running: " + (PlaybackForegroundService.active() != null)
                + "\n" + p.getString("audio_diagnostics", "")
                + "\n---- Screen ----\n" + p.getString("diagnostics", "")
                + "\n---- Audio events ----\n" + p.getString("audio_events", "")
                + "\n---- Surface events ----\n" + p.getString("screen_events", "")
                + "\n---- Resume ----\nposition: " + p.getLong("last_position_ms", 0)
                + "ms, reason: " + p.getString("last_position_reason", "")
                + ", interrupted: " + p.getBoolean("resume_interrupted", false);
        diagnosticText.setText(info);
    }
    @Override protected void onResume() {
        super.onResume();
        showPlaybackStatus();
        displayCatalog();
        ui.removeCallbacks(refresh);
        ui.post(refresh);
    }
    @Override protected void onPause() {
        ui.removeCallbacks(refresh);
        super.onPause();
    }
}
