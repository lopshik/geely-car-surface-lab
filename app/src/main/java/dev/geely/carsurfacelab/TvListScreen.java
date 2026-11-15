package dev.geely.carsurfacelab;

import android.content.Intent;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.car.app.CarContext;
import androidx.car.app.CarToast;
import androidx.car.app.Screen;
import androidx.car.app.constraints.ConstraintManager;
import androidx.car.app.model.Action;
import androidx.car.app.model.ItemList;
import androidx.car.app.model.ListTemplate;
import androidx.car.app.model.Row;
import androidx.car.app.model.Template;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * Android Auto TV browser: compact group -> channels list.
 * It never makes network requests from onGetTemplate (car host binder).
 */
public final class TvListScreen extends Screen {
    private String group=null;
    private int page;
    private boolean refreshRequested;
    private final Runnable refreshNow = () -> {
        if(refreshRequested)return;
        refreshRequested=true;
        TvCatalog.refreshIfStale(getCarContext(),result->{
            refreshRequested=false;
            // Host templates have change quotas; refresh only once following a
            // completed fetch, not on every playlist row or video frame.
            invalidate();
        });
    };

    public TvListScreen(@NonNull CarContext context) {
        super(context);
        new android.os.Handler(android.os.Looper.getMainLooper()).post(refreshNow);
    }

    private int contentLimit() {
        int n=getCarContext().getCarService(ConstraintManager.class)
                .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST);
        return Math.max(2,Math.min(12,n>0?n:6));
    }

    private static String[] twoLines(String raw) {
        String text=raw==null||raw.trim().isEmpty()?"Канал":raw.trim();
        if(text.length()<=38)return new String[]{text,""};
        int at=text.lastIndexOf(' ',38);
        if(at<18)at=38;
        return new String[]{text.substring(0,at).trim(),text.substring(at).trim()};
    }

    private void choose(TvCatalog.Channel channel) {
        CarContext context=getCarContext();
        if(PlaybackForegroundService.active()==null) {
            MediaCatalog.prefs(context).edit().putBoolean("await_audio_service",true).apply();
            try{
                ContextCompat.startForegroundService(context,
                        new Intent(context,PlaybackForegroundService.class));
            }catch(RuntimeException e){
                MediaCatalog.prefs(context).edit().putBoolean("await_audio_service",false)
                        .putString("audio_diagnostics",
                                "TV foreground service unavailable: "+e.getClass().getSimpleName()).apply();
                Log.w("CarVideo.TV","Cannot start service",e);
                CarToast.makeText(context,"На телефоне: Лог → Звук для ГУ",
                        CarToast.LENGTH_LONG).show();
            }
        }
        TvCatalog.select(context,channel);
        getScreenManager().popToRoot();
    }

    @NonNull @Override public Template onGetTemplate() {
        CarContext context=getCarContext();
        ItemList.Builder rows=new ItemList.Builder();
        int capacity=contentLimit();
        List<String> categories=TvCatalog.groups(context);
        if(group==null){
            List<String> entries=new ArrayList<>();
            if(!TvCatalog.inGroup(context,"★ Избранное").isEmpty())
                entries.add("★ Избранное");
            if(categories.size()<=1){
                entries.add("Все каналы");
            }else{
                entries.addAll(categories);
                entries.add("Все каналы");
            }
            int perPage=Math.max(1,capacity-1);
            int maxPages=Math.max(1,(entries.size()+perPage-1)/perPage);
            page=Math.min(page,maxPages-1);
            int start=page*perPage,end=Math.min(entries.size(),start+perPage);
            for(int i=start;i<end;i++){
                String name=entries.get(i);
                int count="Все каналы".equals(name)?TvCatalog.all(context).size():
                        TvCatalog.inGroup(context,name).size();
                rows.addItem(new Row.Builder().setTitle(name)
                        .addText(count+" каналов")
                        .setOnClickListener(()->{group=name;page=0;invalidate();})
                        .build());
            }
            if(end<entries.size())rows.addItem(new Row.Builder()
                    .setTitle("Следующая страница ›")
                    .setOnClickListener(()->{page++;invalidate();}).build());
            else if(page>0)rows.addItem(new Row.Builder()
                    .setTitle("‹ Предыдущая страница")
                    .setOnClickListener(()->{page--;invalidate();}).build());
        } else {
            List<TvCatalog.Channel> all="Все каналы".equals(group)?
                    TvCatalog.all(context):TvCatalog.inGroup(context,group);
            // Reserve one row for back to groups and one for paging.
            int perPage=Math.max(1,capacity-2);
            int maxPages=Math.max(1,(all.size()+perPage-1)/perPage);
            page=Math.min(page,maxPages-1);
            rows.addItem(new Row.Builder().setTitle("‹ К группам")
                    .setOnClickListener(()->{group=null;page=0;invalidate();}).build());
            int start=page*perPage,end=Math.min(all.size(),start+perPage);
            for(int i=start;i<end;i++){
                TvCatalog.Channel channel=all.get(i);
                String[] name=twoLines(channel.name);
                Row.Builder row=new Row.Builder().setTitle(name[0]);
                row.addText(name[1].isEmpty()?channel.group:name[1]);
                row.setOnClickListener(()->choose(channel));
                rows.addItem(row.build());
            }
            if(end<all.size())rows.addItem(new Row.Builder().setTitle("Следующие каналы ›")
                    .setOnClickListener(()->{page++;invalidate();}).build());
            else if(page>0)rows.addItem(new Row.Builder().setTitle("‹ Предыдущие каналы")
                    .setOnClickListener(()->{page--;invalidate();}).build());
        }
        if(TvCatalog.all(context).isEmpty()){
            rows=new ItemList.Builder();
            rows.addItem(new Row.Builder().setTitle("Плейлист пока не настроен")
                    .addText("Добавь URL M3U на телефоне → Телевидение").build());
        }
        return new ListTemplate.Builder()
                .setTitle("ТВ"+(group==null?"":": "+group))
                .setHeaderAction(Action.BACK)
                .setSingleList(rows.build())
                .build();
    }
}
