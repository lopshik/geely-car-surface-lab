package dev.geely.carsurfacelab;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import android.media.AudioDeviceInfo;

import androidx.annotation.Nullable;

public final class PlaybackForegroundService extends Service {
    private static final String CHANNEL = "car_surface_playback";
    private static final int NOTIFICATION_ID = 39;
    private static final String TAG = "CarSurfaceAudio";
    private static volatile PlaybackForegroundService instance;

    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private MediaPlayer player;
    private MediaSession session;
    private int focusResult = Integer.MIN_VALUE;
    private final Handler handler = new Handler(Looper.getMainLooper());

    public static PlaybackForegroundService active() { return instance; }

    @Override
    public void onCreate() {
        super.onCreate();
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Car Surface Lab audio",
                    NotificationManager.IMPORTANCE_LOW));
        }
        Notification notification = createNotification();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        session = new MediaSession(this, "CarSurfaceLabAudio");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { resume(); }
            @Override public void onPause() { pause(); }
            @Override public void onStop() { stopPlayback(); }
        });
        session.setActive(true);
        updateSession(false);
        instance = this;
        report("Foreground audio service started");
        // If the user chose a file while Android Auto Surface was already open,
        // tell its session when this service is actually ready.
        prefs().edit()
                .putBoolean("await_audio_service", false)
                .putLong("audio_service_ready", android.os.SystemClock.elapsedRealtime())
                .apply();
    }

    private Notification createNotification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ?
                new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return b.setContentTitle("Car Surface Lab — звук")
                .setContentText("Аудиосервис готов к подключению Android Auto")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(content)
                .setOngoing(true).build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    @Nullable
    @Override public IBinder onBind(Intent intent) { return null; }

    private SharedPreferences prefs() {
        return getSharedPreferences("lab", MODE_PRIVATE);
    }

    private void report(String status) {
        Log.i(TAG, status);
        SharedPreferences sp = prefs();
        String record = sp.getString("audio_events", "") +
                new java.util.Date() + " | " + status + " | focus=" + focusName(focusResult) + "\n";
        if (record.length() > 5200) record = record.substring(record.length() - 5200);
        sp.edit().putString("audio_diagnostics", status
                + "\nAudio focus: " + focusName(focusResult)
                + "\nMedia volume: " + audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                + "/" + audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                + "\nMusic active: " + audioManager.isMusicActive())
                .putString("audio_events", record).apply();
    }

    private static String focusName(int result) {
        if (result == Integer.MIN_VALUE) return "not requested";
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return "GRANTED";
        if (result == AudioManager.AUDIOFOCUS_REQUEST_FAILED) return "FAILED";
        if (result == AudioManager.AUDIOFOCUS_REQUEST_DELAYED) return "DELAYED";
        return String.valueOf(result);
    }

    private void updateSession(boolean playing) {
        if (session == null) return;
        PlaybackState st = new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE |
                            PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        PlaybackState.PLAYBACK_POSITION_UNKNOWN, playing ? 1f : 0f)
                .build();
        session.setPlaybackState(st);
    }

    public void play(Uri uri, Surface surface) {
        stopPlayback();
        String stage = "initial";
        if (surface == null || !surface.isValid()) {
            report("Video Surface invalid");
            return;
        }
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build();
        focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(change -> {
                    if (change == AudioManager.AUDIOFOCUS_LOSS ||
                            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                        pause();
                    }
                    report("Audio focus changed: " + change);
                }).build();
        focusResult = audioManager.requestAudioFocus(focusRequest);
        report("Audio focus request = " + focusName(focusResult));
        try {
            stage = "create";
            MediaPlayer mp = new MediaPlayer();
            player = mp;
            stage = "audio attributes";
            mp.setAudioAttributes(attributes);
            mp.setVolume(1f, 1f);
            stage = "setDataSource";
            mp.setDataSource(this, uri);
            stage = "setSurface";
            mp.setSurface(surface);
            stage = "setLooping";
            mp.setLooping(true);
            mp.setOnVideoSizeChangedListener((p, w, h) ->
                    report("Video decoded: " + w + "x" + h));
            mp.setOnPreparedListener(p -> {
                if (player != p) return;
                int audioTracks = 0;
                try {
                    for (MediaPlayer.TrackInfo track : p.getTrackInfo()) {
                        if (track.getTrackType() == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO) audioTracks++;
                    }
                } catch (RuntimeException e) {
                    report("Tracks inspection error: " + e.getClass().getSimpleName());
                }
                try {
                    p.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT);
                    p.start();
                    updateSession(true);
                    report("Playing; audio tracks=" + audioTracks +
                            "; audio session=" + p.getAudioSessionId() + route(p));
                    final int count = audioTracks;
                    handler.postDelayed(() -> {
                        if (player == p) report("Playing after 2s; audio tracks=" + count + route(p));
                    }, 2000);
                } catch (RuntimeException e) {
                    report("Player start error: " + e.getClass().getSimpleName());
                }
            });
            mp.setOnErrorListener((p, what, extra) -> {
                report("MediaPlayer error " + what + "/" + extra);
                updateSession(false);
                return true;
            });
            stage = "prepareAsync";
            mp.prepareAsync();
            report("Foreground player preparing");
        } catch (Exception e) {
            report("Foreground player FAILED at " + stage + ": " + e.getClass().getSimpleName());
            Log.e(TAG, "Video setup failed at " + stage, e);
            stopPlayback();
        }
    }

    private static String route(MediaPlayer p) {
        try {
            AudioDeviceInfo d = p.getRoutedDevice();
            return d == null ? "; routed device=unknown" :
                    "; routed device=" + d.getProductName() + " type=" + d.getType();
        } catch (RuntimeException e) {
            return "; audio route error=" + e.getClass().getSimpleName();
        }
    }

    public void toggle() {
        if (player == null) return;
        try {
            if (player.isPlaying()) pause();
            else resume();
        } catch (RuntimeException e) { report("Toggle failed: " + e.getClass().getSimpleName()); }
    }

    public void pause() {
        if (player == null) return;
        try {
            if (player.isPlaying()) player.pause();
            updateSession(false);
            report("Paused");
        } catch (RuntimeException e) { report("Pause failed: " + e.getClass().getSimpleName()); }
    }

    public void resume() {
        if (player == null) return;
        try {
            player.start();
            updateSession(true);
            report("Resumed");
        } catch (RuntimeException e) { report("Resume failed: " + e.getClass().getSimpleName()); }
    }

    public void stopPlayback() {
        if (player != null) {
            try { player.release(); } catch (RuntimeException ignored) {}
            player = null;
        }
        if (focusRequest != null) {
            audioManager.abandonAudioFocusRequest(focusRequest);
            focusRequest = null;
        }
        updateSession(false);
    }

    @Override
    public void onDestroy() {
        report("Foreground service destroyed");
        stopPlayback();
        instance = null;
        if (session != null) { session.release(); session = null; }
        super.onDestroy();
    }
}
