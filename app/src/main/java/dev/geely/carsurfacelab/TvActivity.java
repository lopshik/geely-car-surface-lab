package dev.geely.carsurfacelab;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** TV setup is PHONE-only; never asks the car host to display a keyboard. */
public final class TvActivity extends Activity {
    private static final int BG=0xFF09131F, PANEL=0xFF152637,
            TEXT=0xFFF4F8FD, MUTED=0xFF9DB1C2, ACCENT=0xFF57D0F2;
    private EditText url;
    private TextView info,groupButton,moreButton;
    private LinearLayout channels;
    private String group="Все";
    private int shown=45;

    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}

    private GradientDrawable background(int color) {
        GradientDrawable d=new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(12));
        return d;
    }

    private TextView label(String value,int font,boolean strong) {
        TextView v=new TextView(this);
        v.setText(value);
        v.setTextColor(TEXT);
        v.setTextSize(font);
        if(strong)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        return v;
    }

    private TextView button(String text, View.OnClickListener listener) {
        TextView v=label(text,14,true);
        v.setBackground(background(PANEL));
        v.setMinHeight(dp(44));
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(12),dp(8),dp(12),dp(8));
        v.setOnClickListener(listener);
        return v;
    }

    private LinearLayout horizontal() {
        LinearLayout r=new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private LinearLayout.LayoutParams spaced(int top,int bottom) {
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);
        p.setMargins(0,dp(top),0,dp(bottom));
        return p;
    }

    @Override public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        scroll.setOnApplyWindowInsetsListener((view,insets)->{
            int top,bottom;
            if(Build.VERSION.SDK_INT>=30) {
                android.graphics.Insets sys=insets.getInsets(
                        WindowInsets.Type.systemBars() |
                        WindowInsets.Type.displayCutout());
                top=sys.top;bottom=sys.bottom;
            }else{
                top=insets.getSystemWindowInsetTop();
                bottom=insets.getSystemWindowInsetBottom();
            }
            view.setPadding(dp(16),top+dp(12),dp(16),bottom+dp(22));
            return insets;
        });
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(root);
        setContentView(scroll);
        scroll.requestApplyInsets();

        TextView title=label("‹  Телевидение",24,true);
        title.setOnClickListener(v->finish());
        root.addView(title,spaced(0,12));
        TextView intro=label("Добавь до 8 ссылок M3U (по одной в строке)."+
                " Списки обновляются не чаще раза в 6 часов или вручную."+
                " У каждой подписки сохраняется последняя рабочая копия.",13,false);
        intro.setTextColor(MUTED);
        root.addView(intro,spaced(0,11));

        url=new EditText(this);
        url.setSingleLine(false);
        url.setMinLines(2);
        url.setMaxLines(5);
        url.setTextSize(14);
        url.setTextColor(TEXT);
        url.setHintTextColor(MUTED);
        url.setHint("Один URL M3U в каждой строке (до 8)");
        url.setInputType(InputType.TYPE_CLASS_TEXT |
                InputType.TYPE_TEXT_FLAG_MULTI_LINE |
                InputType.TYPE_TEXT_VARIATION_URI);
        url.setBackground(background(PANEL));
        url.setPadding(dp(13),dp(10),dp(13),dp(10));
        url.setText(TvCatalog.sourceUrl(this));
        root.addView(url,spaced(0,9));

        TextView loganet=button("Добавить LoganetTV: Детям + Кино",v -> {
            String kids="https://loganettv.github.io/playlists/kids.m3u";
            String movies="https://loganettv.github.io/playlists/movie.m3u";
            String before=url.getText().toString().trim();
            java.util.List<String> existing=new java.util.ArrayList<>();
            for(String line:before.split("[\\n;,]+")) {
                if(!line.trim().isEmpty() && !existing.contains(line.trim()))
                    existing.add(line.trim());
            }
            if(!existing.contains(kids))existing.add(kids);
            if(!existing.contains(movies))existing.add(movies);
            url.setText(android.text.TextUtils.join("\n",existing));
            saveUrl();
        });
        root.addView(loganet,spaced(0,9));

        LinearLayout actions=horizontal();
        TextView save=button("Сохранить URL",v->saveUrl());
        TextView refresh=button("Обновить",v->refreshNow());
        actions.addView(save,new LinearLayout.LayoutParams(0,dp(44),1.5f));
        LinearLayout.LayoutParams second=new LinearLayout.LayoutParams(0,dp(44),1);
        second.setMargins(dp(8),0,0,0);
        actions.addView(refresh,second);
        root.addView(actions);
        info=label("",12,false);
        info.setTextColor(MUTED);
        info.setPadding(dp(3),dp(10),dp(3),dp(12));
        root.addView(info);
        groupButton=button("Группа: Все  ▾",v->selectGroup());
        root.addView(groupButton,spaced(0,8));
        channels=new LinearLayout(this);
        channels.setOrientation(LinearLayout.VERTICAL);
        root.addView(channels);
        moreButton=button("Показать ещё",v->{
            shown=Math.min(1500,shown+45);render();
        });
        root.addView(moreButton,spaced(8,0));
        render();
    }

    private void saveUrl() {
        String proposed=url.getText().toString().trim();
        if(!TvCatalog.configure(this,proposed)) {
            Toast.makeText(this,"Нужен полный URL http:// или https://",
                    Toast.LENGTH_LONG).show();
            return;
        }
        group="Все";shown=45;
        refreshNow();
    }

    private void refreshNow() {
        info.setText("Обновляю список каналов…");
        TvCatalog.refresh(this,message->{
            if(isFinishing()||isDestroyed())return;
            info.setText(message);
            render();
        });
    }

    private void selectGroup() {
        PopupMenu menu=new PopupMenu(this,groupButton);
        menu.getMenu().add("Все");
        boolean hasFavorites=false;
        for(TvCatalog.Channel item:TvCatalog.all(this)) {
            if(TvCatalog.favorite(this,item)){hasFavorites=true;break;}
        }
        if(hasFavorites)menu.getMenu().add("★ Избранное");
        for(String name:TvCatalog.groups(this))menu.getMenu().add(name);
        menu.setOnMenuItemClickListener(item->{
            group=item.getTitle().toString();shown=45;render();return true;
        });
        menu.show();
    }

    private void selectChannel(TvCatalog.Channel c) {
        if(TvCatalog.isTvSelected(this) &&
                c.id.equals(TvCatalog.prefs(this).getString("tv_channel_id",""))) {
            Toast.makeText(this,"Этот канал уже выбран",Toast.LENGTH_SHORT).show();
        }
        if(TvCatalog.prefs(this).getBoolean("surface_connected",false) &&
                PlaybackForegroundService.active()==null) {
            try {
                Intent intent=new Intent(this,PlaybackForegroundService.class);
                if(Build.VERSION.SDK_INT>=26)startForegroundService(intent);
                else startService(intent);
            }catch(RuntimeException e) {
                Toast.makeText(this,"Открой Лог → Звук для ГУ",
                        Toast.LENGTH_LONG).show();
            }
        }
        TvCatalog.select(this,c);
        Toast.makeText(this,"Канал: "+c.name+" · открой CarVideo на ГУ",
                Toast.LENGTH_SHORT).show();
        render();
    }

    private void render() {
        if(channels==null)return;
        channels.removeAllViews();
        info.setText(TvCatalog.status(this));
        groupButton.setText("Группа: "+group+"  ▾");
        List<TvCatalog.Channel> list;
        if("Все".equals(group)) list=TvCatalog.all(this);
        else list=TvCatalog.inGroup(this,group);
        moreButton.setVisibility(list.size()>shown?View.VISIBLE:View.GONE);
        if(list.isEmpty()){
            TextView empty=label("Список пуст. Добавь URL M3U и обнови каналы.",15,false);
            empty.setTextColor(MUTED);
            empty.setPadding(0,dp(20),0,dp(20));
            channels.addView(empty);
            return;
        }
        String current=TvCatalog.prefs(this).getString("tv_channel_id","");
        for(int i=0;i<Math.min(shown,list.size());i++){
            TvCatalog.Channel channel=list.get(i);
            LinearLayout item=horizontal();
            item.setPadding(dp(10),dp(7),dp(6),dp(7));
            item.setBackground(background(channel.id.equals(current)?0xFF22455A:PANEL));
            channels.addView(item,spaced(3,3));
            LinearLayout details=new LinearLayout(this);
            details.setOrientation(LinearLayout.VERTICAL);
            TextView name=label(channel.name,15,true);
            name.setMaxLines(2);
            details.addView(name);
            TextView category=label(channel.group,11,false);
            category.setTextColor(MUTED);
            category.setPadding(0,dp(3),0,0);
            details.addView(category);
            item.addView(details,new LinearLayout.LayoutParams(0,dp(54),1));
            TextView star=label(TvCatalog.favorite(this,channel)?"★":"☆",24,false);
            star.setTextColor(ACCENT);
            star.setGravity(Gravity.CENTER);
            star.setContentDescription("Избранное: "+channel.name);
            star.setOnClickListener(v->{TvCatalog.toggleFavorite(this,channel);render();});
            item.addView(star,new LinearLayout.LayoutParams(dp(43),dp(48)));
            TextView play=label("▶",19,true);
            play.setTextColor(ACCENT);
            play.setGravity(Gravity.CENTER);
            item.addView(play,new LinearLayout.LayoutParams(dp(38),dp(48)));
            item.setOnClickListener(v->selectChannel(channel));
        }
    }

    @Override public void onResume() {
        super.onResume();
        if(!TvCatalog.sourceUrl(this).isEmpty()) {
            TvCatalog.refreshIfStale(this,message->{
                if(!isFinishing()&&!isDestroyed()){
                    if(info!=null)info.setText(message);
                    render();
                }
            });
        }
    }
}
