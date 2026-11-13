package dev.geely.carsurfacelab;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.UriPermission;
import android.provider.DocumentsContract;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;

/**
 * CarVideo 0.8.2: compact phone remote and local film library.
 * Android Auto video Surface and foreground playback engine are unchanged.
 */
public final class MainActivity extends Activity {
    private static final int CHOOSE_FOLDER = 101;
    private static final int BG=0xFF09131F, PANEL=0xFF152637,
            TEXT=0xFFF4F8FD, MUTED=0xFF9DB1C2, ACCENT=0xFF57D0F2;

    private TextView nowTitle, nowTime, nowStatus, folderName, countView, rateButton,
            pictureButton, moreButton, optionsButton;
    private LinearLayout catalog, playbackControls, optionsRow, root;
    private SeekBar timeline;
    private int shown = 40;
    private boolean dragging, optionsExpanded;
    private boolean deletionInProgress;
    private boolean lastSelected;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            updatePlaying();
            ui.postDelayed(this,1300);
        }
    };

    private int dp(int x) {
        return Math.round(x*getResources().getDisplayMetrics().density);
    }

    private GradientDrawable rounded(int color,int radius) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(radius));
        return bg;
    }

    private GradientDrawable gradient() {
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF203E53,0xFF14283D});
        bg.setCornerRadius(dp(17));
        return bg;
    }

    private TextView text(String value,int size,int color,boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        return t;
    }

    private TextView button(String name,boolean primary,int height,View.OnClickListener listener) {
        TextView t = text(name,14,primary?BG:TEXT,true);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(dp(height));
        t.setPadding(dp(9),dp(7),dp(9),dp(7));
        t.setBackground(rounded(primary?ACCENT:0xFF23364A,12));
        t.setOnClickListener(listener);
        t.setFocusable(true);
        return t;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private LinearLayout.LayoutParams weighted(float w,int h) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,dp(h),w);
        p.setMargins(dp(2),0,dp(2),0);
        return p;
    }

    private LinearLayout.LayoutParams spaced(int top,int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1,-2);
        p.setMargins(0,dp(top),0,dp(bottom));
        return p;
    }

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(root);
        // Android 15/16 target 35 is edge-to-edge: controls must not sit under
        // the system clock, battery or bottom gesture area.
        scroll.setOnApplyWindowInsetsListener((view,insets) -> {
            int top,bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets sys = insets.getInsets(
                        WindowInsets.Type.systemBars() |
                        WindowInsets.Type.displayCutout());
                top = sys.top;
                bottom = sys.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(dp(16),top+dp(9),dp(16),bottom+dp(18));
            return insets;
        });
        setContentView(scroll);
        scroll.requestApplyInsets();

        LinearLayout header = row();
        TextView name = text("CarVideo",25,TEXT,true);
        header.addView(name,new LinearLayout.LayoutParams(0,-2,1f));
        TextView logButton = button("Лог",false,40,v ->
                startActivity(new Intent(this,DiagnosticsActivity.class)));
        header.addView(logButton,new LinearLayout.LayoutParams(dp(56),dp(40)));
        root.addView(header,spaced(0,12));

        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(dp(14),dp(12),dp(14),dp(12));
        hero.setBackground(gradient());
        root.addView(hero,spaced(0,14));

        LinearLayout titleRow = row();
        nowTitle = text("Фильм не выбран",17,TEXT,true);
        nowTitle.setMaxLines(2);
        nowTitle.setEllipsize(TextUtils.TruncateAt.END);
        titleRow.addView(nowTitle,new LinearLayout.LayoutParams(0,-2,1f));
        optionsButton = button("⚙",false,40,v -> {
            optionsExpanded = !optionsExpanded;
            optionsRow.setVisibility(optionsExpanded?View.VISIBLE:View.GONE);
        });
        titleRow.addView(optionsButton,new LinearLayout.LayoutParams(dp(43),dp(40)));
        hero.addView(titleRow);

        nowStatus = text("Выбери фильм в списке ниже",12,MUTED,false);
        nowStatus.setPadding(0,0,0,0);
        hero.addView(nowStatus);

        playbackControls = new LinearLayout(this);
        playbackControls.setOrientation(LinearLayout.VERTICAL);
        hero.addView(playbackControls);
        playbackControls.setVisibility(View.GONE);

        timeline = new SeekBar(this);
        timeline.setMax(1000);
        timeline.setMinHeight(dp(26));
        timeline.setProgressTintList(ColorStateList.valueOf(ACCENT));
        timeline.setThumbTintList(ColorStateList.valueOf(ACCENT));
        timeline.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar,int progress,boolean fromUser) {}
            @Override public void onStartTrackingTouch(SeekBar bar) {dragging = true;}
            @Override public void onStopTrackingTouch(SeekBar bar) {
                dragging = false;
                PlaybackForegroundService service = PlaybackForegroundService.active();
                if (service != null && service.duration() > 0)
                    service.seekTo(service.duration()*bar.getProgress()/1000);
            }
        });
        playbackControls.addView(timeline,spaced(5,0));
        nowTime = text("00:00 / --:--",12,MUTED,false);
        playbackControls.addView(nowTime);

        LinearLayout controls = row();
        controls.setPadding(0,dp(8),0,0);
        controls.addView(button("−10 c",false,42,v -> jump(-10000)),weighted(1,42));
        controls.addView(button("▶ / Ⅱ",true,42,v -> {
            PlaybackForegroundService s = PlaybackForegroundService.active();
            if (s != null) s.toggle();
            else Toast.makeText(this,"Открой выбранный фильм на экране автомобиля",
                    Toast.LENGTH_SHORT).show();
            updatePlaying();
        }),weighted(1.5f,42));
        controls.addView(button("+10 c",false,42,v -> jump(10000)),weighted(1,42));
        playbackControls.addView(controls);

        optionsRow = row();
        optionsRow.setPadding(0,dp(9),0,0);
        rateButton = button("1×",false,42,v -> cycleRate());
        pictureButton = button("Заполнить",false,42,v -> cyclePicture());
        optionsRow.addView(rateButton,weighted(1,42));
        optionsRow.addView(pictureButton,weighted(1.5f,42));
        optionsRow.setVisibility(View.GONE);
        hero.addView(optionsRow);

        LinearLayout libraryHeader = row();
        TextView libraryTitle = text("Мои видео",20,TEXT,true);
        libraryHeader.addView(libraryTitle,new LinearLayout.LayoutParams(0,-2,1f));
        countView = text("",13,MUTED,false);
        libraryHeader.addView(countView);
        root.addView(libraryHeader,spaced(2,7));

        LinearLayout folderTools = row();
        folderName = text(MediaCatalog.folderLabel(this),13,MUTED,false);
        folderName.setSingleLine(true);
        folderName.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        folderTools.addView(folderName,new LinearLayout.LayoutParams(0,-2,1f));
        folderTools.addView(button("Папка",false,38,v -> pickFolder()),
                new LinearLayout.LayoutParams(dp(74),dp(38)));
        TextView refreshButton = button("↻",false,38,v -> scanFolder());
        LinearLayout.LayoutParams refreshParams =
                new LinearLayout.LayoutParams(dp(43),dp(38));
        refreshParams.setMargins(dp(6),0,0,0);
        folderTools.addView(refreshButton,refreshParams);
        root.addView(folderTools,spaced(0,5));

        catalog = new LinearLayout(this);
        catalog.setOrientation(LinearLayout.VERTICAL);
        root.addView(catalog);
        moreButton = button("Показать остальные фильмы",false,44,v -> {
            shown = Math.min(250,shown+30);
            renderCatalog();
        });
        root.addView(moreButton,spaced(8,0));

        renderCatalog();
        updatePlaying();
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("lab",MODE_PRIVATE);
    }

    private void pickFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent,CHOOSE_FOLDER);
    }

    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if (request != CHOOSE_FOLDER || result != RESULT_OK ||
                data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            int returned = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION |
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            if ((returned & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0)
                throw new SecurityException("No read permission to video folder");
            getContentResolver().takePersistableUriPermission(uri, returned);
        } catch (SecurityException e) {
            Toast.makeText(this,"Не удалось сохранить доступ к папке",
                    Toast.LENGTH_LONG).show();
            return;
        }
        prefs().edit().putString("video_folder_uri",uri.toString()).apply();
        shown = 40;
        folderName.setText(MediaCatalog.folderLabel(this));
        scanFolder();
    }

    private void scanFolder() {
        if (prefs().getString("video_folder_uri",null) == null) {
            pickFolder();
            return;
        }
        countView.setText("Обновление…");
        new Thread(() -> {
            int n = MediaCatalog.scanFolder(getApplicationContext());
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (n < 0) {
                    Toast.makeText(this,prefs().getString("catalog_error","Ошибка чтения папки"),
                            Toast.LENGTH_LONG).show();
                }
                renderCatalog();
            });
        },"CarVideo-folder-refresh").start();
    }

    private void renderCatalog() {
        if (catalog == null) return;
        catalog.removeAllViews();
        List<MediaCatalog.Entry> movies = MediaCatalog.all(this);
        countView.setText(movies.size()+" файлов");
        folderName.setText(MediaCatalog.folderLabel(this));
        moreButton.setVisibility(movies.size()>shown?View.VISIBLE:View.GONE);
        if (movies.isEmpty()) {
            TextView empty = text("Выбери папку с видео",15,MUTED,false);
            empty.setPadding(dp(5),dp(18),dp(5),dp(24));
            catalog.addView(empty);
            return;
        }
        String selected = prefs().getString("video_uri","");
        for (int i=0;i<Math.min(shown,movies.size());i++) {
            MediaCatalog.Entry movie = movies.get(i);
            LinearLayout item = row();
            item.setPadding(dp(8),dp(5),dp(8),dp(5));
            item.setBackground(rounded(movie.uri.equals(selected)?0xFF22455A:PANEL,12));
            catalog.addView(item,spaced(3,3));

            ImageView image = new ImageView(this);
            image.setImageBitmap(MediaCatalog.poster(this,movie));
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setTag(movie.uri);
            LinearLayout.LayoutParams thumb =
                    new LinearLayout.LayoutParams(dp(57),dp(57));
            thumb.setMargins(0,0,dp(11),0);
            item.addView(image,thumb);
            MediaCatalog.preparePoster(this,movie,bitmap -> {
                if (movie.uri.equals(image.getTag())) image.setImageBitmap(bitmap);
            });

            LinearLayout details = new LinearLayout(this);
            details.setOrientation(LinearLayout.VERTICAL);
            details.setGravity(Gravity.CENTER_VERTICAL);
            TextView label = text(MediaCatalog.displayTitle(movie.title),15,TEXT,true);
            label.setMaxLines(2);
            label.setEllipsize(TextUtils.TruncateAt.END);
            details.addView(label);
            String subtitle = MediaCatalog.subtitle(this,movie);
            if (!subtitle.isEmpty()) {
                TextView sub = text(subtitle,11,MUTED,false);
                sub.setPadding(0,dp(3),0,0);
                details.addView(sub);
            }
            item.addView(details,new LinearLayout.LayoutParams(0,-1,1f));
            TextView arrow = text("▶",18,ACCENT,true);
            arrow.setPadding(dp(8),0,dp(2),0);
            item.addView(arrow);

            // The movie row still selects a film. Delete is a separate,
            // accessible 44dp target and can NEVER act without confirmation.
            TextView remove = text("✕",21,0xFFFF8C8C,true);
            remove.setGravity(Gravity.CENTER);
            remove.setMinWidth(dp(44));
            remove.setMinHeight(dp(44));
            remove.setContentDescription("Удалить файл: " + movie.title);
            remove.setOnClickListener(v -> confirmDelete(movie));
            item.addView(remove,new LinearLayout.LayoutParams(dp(44),dp(48)));

            item.setOnClickListener(v -> {
                if (prefs().getBoolean("surface_connected",false) &&
                        PlaybackForegroundService.active() == null) startAudio();
                MediaCatalog.choose(this,movie);
                renderCatalog();
                updatePlaying();
            });
        }
    }

    /** A persistent *write* grant is required: earlier versions only asked to read. */
    private boolean hasFolderWritePermission() {
        String raw = prefs().getString("video_folder_uri",null);
        if (raw == null) return false;
        Uri tree = Uri.parse(raw);
        for (UriPermission grant : getContentResolver().getPersistedUriPermissions()) {
            if (tree.equals(grant.getUri()) && grant.isWritePermission()) return true;
        }
        return false;
    }

    private void confirmDelete(MediaCatalog.Entry movie) {
        if (deletionInProgress) return;
        // Deleting the actively projected file can invalidate the decoder's source
        // and conflict with the OEM camera. The user must stop it first.
        if (movie.uri.equals(prefs().getString("video_uri",null)) &&
                prefs().getBoolean("surface_connected",false)) {
            Toast.makeText(this,
                    "Останови этот фильм на Geely перед удалением",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (!hasFolderWritePermission()) {
            new AlertDialog.Builder(this)
                    .setTitle("Нет разрешения на удаление")
                    .setMessage("Android разрешил только просмотр. Выбери папку ещё раз " +
                            "и предоставь доступ к её изменению. Файл пока не затронут.")
                    .setNegativeButton("Отмена",null)
                    .setPositiveButton("Выбрать папку",(dialog,which) -> pickFolder())
                    .show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Удалить файл безвозвратно?")
                .setMessage(movie.title + "\n\nФайл исчезнет из памяти телефона, " +
                        "а не только из видеотеки. Отменить удаление нельзя.")
                .setNegativeButton("Оставить",null)
                .setPositiveButton("Удалить",(dialog,which) -> deleteConfirmed(movie))
                .show();
    }

    private void deleteConfirmed(MediaCatalog.Entry movie) {
        if (deletionInProgress) return;
        deletionInProgress = true;
        new Thread(() -> {
            boolean deleted = false;
            String failure = null;
            int count = -1;
            try {
                String folderRaw = prefs().getString("video_folder_uri",null);
                if (folderRaw == null) throw new SecurityException("Папка не выбрана");
                Uri tree = Uri.parse(folderRaw);
                Uri doc = Uri.parse(movie.uri);
                // Only delete entries that originated from this selected tree.
                if (!tree.getAuthority().equals(doc.getAuthority()) ||
                        !DocumentsContract.getTreeDocumentId(tree)
                                .equals(DocumentsContract.getTreeDocumentId(doc))) {
                    throw new SecurityException("Файл не принадлежит выбранной папке");
                }
                boolean inCatalog = false;
                for (MediaCatalog.Entry existing : MediaCatalog.all(getApplicationContext())) {
                    if (movie.uri.equals(existing.uri)) { inCatalog = true; break; }
                }
                if (!inCatalog) throw new SecurityException("Файл отсутствует в видеотеке");
                if (movie.uri.equals(prefs().getString("video_uri",null)) &&
                        prefs().getBoolean("surface_connected",false)) {
                    throw new IllegalStateException("Видео сейчас открыто на Geely");
                }
                deleted = DocumentsContract.deleteDocument(getContentResolver(),doc);
                if (!deleted) throw new IllegalStateException(
                        "Провайдер не поддерживает удаление этого файла");
                MediaCatalog.forgetDeleted(getApplicationContext(),movie);
                count = MediaCatalog.scanFolder(getApplicationContext());
            } catch (Exception e) {
                android.util.Log.w("CarVideo.Delete","Document deletion failed",e);
                failure = e.getMessage();
                if (failure == null || failure.trim().isEmpty())
                    failure = e.getClass().getSimpleName();
            }
            final boolean success = deleted;
            final String reason = failure;
            final int scanned = count;
            runOnUiThread(() -> {
                deletionInProgress = false;
                if (isFinishing() || isDestroyed()) return;
                if (success) {
                    Toast.makeText(this,scanned >= 0 ?
                            "Файл удалён" : "Файл удалён, но список не обновился",
                            Toast.LENGTH_LONG).show();
                    renderCatalog();
                    updatePlaying();
                } else {
                    new AlertDialog.Builder(this).setTitle("Не удалось удалить")
                            .setMessage(reason == null ? "Android отклонил удаление" : reason)
                            .setPositiveButton("Понятно",null).show();
                }
            });
        },"CarVideo-delete-selected-file").start();
    }

    private void startAudio() {
        try {
            if (Build.VERSION.SDK_INT >= 26)
                startForegroundService(new Intent(this,PlaybackForegroundService.class));
            else startService(new Intent(this,PlaybackForegroundService.class));
        } catch (RuntimeException e) {
            prefs().edit().putString("audio_diagnostics",
                    "Start service: "+e.getClass().getSimpleName()).apply();
        }
    }

    private void jump(long ms) {
        PlaybackForegroundService service = PlaybackForegroundService.active();
        if (service != null) service.seekTo(service.position()+ms);
        updatePlaying();
    }

    private void cycleRate() {
        float[] choices = {0.75f,1f,1.25f,1.5f,2f};
        float current = prefs().getFloat("playback_rate",1f);
        int index=1;
        for(int i=0;i<choices.length;i++) {
            if (Math.abs(current-choices[i])<0.01f) {index=i;break;}
        }
        float next = choices[(index+1)%choices.length];
        PlaybackForegroundService service = PlaybackForegroundService.active();
        if (service != null) service.changeRate(next);
        else prefs().edit().putFloat("playback_rate",next).apply();
        updatePlaying();
    }

    private void cyclePicture() {
        String current = prefs().getString("picture_mode","fill");
        String next = "fit".equals(current)?"fill":"fit";
        PlaybackForegroundService service = PlaybackForegroundService.active();
        if (service != null) service.changePicture(next);
        else prefs().edit().putString("picture_mode",next).apply();
        updatePlaying();
    }

    private void updatePlaying() {
        if (nowTitle == null) return;
        MediaCatalog.Entry entry = MediaCatalog.selected(this);
        boolean selected = entry != null;
        if (selected != lastSelected) {
            playbackControls.setVisibility(selected?View.VISIBLE:View.GONE);
            lastSelected = selected;
        }
        nowTitle.setText(selected?MediaCatalog.displayTitle(entry.title):"Фильм не выбран");
        PlaybackForegroundService s = PlaybackForegroundService.active();
        boolean connected=prefs().getBoolean("surface_connected",false);
        nowStatus.setText(!selected?"Выбери фильм в списке ниже" :
                s!=null&&s.playing()?"▶ Играет на Geely" :
                connected?"Готов к воспроизведению на Geely":"Выбран для Geely");
        if (selected) {
            long pos = s!=null?s.position():prefs().getLong(
                    PlaybackForegroundService.positionKey(Uri.parse(entry.uri)),0);
            long duration = s!=null?s.duration():0;
            nowTime.setText(MediaCatalog.time(pos)+" / "+
                    (duration>0?MediaCatalog.time(duration):"--:--"));
            if(!dragging && duration>0)
                timeline.setProgress((int)Math.min(1000,pos*1000/duration));
        }
        float speed = prefs().getFloat("playback_rate",1f);
        rateButton.setText(speed==1f?"1×":String.format(Locale.US,"%s×",speed));
        pictureButton.setText("fit".equals(prefs().getString("picture_mode","fill"))?
                "Целиком":"Заполнить");
    }

    @Override protected void onResume() {
        super.onResume();
        renderCatalog();
        updatePlaying();
        ui.removeCallbacks(refresh);
        ui.post(refresh);
    }

    @Override protected void onPause() {
        ui.removeCallbacks(refresh);
        super.onPause();
    }
}
