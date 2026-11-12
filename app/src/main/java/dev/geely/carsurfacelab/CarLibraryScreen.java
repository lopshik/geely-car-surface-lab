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

import java.util.List;

/**
 * Text-first, clean list for Geely. The previous GridTemplate cut off film
 * titles (especially sequels) beneath giant square thumbnails.
 * One secondary line is allowed to wrap to two lines in ListTemplate.
 */
public final class CarLibraryScreen extends Screen {
    private int page;
    public CarLibraryScreen(@NonNull CarContext context) { super(context); }

    private static String[] splitName(String title) {
        String name = title == null || title.trim().isEmpty() ? "Видео" : title.trim();
        if (name.length() <= 34) return new String[]{name, ""};
        int cut = name.lastIndexOf(' ', 34);
        if (cut < 15) cut = 34;
        return new String[]{name.substring(0, cut).trim(),
                name.substring(cut).trim()};
    }

    @NonNull @Override public Template onGetTemplate() {
        CarContext c = getCarContext();
        List<MediaCatalog.Entry> movies = MediaCatalog.all(c);
        int limit = c.getCarService(ConstraintManager.class)
                .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST);
        int capacity = Math.max(2, Math.min(12, limit > 0 ? limit : 6));
        int filmsPerPage = Math.max(1, capacity-1);
        int pages = Math.max(1, (movies.size()+filmsPerPage-1)/filmsPerPage);
        page = Math.max(0,Math.min(page,pages-1));

        ItemList.Builder list = new ItemList.Builder();
        int start=page*filmsPerPage;
        int end=Math.min(movies.size(),start+filmsPerPage);
        for (int i=start;i<end;i++) {
            MediaCatalog.Entry entry=movies.get(i);
            String[] name=splitName(MediaCatalog.displayTitle(entry.title));
            Row.Builder row = new Row.Builder().setTitle(name[0])
                    .setOnClickListener(() -> open(entry));
            if (!name[1].isEmpty()) {
                row.addText(name[1]);
            } else {
                String extra = MediaCatalog.subtitle(c, entry);
                if (!extra.isEmpty()) row.addText(extra);
            }
            list.addItem(row.build());
        }
        if (movies.isEmpty()) {
            list.addItem(new Row.Builder().setTitle("Фильмов пока нет")
                    .addText("Выбери папку на телефоне и обнови список")
                    .build());
        }
        if (page+1<pages) {
            list.addItem(new Row.Builder()
                    .setTitle("Следующая страница  ›")
                    .addText((page+2) + " / " + pages)
                    .setOnClickListener(() -> {page++;invalidate();})
                    .build());
        } else if (page>0) {
            list.addItem(new Row.Builder()
                    .setTitle("‹  Предыдущая страница")
                    .addText(page + " / " + pages)
                    .setOnClickListener(() -> {page--;invalidate();})
                    .build());
        }

        return new ListTemplate.Builder()
                .setTitle(pages==1?"Мои фильмы":"Мои фильмы · "+(page+1)+"/"+pages)
                .setHeaderAction(Action.BACK)
                .setSingleList(list.build())
                .build();
    }

    private void open(MediaCatalog.Entry entry) {
        CarContext c=getCarContext();
        if (PlaybackForegroundService.active()==null) {
            MediaCatalog.prefs(c).edit().putBoolean("await_audio_service",true).apply();
            try {
                ContextCompat.startForegroundService(c,
                        new Intent(c,PlaybackForegroundService.class));
            } catch (RuntimeException e) {
                MediaCatalog.prefs(c).edit().putBoolean("await_audio_service",false)
                        .putString("audio_diagnostics",
                                "Car selection service: "+e.getClass().getSimpleName()).apply();
                Log.w("CarVideo.List","FGS start refused by host",e);
                CarToast.makeText(c,"Лог на телефоне → Звук для ГУ",
                        CarToast.LENGTH_LONG).show();
            }
        }
        MediaCatalog.choose(c,entry);
        getScreenManager().popToRoot();
    }
}
