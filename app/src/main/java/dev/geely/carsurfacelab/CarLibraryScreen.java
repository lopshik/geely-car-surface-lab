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

/** A genuinely selectable catalog on the large car display; paging respects host limits. */
public final class CarLibraryScreen extends Screen {
    private int page;
    public CarLibraryScreen(@NonNull CarContext context) { super(context); }

    @NonNull @Override public Template onGetTemplate() {
        CarContext context = getCarContext();
        List<MediaCatalog.Entry> entries = MediaCatalog.all(context);
        int hostLimit = context.getCarService(ConstraintManager.class)
                .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_GRID);
        // Reserve one tile for navigation on hosts with limited template sizes.
        int perPage = Math.max(2, Math.min(8, hostLimit > 0 ? hostLimit - 1 : 5));
        int pages = Math.max(1, (entries.size() + perPage - 1) / perPage);
        page = Math.max(0, Math.min(page, pages - 1));
        ItemList.Builder list = new ItemList.Builder();
        int start = page * perPage;
        int stop = Math.min(entries.size(), start + perPage);
        for (int i = start; i < stop; i++) {
            MediaCatalog.Entry entry = entries.get(i);
            CarIcon icon = new CarIcon.Builder(
                    IconCompat.createWithBitmap(MediaCatalog.poster(entry))).build();
            GridItem item = new GridItem.Builder()
                    .setTitle(entry.title.length() > 40 ? entry.title.substring(0, 39) + "…" : entry.title)
                    .setImage(icon, GridItem.IMAGE_TYPE_LARGE)
                    .setOnClickListener(() -> open(entry))
                    .build();
            list.addItem(item);
        }
        if (entries.isEmpty()) {
            list.addItem(new GridItem.Builder()
                    .setTitle("Добавь фильмы на Xiaomi")
                    .setImage(new CarIcon.Builder(IconCompat.createWithBitmap(
                            MediaCatalog.poster(new MediaCatalog.Entry("", "Открой CarVideo", "", 0)))).build(),
                            GridItem.IMAGE_TYPE_LARGE)
                    .build());
        }
        if (page + 1 < pages) {
            list.addItem(new GridItem.Builder()
                    .setTitle("Следующая страница")
                    .setImage(new CarIcon.Builder(IconCompat.createWithBitmap(
                            MediaCatalog.poster(new MediaCatalog.Entry("", "Далее", "", 0)))).build(),
                            GridItem.IMAGE_TYPE_LARGE)
                    .setOnClickListener(() -> { page++; invalidate(); })
                    .build());
        }
        return new GridTemplate.Builder()
                .setTitle("Видеотека " + (page + 1) + "/" + pages)
                .setHeaderAction(Action.BACK)
                .setSingleList(list.build()).build();
    }

    private void open(MediaCatalog.Entry entry) {
        CarContext c = getCarContext();
        // Request the service from a direct car-screen user interaction.
        // Some Android 16 builds may reject background FGS starts: report and
        // leave the selection intact for manual service activation on Xiaomi.
        if (PlaybackForegroundService.active() == null) {
            MediaCatalog.prefs(c).edit().putBoolean("await_audio_service", true).apply();
            try {
                ContextCompat.startForegroundService(c,
                        new Intent(c, PlaybackForegroundService.class));
            } catch (RuntimeException ex) {
                MediaCatalog.prefs(c).edit().putBoolean("await_audio_service", false)
                        .putString("audio_diagnostics", "Car selection service start: " +
                                ex.getClass().getSimpleName()).apply();
                Log.w("CarVideo.Library", "Audio service could not start from car", ex);
                CarToast.makeText(c, "Открой CarVideo на телефоне для звука",
                        CarToast.LENGTH_LONG).show();
            }
        }
        MediaCatalog.choose(c, entry);
        getScreenManager().pop();
    }
}
