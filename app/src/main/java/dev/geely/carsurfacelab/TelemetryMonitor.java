package dev.geely.carsurfacelab;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;

import java.util.Date;
import java.util.Locale;

/**
 * Low-overhead, unprivileged PHONE instrumentation. It does not monitor
 * Google's Android Auto host process, video link bitrate, GPU or true FPS.
 */
public final class TelemetryMonitor {
    private static final String PREFS = "lab";
    private static final int PERIOD_MS = 20_000;
    private static final int HISTORY_LIMIT = 14000;

    private final PlaybackForegroundService service;
    private final Context app;
    private final BatteryManager battery;
    private final PowerManager power;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            sample();
            handler.postDelayed(this, PERIOD_MS);
        }
    };
    private boolean running;
    private long startElapsed, previousElapsed, previousCpu, estimatedPlayingMs;
    private int samples, firstLevel = -1, lastLevel = -1, maximumBatteryTemp = -1;
    private int maximumThermal = -1;
    private long maximumPssKb;
    private double cpuSum, maximumCpu;

    public TelemetryMonitor(PlaybackForegroundService service) {
        this.service = service;
        app = service.getApplicationContext();
        battery = (BatteryManager) app.getSystemService(Context.BATTERY_SERVICE);
        power = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
    }

    private SharedPreferences prefs() {
        return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void mark(Context context, String event) {
        if (!("interruptions".equals(event) || "errors".equals(event) ||
                "prepares".equals(event) || "aspect".equals(event) ||
                "speed".equals(event))) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String key = "telemetry_count_" + event;
        prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).apply();
    }

    public static void prepare(Context c, String mode) {
        if (!"USB".equals(mode) && !"Wi-Fi".equals(mode)) return;
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor e = p.edit()
                .putString("telemetry_mode", mode)
                .putString("telemetry_summary", "Режим " + mode +
                        " выбран вручную; ожидаем измерение.")
                .putString("telemetry_samples", "")
                .remove("telemetry_error");
        for (String name : new String[]{"interruptions","errors","prepares","aspect","speed"})
            e.putInt("telemetry_count_" + name, 0);
        e.apply();
    }

    public void start() { begin(prefs().getString("telemetry_mode", "Не выбран")); }

    public void begin(String mode) {
        running = false;
        handler.removeCallbacks(tick);
        SharedPreferences p = prefs();
        p.edit().putString("telemetry_mode", mode)
                .putString("telemetry_samples", "").remove("telemetry_error").apply();
        startElapsed = previousElapsed = SystemClock.elapsedRealtime();
        previousCpu = Process.getElapsedCpuTime();
        estimatedPlayingMs=0; samples=0; firstLevel=-1; lastLevel=-1;
        maximumBatteryTemp=-1; maximumThermal=-1; maximumPssKb=0;
        cpuSum=0; maximumCpu=0;
        running = true;
        sample();
        handler.postDelayed(tick, PERIOD_MS);
    }

    public void stop() {
        if (!running) return;
        sample();
        running=false;
        handler.removeCallbacks(tick);
    }

    private static String fmt(double n) { return String.format(Locale.US, "%.1f", n); }

    private static String powerSource(int flags) {
        if ((flags & BatteryManager.BATTERY_PLUGGED_USB) != 0) return "USB power";
        if ((flags & BatteryManager.BATTERY_PLUGGED_AC) != 0) return "AC power";
        if ((flags & BatteryManager.BATTERY_PLUGGED_WIRELESS) != 0) return "Qi power";
        return "battery only";
    }

    private static String thermalLabel(int code) {
        switch (code) {
            case PowerManager.THERMAL_STATUS_NONE: return "NONE";
            case PowerManager.THERMAL_STATUS_LIGHT: return "LIGHT";
            case PowerManager.THERMAL_STATUS_MODERATE: return "MODERATE";
            case PowerManager.THERMAL_STATUS_SEVERE: return "SEVERE";
            case PowerManager.THERMAL_STATUS_CRITICAL: return "CRITICAL";
            case PowerManager.THERMAL_STATUS_EMERGENCY: return "EMERGENCY";
            case PowerManager.THERMAL_STATUS_SHUTDOWN: return "SHUTDOWN";
            default: return "unknown";
        }
    }

    private void sample() {
        try {
            long now = SystemClock.elapsedRealtime();
            long cpu = Process.getElapsedCpuTime();
            long interval = Math.max(1L, now - previousElapsed);
            double percent = samples == 0 ? 0 :
                    Math.max(0, (cpu - previousCpu) * 100.0 / interval);
            if (samples > 0) {
                cpuSum += percent;
                maximumCpu = Math.max(maximumCpu, percent);
                if (service.playing()) estimatedPlayingMs += interval;
            }
            previousElapsed = now; previousCpu = cpu; samples++;

            Intent b = app.registerReceiver(null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            int level=-1,temp=-1,voltage=0,charging=0;
            if (b != null) {
                int raw=b.getIntExtra(BatteryManager.EXTRA_LEVEL,-1);
                int scale=b.getIntExtra(BatteryManager.EXTRA_SCALE,0);
                if(raw>=0 && scale>0) level=(int)Math.round(raw*100.0/scale);
                temp=b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,-1);
                voltage=b.getIntExtra(BatteryManager.EXTRA_VOLTAGE,0);
                charging=b.getIntExtra(BatteryManager.EXTRA_PLUGGED,0);
            }
            if (firstLevel<0 && level>=0) firstLevel=level;
            if (level>=0) lastLevel=level;
            if (temp>=0) maximumBatteryTemp=Math.max(maximumBatteryTemp,temp);
            int thermal=-1;
            if (Build.VERSION.SDK_INT>=29 && power!=null) {
                thermal=power.getCurrentThermalStatus();
                maximumThermal=Math.max(maximumThermal,thermal);
            }

            int current = battery==null?Integer.MIN_VALUE:
                    battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
            long pssKb = Debug.getPss();
            maximumPssKb=Math.max(maximumPssKb,pssKb);
            long javaMb=(Runtime.getRuntime().totalMemory()-
                    Runtime.getRuntime().freeMemory())/(1024*1024);
            long nativeMb=Debug.getNativeHeapAllocatedSize()/(1024*1024);
            String amps=current==Integer.MIN_VALUE?"н/д":fmt(current/1000.0)+"mA";
            String watts=(voltage>0&&current!=Integer.MIN_VALUE) ?
                    fmt(current/1000000.0 * voltage/1000.0)+"W*" : "н/д";

            SharedPreferences p=prefs();
            String record=String.format(Locale.US,
                    "%tT | %s | %s | %s%% %.1f°C thermal=%s | I=%s P=%s"+
                    " | CPU=%s%%/core PSS=%dMB Java=%dMB Native=%dMB"+
                    " | video=%ds/%ds playing=%s aspect=%s\n",
                    new Date(),p.getString("telemetry_mode","Не выбран"),
                    powerSource(charging),level<0?"n/a":Integer.toString(level),
                    temp<0?Double.NaN:temp/10.0,
                    thermal<0?"н/д":thermalLabel(thermal),
                    amps,watts,fmt(percent),pssKb/1024,javaMb,nativeMb,
                    service.position()/1000,service.duration()/1000,service.playing(),
                    p.getString("picture_mode","fill"));

            String history=p.getString("telemetry_samples","")+record;
            if (history.length()>HISTORY_LIMIT)
                history=history.substring(history.length()-HISTORY_LIMIT);
            String summary="Транспорт Android Auto (метка вручную): "+
                    p.getString("telemetry_mode","Не выбран")+
                    "\nПрошло: "+(now-startElapsed)/1000+" сек; видео играет (≈): "+
                    estimatedPlayingMs/1000+" сек"+
                    "\nБатарея: "+firstLevel+"% → "+lastLevel+"%; макс. T батареи: "+
                    (maximumBatteryTemp<0?"н/д":fmt(maximumBatteryTemp/10.0)+"°C")+
                    "\nМакс. thermal: "+
                    (maximumThermal<0?"н/д":thermalLabel(maximumThermal))+
                    "\nCPU процесса, % одного ядра, среднее/пик: "+
                    fmt(samples>1?cpuSum/(samples-1):0)+" / "+fmt(maximumCpu)+
                    "\nПик PSS: "+maximumPssKb/1024+" MB"+
                    "\nПрерывания: "+p.getInt("telemetry_count_interruptions",0)+
                    "; ошибки: "+p.getInt("telemetry_count_errors",0)+
                    "; подготовки декодера: "+p.getInt("telemetry_count_prepares",0)+
                    "\nПереключения масштаба/скорости: "+
                    p.getInt("telemetry_count_aspect",0)+"/"+
                    p.getInt("telemetry_count_speed",0)+
                    "\nДекодер: "+p.getInt("telemetry_video_width",0)+"x"+
                    p.getInt("telemetry_video_height",0)+
                    "\nFPS, потери кадров, GPU%, скорость канала AA: н/д (нет достоверного API)"+
                    "\n* I и P относятся к БАТАРЕЕ всего телефона, не только к CarVideo."+
                    "\nТемпература — батареи, не CPU; проценты CPU — для одного ядра."+
                    "\nСэмпл каждые 20 сек, данные приблизительные; зарядка влияет на сравнение.";
            p.edit().putString("telemetry_samples",history)
                    .putString("telemetry_summary",summary).apply();
        } catch (RuntimeException ex) {
            prefs().edit().putString("telemetry_error",
                    ex.getClass().getSimpleName()).apply();
        }
    }
}
