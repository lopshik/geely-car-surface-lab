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

/** Android Auto-compatible paged library; the movie Surface remains owned by VideoSurfaceScreen. */
public final class CarLibraryScreen extends Screen {
    private int page;
    private final String folder;
    private final boolean favoritesOnly;

    public CarLibraryScreen(@NonNull CarContext context) { this(context, null, false); }
    public CarLibraryScreen(@NonNull CarContext context, String folder, boolean favoritesOnly) {
        super(context);
        this.folder = folder;
        this.favoritesOnly = favoritesOnly;
    }

    private String title() {
        return favoritesOnly ? "★ Избранное" : folder != null ? folder : "Все видео";
    }

    private CarIcon icon(String name) {
        MediaCatalog.Entry sample = new MediaCatalog.Entry("", name, "", 0);
        return new CarIcon.Builder(IconCompat.createWithBitmap(MediaCatalog.poster(sample))).build();
    }

    private void tile(ItemList.Builder list, String name, CarIcon icon, Runnable onClick) {
        GridItem.Builder item = new GridItem.Builder()
                .setTitle(name).setImage(icon, GridItem.IMAGE_TYPE_LARGE);
        if (onClick != null) item.setOnClickListener(onClick::run);
        list.addItem(item.build());
    }

    @NonNull @Override public Template onGetTemplate() {
        CarContext context = getCarContext();
        List<MediaCatalog.Entry> entries =
                MediaCatalog.filtered(context, favoritesOnly, folder, null);
        int hostLimit = context.getCarService(ConstraintManager.class)
                .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_GRID);
        int capacity = Math.max(2, Math.min(8, hostLimit > 0 ? hostLimit : 6));
        boolean root = !favoritesOnly && folder == null;
        boolean categories = root && capacity >= 4;
        // Reserve space for Next button and category entries, including when there are
        // hundreds of movies. Never exceed the host's declared grid-item limit.
        int perPage = Math.max(1, capacity - 1 - (categories ? 2 : 0));
        int pages = Math.max(1, (entries.size() + perPage - 1) / perPage);
        page = Math.max(0, Math.min(page, pages - 1));

        ItemList.Builder list = new ItemList.Builder();
        if (categories && page == 0) {
            tile(list, "★ Избранное", icon("Избранное"),
                    () -> getScreenManager().push(
                            new CarLibraryScreen(getCarContext(), null, true)));
            tile(list, "📁 Папки", icon("Папки"),
                    () -> getScreenManager().push(new CarFoldersScreen(getCarContext())));
        }
        int start = page * perPage;
        int end = Math.min(entries.size(), start + perPage);
        for (int i = start; i < end; i++) {
            MediaCatalog.Entry entry = entries.get(i);
            CarIcon preview = new CarIcon.Builder(
                    IconCompat.createWithBitmap(MediaCatalog.poster(context, entry))).build();
            tile(list, entry.title.length() > 42 ? entry.title.substring(0, 41) + "…" : entry.title,
                    preview, () -> open(entry));
        }
        if (entries.isEmpty() && (!categories || !root)) {
            tile(list, favoritesOnly ? "Избранное пока пусто" :
                    "Добавь фильмы на Xiaomi", icon("Нет фильмов"), null);
        }
        if (page + 1 < pages) {
            tile(list, "Далее ›", icon("Далее"), () -> {
                page++;
                invalidate();
            });
        } else if (page > 0) {
            tile(list, "‹ Назад", icon("Назад"), () -> {
                page--;
                invalidate();
            });
        }
        return new GridTemplate.Builder()
                .setTitle(title() + " " + (page + 1) + "/" + pages)
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
                        .putString("audio_diagnostics", "Car selection service start: " +
                                ex.getClass().getSimpleName()).apply();
                Log.w("CarVideo.Library", "Foreground service start refused", ex);
                CarToast.makeText(c, "Включи «Звук для ГУ» на телефоне",
                        CarToast.LENGTH_LONG).show();
            }
        }
        MediaCatalog.choose(c, entry);
        // Pop all the library screens that we created, so returning from a folder
        // goes to the established NavigationTemplate and the video Surface.
        getScreenManager().popToRoot();
    }
}
