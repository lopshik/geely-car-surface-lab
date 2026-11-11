package dev.geely.carsurfacelab;

import androidx.annotation.NonNull;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.constraints.ConstraintManager;
import androidx.car.app.model.Action;
import androidx.car.app.model.ItemList;
import androidx.car.app.model.ListTemplate;
import androidx.car.app.model.Row;
import androidx.car.app.model.Template;

import java.util.List;

/** One compact list level; respects host template depth and item limits. */
public final class CarFoldersScreen extends Screen {
    private int page;
    public CarFoldersScreen(@NonNull CarContext c) { super(c); }

    @NonNull @Override public Template onGetTemplate() {
        List<String> folders = MediaCatalog.folders(getCarContext());
        int hostLimit = getCarContext().getCarService(ConstraintManager.class)
                .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST);
        int capacity = Math.max(2, Math.min(12, hostLimit > 0 ? hostLimit : 6));
        int perPage = Math.max(1, capacity - 1);
        int pages = Math.max(1, (folders.size() + perPage - 1) / perPage);
        page = Math.min(page, pages - 1);
        ItemList.Builder list = new ItemList.Builder();
        int end = Math.min(folders.size(), (page + 1) * perPage);
        for (int i = page * perPage; i < end; i++) {
            String folder = folders.get(i);
            int count = MediaCatalog.folderCount(getCarContext(), folder);
            list.addItem(new Row.Builder()
                    .setTitle(folder)
                    .addText(count + " видео")
                    .setOnClickListener(() -> getScreenManager().push(
                            new CarLibraryScreen(getCarContext(), folder, false)))
                    .build());
        }
        if (folders.isEmpty()) {
            list.addItem(new Row.Builder().setTitle("Папок пока нет")
                    .addText("Добавь файлы на Xiaomi и обнови медиатеку").build());
        }
        if (page + 1 < pages) {
            list.addItem(new Row.Builder().setTitle("Следующая страница ›")
                    .setOnClickListener(() -> { page++; invalidate(); }).build());
        } else if (page > 0) {
            list.addItem(new Row.Builder().setTitle("‹ Предыдущая страница")
                    .setOnClickListener(() -> { page--; invalidate(); }).build());
        }
        return new ListTemplate.Builder()
                .setTitle("Папки · " + (page + 1) + "/" + pages)
                .setHeaderAction(Action.BACK)
                .setSingleList(list.build()).build();
    }
}
