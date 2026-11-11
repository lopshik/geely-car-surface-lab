package dev.geely.carsurfacelab;

import android.content.Intent;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.car.app.CarContext;
import androidx.car.app.CarToast;
import androidx.car.app.Screen;
import androidx.car.app.constraints.ConstraintManager;
import androidx.car.app.model.Action;
import androidx.car.app.model.CarIcon;
import androidx.car.app.model.GridItem;
import androidx.car.app.model.GridTemplate;
import androidx.car.app.model.ItemList;
import androidx.car.app.model.Template;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.IconCompat;

import java.util.List;

/**
 * Small, direct movie grid on the car screen. No favorites, search or folder
 * hierarchies. Keeps the v0.6 playback/Surface lifecycle unchanged.
 */
public final class CarLibraryScreen extends Screen {
    private int page;
    public CarLibraryScreen(@NonNull CarContext context) { super(context); }

    private CarIcon icon(MediaCatalog.Entry entry) {
        return new CarIcon.Builder(
                IconCompat.createWithBitmap(MediaCatalog.poster(getCarContext(), entry))).build();
    }

    private void tile(ItemList.Builder list, String title, CarIcon icon, Runnable callback) {
        GridItem.Builder b = new GridItem.Builder()
                .setTitle(title).setImage(icon, GridItem.IMAGE_TYPE_LARGE);
        if (callback != null) b.setOnClickListener(callback::run);
        list.addItem(b.build());
    }

    @NonNull @Override public Template onGetTemplate() {
        CarContext c = getCarContext();
        List<MediaCatalog.Entry> movies = MediaCatalog.all(c);
        int hostLimit = c.getCarService(ConstraintManager.class)
                .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_GRID);
        int capacity = Math.max(2, Math.min(8, hostLimit > 0 ? hostLimit : 6));
        // Reserve one place for paging, but fill the entire grid when it fits.
        int perPage = movies.size() <= capacity ? capacity : Math.max(1, capacity - 1);
        int pages = Math.max(1, (movies.size() + perPage - 1) / perPage);
        page = Math.min(page, pages - 1);
        ItemList.Builder list = new ItemList.Builder();
        int start = page * perPage;
        int end = Math.min(movies.size(), start + perPage);
        for (int i = start; i < end; i++) {
            MediaCatalog.Entry entry = movies.get(i);
            String title = entry.title.length() > 42
                    ? entry.title.substring(0, 41) + "…" : entry.title;
            tile(list, title, icon(entry), () -> open(entry));
        }
        if (movies.isEmpty()) {
            MediaCatalog.Entry empty = new MediaCatalog.Entry("", "Добавь видео на Xiaomi", "", 0);
            tile(list, "Добавь видео на Xiaomi", icon(empty), null);
        }
        if (page + 1 < pages) {
            MediaCatalog.Entry next = new MediaCatalog.Entry("", "Следующие", "", 0);
            tile(list, "Далее ›", icon(next), () -> { page++; invalidate(); });
        } else if (page > 0) {
            MediaCatalog.Entry prev = new MediaCatalog.Entry("", "Предыдущие", "", 0);
            tile(list, "‹ Назад", icon(prev), () -> { page--; invalidate(); });
        }

        return new GridTemplate.Builder()
                .setTitle(pages == 1 ? "Мои видео" : "Мои видео " + (page + 1) + "/" + pages)
                .setHeaderAction(Action.BACK)
                .setSingleList(list.build()).build();
    }

    private void open(MediaCatalog.Entry entry) {
        CarContext c = getCarContext();
        if (PlaybackForegroundService.active() == null) {
            MediaCatalog.prefs(c).edit().putBoolean("await_audio_service", true).apply();
            try {
                ContextCompat.startForegroundService(c,
                        new Intent(c, PlaybackForegroundService.class));
            } catch (RuntimeException ex) {
                MediaCatalog.prefs(c).edit().putBoolean("await_audio_service", false)
                        .putString("audio_diagnostics",
                                "Car selection service start: " + ex.getClass().getSimpleName()).apply();
                Log.w("CarVideo.Library", "Audio foreground service not available", ex);
                CarToast.makeText(c, "Включи звук для ГУ на Xiaomi",
                        CarToast.LENGTH_LONG).show();
            }
        }
        MediaCatalog.choose(c, entry);
        getScreenManager().popToRoot();
    }
}
