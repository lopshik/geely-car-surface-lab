package dev.geely.carsurfacelab;

import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.model.Action;
import androidx.car.app.model.ItemList;
import androidx.car.app.model.ListTemplate;
import androidx.car.app.model.Row;
import androidx.car.app.model.Template;

/**
 * Compact car settings: two large touch rows, no nested submenu.
 * Returning to the movie after an OEM-host screen interruption stays paused,
 * just as in owner-tested v0.7.1.
 */
public final class PlaybackOptionsScreen extends Screen {
    private static final float[] RATES = {0.75f,1f,1.25f,1.5f,2f};

    public PlaybackOptionsScreen(@NonNull CarContext c) { super(c); }

    private SharedPreferences prefs() {
        return getCarContext().getSharedPreferences("lab", CarContext.MODE_PRIVATE);
    }

    private static String speedLabel(float v) {
        if (v == 1f) return "1×";
        return String.valueOf(v).replace(".0", "") + "×";
    }

    @NonNull @Override public Template onGetTemplate() {
        float rate = prefs().getFloat("playback_rate",1f);
        boolean fit = "fit".equals(prefs().getString("picture_mode","fill"));
        ItemList.Builder items = new ItemList.Builder();
        items.addItem(new Row.Builder()
                .setTitle("Скорость   " + speedLabel(rate))
                .addText("Нажми для смены: 0,75 / 1 / 1,25 / 1,5 / 2")
                .setOnClickListener(this::changeSpeed)
                .build());
        items.addItem(new Row.Builder()
                .setTitle("Размер   " + (fit ? "Целиком" : "Заполнить"))
                .addText(fit ? "Без искажения, возможны поля" :
                        "Без искажения, возможна обрезка краёв")
                .setOnClickListener(this::changeScale)
                .build());
        return new ListTemplate.Builder()
                .setTitle("Настройки видео")
                .setHeaderAction(Action.BACK)
                .setSingleList(items.build())
                .build();
    }

    private void changeSpeed() {
        float current = prefs().getFloat("playback_rate",1f);
        int idx = 0;
        for (int i = 0; i < RATES.length; i++) {
            if (Math.abs(current-RATES[i]) < 0.01f) { idx = i; break; }
        }
        float next = RATES[(idx+1)%RATES.length];
        PlaybackForegroundService service = PlaybackForegroundService.active();
        if (service != null) service.changeRate(next);
        else prefs().edit().putFloat("playback_rate",next).apply();
        invalidate();
    }

    private void changeScale() {
        String current = prefs().getString("picture_mode","fill");
        String next = "fit".equals(current) ? "fill" : "fit";
        PlaybackForegroundService service = PlaybackForegroundService.active();
        if (service != null) service.changePicture(next);
        else prefs().edit().putString("picture_mode",next).apply();
        invalidate();
    }
}
