package dev.geely.carsurfacelab;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;

/** CarVideo: one folder, a small movie list, and a compact playback remote. */
public final class MainActivity extends Activity {
    private static final int CHOOSE_FOLDER = 101;
    private static final int BG = 0xFF09121D, PANEL = 0xFF142235,
            TEXT = 0xFFF4F8FF, MUTED = 0xFF9AAEC3, ACCENT = 0xFF55D5FF;

    private TextView nowTitle, nowTime, nowStatus, folderName, libraryStatus;
    private TextView rateButton, pictureButton, moreButton;
    private LinearLayout catalog;
    private SeekBar timeline;
    private int shown = 30;
    private boolean dragging;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            updatePlaying();
            ui.postDelayed(this, 1200);
        }
    };

    private int dp(int d) { return Math.round(d*getResources().getDisplayMetrics().density); }

    private GradientDrawable background(int color,int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        return d;
    }

    private GradientDrawable gradient() {
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF21435C,0xFF122438});
        d.setCornerRadius(dp(22));
        return d;
    }

    private TextView txt(String value,int size,int color,boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextColor(color);
        t.setTextSize(size);
        if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        return t;
    }

    private TextView btn(String name,boolean accent,View.OnClickListener click) {
        TextView t = txt(name,14,accent?BG:TEXT,true);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(dp(48));
        t.setPadding(dp(12),dp(10),dp(12),dp(10));
        t.setBackground(background(accent?ACCENT:0xFF203245,13));
        t.setOnClickListener(click);
        t.setFocusable(true);
        return t;
    }

    private LinearLayout horizontal() {
        LinearLayout line=new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        return line;
    }

    private LinearLayout.LayoutParams equal(int weight) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,dp(48),weight);
        p.setMargins(dp(3),0,dp(3),0);
        return p;
    }

    private LinearLayout.LayoutParams margin(int top,int bottom) {
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);
        p.setMargins(0,dp(top),0,dp(bottom));
        return p;
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        ScrollView scroll=new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18),dp(20),dp(18),dp(28));
        scroll.addView(root);
        setContentView(scroll);

        LinearLayout header=horizontal();
        TextView title=txt("CarVideo",31,TEXT,true);
        header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        header.addView(btn("Лог",false,v ->
                startActivity(new Intent(this,DiagnosticsActivity.class))));
        root.addView(header,margin(0,16));

        LinearLayout hero=new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(dp(18),dp(18),dp(18),dp(15));
        hero.setBackground(gradient());
        root.addView(hero,margin(0,20));
        nowTitle=txt("Выбери фильм",22,TEXT,true);
        nowTitle.setMaxLines(2);
        hero.addView(nowTitle);
        nowStatus=txt("Готово к просмотру",13,MUTED,false);
        nowStatus.setPadding(0,dp(6),0,dp(13));
        hero.addView(nowStatus);

        timeline=new SeekBar(this);
        timeline.setMax(1000);
        timeline.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        timeline.setThumbTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        timeline.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b,int progress,boolean user){}
            @Override public void onStartTrackingTouch(SeekBar b){dragging=true;}
            @Override public void onStopTrackingTouch(SeekBar b){
                dragging=false;
                PlaybackForegroundService service=PlaybackForegroundService.active();
                if(service!=null && service.duration()>0)
                    service.seekTo(service.duration()*b.getProgress()/1000);
            }
        });
        hero.addView(timeline);
        nowTime=txt("00:00 / --:--",13,MUTED,false);
        hero.addView(nowTime);

        LinearLayout controls=horizontal();
        controls.setPadding(0,dp(13),0,0);
        controls.addView(btn("−10 с",false,v -> jump(-10000)),equal(1));
        controls.addView(btn("▶ / Ⅱ",true,v -> {
            PlaybackForegroundService service=PlaybackForegroundService.active();
            if(service!=null)service.toggle();
            else Toast.makeText(this,"Запусти фильм на экране автомобиля",Toast.LENGTH_SHORT).show();
            updatePlaying();
        }),equal(2));
        controls.addView(btn("+10 с",false,v -> jump(10000)),equal(1));
        hero.addView(controls);

        LinearLayout modes=horizontal();
        modes.setPadding(0,dp(11),0,0);
        rateButton=btn("1×",false,v -> cycleRate());
        pictureButton=btn("Заполнить",false,v -> cyclePicture());
        modes.addView(rateButton,equal(1));
        modes.addView(pictureButton,equal(2));
        hero.addView(modes);

        root.addView(txt("Видео",22,TEXT,true),margin(4,11));
        LinearLayout actions=horizontal();
        actions.addView(btn("Выбрать папку",true,v -> pickFolder()),equal(2));
        actions.addView(btn("Обновить",false,v -> scanFolder()),equal(1));
        root.addView(actions);
        folderName=txt(MediaCatalog.folderLabel(this),14,MUTED,false);
        folderName.setPadding(dp(5),dp(12),0,0);
        root.addView(folderName);
        libraryStatus=txt("",12,MUTED,false);
        libraryStatus.setPadding(dp(5),dp(5),0,dp(8));
        root.addView(libraryStatus);

        catalog=new LinearLayout(this);
        catalog.setOrientation(LinearLayout.VERTICAL);
        root.addView(catalog);
        moreButton=btn("Показать остальные",false,v -> {
            shown=Math.min(250,shown+30); renderCatalog();
        });
        root.addView(moreButton,margin(8,0));
        TextView foot=txt("Просмотр на автомобиле — только на стоянке",11,MUTED,false);
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(0,dp(22),0,0);
        root.addView(foot);
        renderCatalog();
        updatePlaying();
    }

    private SharedPreferences prefs(){return getSharedPreferences("lab",MODE_PRIVATE);}

    private void pickFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent,CHOOSE_FOLDER);
    }

    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request!=CHOOSE_FOLDER || result!=RESULT_OK || data==null || data.getData()==null)return;
        Uri uri=data.getData();
        try {
            getContentResolver().takePersistableUriPermission(
                    uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch(SecurityException e){
            Toast.makeText(this,"Разрешение на папку не сохранено",Toast.LENGTH_LONG).show();
            return;
        }
        prefs().edit().putString("video_folder_uri",uri.toString()).apply();
        shown=30;
        folderName.setText(MediaCatalog.folderLabel(this));
        scanFolder();
    }

    private void scanFolder() {
        if(prefs().getString("video_folder_uri",null)==null) {
            pickFolder();
            return;
        }
        libraryStatus.setText("Обновление списка…");
        new Thread(() -> {
            int count=MediaCatalog.scanFolder(getApplicationContext());
            runOnUiThread(() -> {
                if(isFinishing()||isDestroyed())return;
                if(count>=0)libraryStatus.setText(count+" видео");
                else libraryStatus.setText(
                        prefs().getString("catalog_error","Нет доступа к папке"));
                renderCatalog();
            });
        },"CarVideo-folder-scan").start();
    }

    private void renderCatalog() {
        if(catalog==null)return;
        catalog.removeAllViews();
        List<MediaCatalog.Entry> files=MediaCatalog.all(this);
        if(moreButton!=null)
            moreButton.setVisibility(files.size()>shown?View.VISIBLE:View.GONE);
        if(libraryStatus!=null && prefs().getString("catalog_error",null)==null)
            libraryStatus.setText(files.size()+" видео");
        if(files.isEmpty()) {
            TextView empty=txt("Выбери папку с фильмами",15,MUTED,false);
            empty.setPadding(dp(6),dp(20),dp(6),dp(20));
            catalog.addView(empty);
            return;
        }
        String selected=prefs().getString("video_uri","");
        for(int i=0;i<Math.min(shown,files.size());i++) {
            MediaCatalog.Entry movie=files.get(i);
            LinearLayout row=horizontal();
            row.setPadding(dp(10),dp(9),dp(12),dp(9));
            row.setBackground(background(movie.uri.equals(selected)?0xFF224457:PANEL,14));
            catalog.addView(row,margin(4,3));

            ImageView cover=new ImageView(this);
            cover.setImageBitmap(MediaCatalog.poster(this,movie));
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cover.setTag(movie.uri);
            LinearLayout.LayoutParams image=new LinearLayout.LayoutParams(dp(68),dp(68));
            image.setMargins(0,0,dp(13),0);
            row.addView(cover,image);
            MediaCatalog.preparePoster(this,movie,bitmap -> {
                if(movie.uri.equals(cover.getTag())) cover.setImageBitmap(bitmap);
            });

            TextView name=txt(movie.title,16,TEXT,true);
            name.setSingleLine(false);
            name.setMaxLines(2);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(name,new LinearLayout.LayoutParams(0,-2,1));
            TextView play=txt("▶",20,ACCENT,true);
            play.setPadding(dp(12),0,0,0);
            row.addView(play);
            row.setOnClickListener(v -> {
                if(prefs().getBoolean("surface_connected",false) &&
                        PlaybackForegroundService.active()==null)startAudio();
                MediaCatalog.choose(this,movie);
                renderCatalog();
                updatePlaying();
            });
        }
    }

    private void startAudio(){
        try {
            if(Build.VERSION.SDK_INT>=26)
                startForegroundService(new Intent(this,PlaybackForegroundService.class));
            else startService(new Intent(this,PlaybackForegroundService.class));
        } catch(RuntimeException e){
            prefs().edit().putString("audio_diagnostics",
                    "Start service: "+e.getClass().getSimpleName()).apply();
        }
    }

    private void jump(long amount){
        PlaybackForegroundService s=PlaybackForegroundService.active();
        if(s!=null)s.seekTo(s.position()+amount);
        updatePlaying();
    }

    private void cycleRate(){
        float[] rates={0.75f,1f,1.25f,1.5f,2f};
        float current=prefs().getFloat("playback_rate",1f);
        int idx=1;
        for(int i=0;i<rates.length;i++)
            if(Math.abs(current-rates[i])<0.01f){idx=i;break;}
        float next=rates[(idx+1)%rates.length];
        PlaybackForegroundService service=PlaybackForegroundService.active();
        if(service!=null)service.changeRate(next);
        else prefs().edit().putFloat("playback_rate",next).apply();
        updatePlaying();
    }

    private void cyclePicture(){
        String current=prefs().getString("picture_mode","fill");
        String next="fit".equals(current)?"fill":"fit";
        PlaybackForegroundService service=PlaybackForegroundService.active();
        if(service!=null)service.changePicture(next);
        else prefs().edit().putString("picture_mode",next).apply();
        updatePlaying();
    }

    private void updatePlaying(){
        if(nowTitle==null)return;
        MediaCatalog.Entry entry=MediaCatalog.selected(this);
        nowTitle.setText(entry==null?"Выбери фильм":entry.title);
        PlaybackForegroundService service=PlaybackForegroundService.active();
        boolean connected=prefs().getBoolean("surface_connected",false);
        nowStatus.setText(service!=null&&service.playing()?"▶ Воспроизведение" :
                connected?"На паузе / готово":"Готово");
        long pos=service!=null?service.position():prefs().getLong("last_position_ms",0);
        long duration=service!=null?service.duration():0;
        nowTime.setText(MediaCatalog.time(pos)+" / "+
                (duration>0?MediaCatalog.time(duration):"--:--"));
        if(!dragging && duration>0)
            timeline.setProgress((int)Math.min(1000,pos*1000/duration));
        float rate=prefs().getFloat("playback_rate",1f);
        rateButton.setText(rate==1f?"1×":String.format(Locale.US,"%s×",rate));
        pictureButton.setText("fit".equals(prefs().getString("picture_mode","fill")) ?
                "Целиком" : "Заполнить");
    }

    @Override protected void onResume(){
        super.onResume();
        renderCatalog();
        updatePlaying();
        ui.removeCallbacks(refresh);
        ui.post(refresh);
    }
    @Override protected void onPause(){
        ui.removeCallbacks(refresh);
        super.onPause();
    }
}
