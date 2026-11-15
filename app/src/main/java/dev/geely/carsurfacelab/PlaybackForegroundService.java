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
import android.media.PlaybackParams;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;
import android.media.AudioDeviceInfo;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;

import java.util.HashMap;
import java.util.Map;

public final class PlaybackForegroundService extends Service {
    private static final String CHANNEL = "car_surface_playback";
    private static final int NOTIFICATION_ID = 39;
    private static final String TAG = "CarSurfaceAudio";
    private static volatile PlaybackForegroundService instance;

    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private MediaPlayer player;
    // TV uses Media3; the owner-tested local MediaPlayer remains untouched.
    private ExoPlayer tvPlayer;
    private int tvRetries;
    private MediaSession session;
    private Uri currentUri;
    private long pendingSeekMs;
    private boolean startAfterPrepared;
    private boolean prepared;
    private Surface hostSurface;
    private AspectSurfaceRenderer aspectRenderer;
    private int playGeneration;
    private long lastCarSkipUptimeMs;
    private long lastCarSkipTargetMs;
    // Android Auto can synthesize dozens of clicks per second for a held action.
    // Bound the per-click seek rate to avoid flooding the decoder and audio logs.
    private static final long CAR_SKIP_MIN_INTERVAL_MS = 550L;
    private final Runnable positionTick = new Runnable() {
        @Override public void run() {
            if (player != null && prepared) {
                try { if (player.isPlaying()) savePosition("periodic"); }
                catch (RuntimeException ignored) {}
            }
            handler.postDelayed(this, 3000);
        }
    };
    private int focusResult = Integer.MIN_VALUE;
    private TelemetryMonitor telemetry;
    private final Handler handler = new Handler(Looper.getMainLooper());

    public static PlaybackForegroundService active() { return instance; }
    public void restartTelemetry(String mode) {
        if (telemetry != null) telemetry.begin(mode);
    }

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
        telemetry = new TelemetryMonitor(this);
        telemetry.start();
        report("Foreground audio service started");
        handler.postDelayed(positionTick, 3000);
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
        return b.setContentTitle("CarVideo")
                .setContentText("Воспроизведение на экране автомобиля")
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
                        PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                        playing ? configuredRate() : 0f)
                .build();
        session.setPlaybackState(st);
    }

    public void play(Uri uri, Surface surface) {
        play(uri, surface, true);
    }

    /**
     * A reverse camera or other OEM overlay owns the display while it is visible.
     * Never fight that overlay. Restore the prior timestamp, but after a display
     * interruption leave video paused until the user explicitly presses Play.
     */
    public float configuredRate() {
        return prefs().getFloat("playback_rate", 1.0f);
    }

    public String configuredPicture() {
        return prefs().getString("picture_mode", "fill");
    }

    public void changeRate(float speed) {
        if (!(speed == 0.75f || speed == 1f || speed == 1.25f ||
                speed == 1.5f || speed == 2f)) speed = 1f;
        prefs().edit().putFloat("playback_rate", speed).apply();
        TelemetryMonitor.mark(this,"speed");
        if (player != null && prepared) {
            // setPlaybackParams(nonzero) STARTS the player on Android.
            // Never invoke it while paused (including after the rear camera).
            try {
                if (player.isPlaying()) applyRate(player);
            } catch (RuntimeException e) {
                report("Speed change failed: " + e.getClass().getSimpleName());
            }
        }
        report("Preferred playback speed " + speed + "x");
    }

    private void applyRate(MediaPlayer p) {
        float value = configuredRate();
        if (Math.abs(value - 1f) < 0.001f) return; // retain proven default audio path
        try {
            p.setPlaybackParams(new PlaybackParams().setSpeed(value).setPitch(1f));
            report("Playback speed applied: " + value + "x");
        } catch (RuntimeException e) {
            report("Playback speed not supported: " + value + "x, " +
                    e.getClass().getSimpleName());
            prefs().edit().putFloat("playback_rate", 1f).apply();
        }
    }

    public void updateVisibleVideoArea(int left,int top,int right,int bottom) {
        if (aspectRenderer != null) aspectRenderer.setSafeArea(left,top,right,bottom);
    }

    public void changePicture(String mode) {
        if (!"fit".equals(mode) && !"fill".equals(mode)) return;
        prefs().edit().putString("picture_mode", mode).apply();
        TelemetryMonitor.mark(this,"aspect");
        Surface host = hostSurface;
        Uri uri = currentUri;
        boolean shouldPlay = playing();
        if (uri != null && host != null && host.isValid()) {
            report("Switch aspect mode, save current timestamp: " + mode);
            play(uri, host, shouldPlay);
        }
    }

    public void play(Uri uri, Surface surface, boolean autoPlay) {
        stopPlayback();
        TelemetryMonitor.mark(this,"prepares");
        int generation = ++playGeneration;
        hostSurface = surface;
        currentUri = uri;
        prepared = false;
        startAfterPrepared = autoPlay;
        pendingSeekMs = prefs().getLong(positionKey(uri), 0L);
        report("Restoring movie position: " + pendingSeekMs + "ms; autoPlay=" + autoPlay);
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
        boolean fit = "fit".equals(configuredPicture());
        boolean tv = "http".equalsIgnoreCase(uri.getScheme()) ||
                "https".equalsIgnoreCase(uri.getScheme());
        if (tv) report("TV live stream selected; Media3 player, " +
                (fit ? "FIT" : "FILL") + ", autoPlay=" + autoPlay);
        if (fit) {
            // Correct letterbox with no aspect distortion on the host's fixed Surface.
            // Extra compositor is opt-in; direct fill remains the efficient default.
            AspectSurfaceRenderer renderer = new AspectSurfaceRenderer(surface,
                    prefs().getInt("surface_width", 1802),
                    prefs().getInt("surface_height", 1052),
                    new AspectSurfaceRenderer.Listener() {
                @Override public void onReady(Surface decoder) {
                    if (generation != playGeneration || currentUri == null) return;
                    report("Aspect FIT renderer ready");
                    if (tv) initializeTv(uri, decoder, attributes, true, generation);
                    else initializePlayer(uri, decoder, attributes, true);
                }
                @Override public void onFailure(String reason) {
                    if (generation != playGeneration || currentUri == null) return;
                    report("FIT EGL failure: " + reason + "; trying direct FILL");
                    if (aspectRenderer != null) { aspectRenderer.close(); aspectRenderer = null; }
                    if (player == null && tvPlayer == null) {
                        if (tv) initializeTv(uri, surface, attributes, false, generation);
                        else initializePlayer(uri, surface, attributes, false);
                    }
                }
            });
            aspectRenderer = renderer;
            renderer.setSafeArea(
                    prefs().getInt("video_safe_left",0),
                    prefs().getInt("video_safe_top",0),
                    prefs().getInt("video_safe_right",prefs().getInt("surface_width",1802)),
                    prefs().getInt("video_safe_bottom",prefs().getInt("surface_height",1052)));
            renderer.start();
        } else {
            if (tv) initializeTv(uri, surface, attributes, false, generation);
            else initializePlayer(uri, surface, attributes, false);
        }
    }

    private void initializePlayer(Uri uri, Surface target, AudioAttributes attributes,
                                  boolean aspectFit) {
        String stage = "create";
        try {
            MediaPlayer mp = new MediaPlayer();
            player = mp;
            stage = "audio attributes";
            mp.setAudioAttributes(attributes);
            mp.setVolume(1f, 1f);
            stage = "setDataSource";
            mp.setDataSource(this, uri);
            stage = "setSurface";
            mp.setSurface(target);
            stage = "setLooping";
            mp.setLooping(true);
            mp.setOnVideoSizeChangedListener((p, w, h) -> {
                if (aspectFit && aspectRenderer != null) aspectRenderer.videoSize(w, h);
                prefs().edit().putInt("telemetry_video_width",w)
                        .putInt("telemetry_video_height",h).apply();
                report("Video decoded: " + w + "x" + h +
                        "; image mode=" + (aspectFit ? "FIT" : "FILL"));
            });
            mp.setOnPreparedListener(p -> {
                if (player != p) return;
                int audioTracks = 0;
                try {
                    for (MediaPlayer.TrackInfo track : p.getTrackInfo()) {
                        if (track.getTrackType() == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO)
                            audioTracks++;
                    }
                } catch (RuntimeException e) {
                    report("Tracks inspection error: " + e.getClass().getSimpleName());
                }
                try {
                    // FIT uses an aspect-matched decoder SurfaceTexture and an EGL
                    // letterboxed output. Direct FILL preserves aspect via cropping.
                    p.setVideoScalingMode(aspectFit ?
                            MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT :
                            MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
                    prepared = true;
                    final int count = audioTracks;
                    long duration = p.getDuration();
                    if (pendingSeekMs > 0 &&
                            (duration <= 0 || pendingSeekMs < duration - 3000)) {
                        long at = pendingSeekMs;
                        // One-shot listener: user seeks must NOT re-run
                        // finishPreparing(), start playback or change pause state.
                        p.setOnSeekCompleteListener(ready -> {
                            if (player != ready) return;
                            ready.setOnSeekCompleteListener(null);
                            finishPreparing(ready, count);
                        });
                        report("Prepared; seek to saved position " + at + "ms");
                        p.seekTo(at, MediaPlayer.SEEK_CLOSEST_SYNC);
                    } else {
                        p.setOnSeekCompleteListener(null);
                        finishPreparing(p, count);
                    }
                } catch (RuntimeException e) {
                    report("Player prepare/seek error: " + e.getClass().getSimpleName());
                }
            });
            mp.setOnErrorListener((p, what, extra) -> {
                TelemetryMonitor.mark(this,"errors");
                report("MediaPlayer error " + what + "/" + extra);
                updateSession(false);
                return true;
            });
            stage = "prepareAsync";
            mp.prepareAsync();
            report("Foreground player preparing; " + (aspectFit ? "FIT" : "FILL"));
        } catch (Exception e) {
            report("Foreground player FAILED at " + stage + ": " + e.getClass().getSimpleName());
            Log.e(TAG, "Video setup failed at " + stage, e);
            stopPlayback();
        }
    }

    /**
     * Separate live-TV media pipeline. ExoPlayer handles HLS playlist reloads,
     * adaptive variant selection, progressive network streams and buffering.
     * All operations are invoked on the service main thread.
     */
    private void initializeTv(Uri uri, Surface target, AudioAttributes platformAttrs,
                              boolean fit, int generation) {
        String stage="create";
        try {
            Map<String,String> headers=new HashMap<>();
            String userAgent="CarVideo/1.1 Android";
            String channelId=prefs().getString("tv_channel_id","");
            for(TvCatalog.Channel c:TvCatalog.all(this)){
                if(c.id.equals(channelId)){
                    if(!c.userAgent.isEmpty())userAgent=c.userAgent;
                    if(!c.referer.isEmpty())headers.put("Referer",c.referer);
                    break;
                }
            }
            DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                    .setUserAgent(userAgent)
                    .setConnectTimeoutMs(10000)
                    .setReadTimeoutMs(12000)
                    .setAllowCrossProtocolRedirects(true);
            if(!headers.isEmpty())http.setDefaultRequestProperties(headers);
            DefaultDataSource.Factory dataSources=new DefaultDataSource.Factory(this,http);
            ExoPlayer live=new ExoPlayer.Builder(this)
                    .setMediaSourceFactory(new androidx.media3.exoplayer.source
                            .DefaultMediaSourceFactory(dataSources)).build();
            tvPlayer=live;
            stage="surface";
            live.setVideoSurface(target);
            androidx.media3.common.AudioAttributes attributes=
                    new androidx.media3.common.AudioAttributes.Builder()
                            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                            .setUsage(C.USAGE_MEDIA).build();
            // The Service already owns the platform AudioFocusRequest.
            live.setAudioAttributes(attributes,false);
            live.addListener(new Player.Listener(){
                @Override public void onPlaybackStateChanged(int state) {
                    if(tvPlayer!=live||generation!=playGeneration)return;
                    if(state==Player.STATE_BUFFERING){
                        report("TV buffering");
                    } else if(state==Player.STATE_READY){
                        prepared=true;
                        updateSession(live.isPlaying());
                        report("TV ready; playing="+live.isPlaying()+
                                ", duration="+live.getDuration());
                    } else if(state==Player.STATE_ENDED){
                        prepared=false;
                        updateSession(false);
                        report("TV stream ended");
                    }
                }
                @Override public void onIsPlayingChanged(boolean playing) {
                    if(tvPlayer!=live||generation!=playGeneration)return;
                    updateSession(playing);
                    report("TV "+(playing?"playing":"paused / buffering"));
                }
                @Override public void onVideoSizeChanged(VideoSize size) {
                    if(tvPlayer!=live||generation!=playGeneration)return;
                    if(fit && aspectRenderer!=null) {
                        int corrected=(int)(size.width*size.pixelWidthHeightRatio);
                        aspectRenderer.videoSize(Math.max(1,corrected),size.height);
                    }
                    prefs().edit().putInt("telemetry_video_width",size.width)
                            .putInt("telemetry_video_height",size.height).apply();
                    report("TV decoded "+size.width+"x"+size.height);
                }
                @Override public void onPlayerError(PlaybackException error) {
                    if(tvPlayer!=live||generation!=playGeneration)return;
                    TelemetryMonitor.mark(PlaybackForegroundService.this,"errors");
                    // Do not report URL or signed source token in public diagnostics.
                    report("TV playback failed: "+error.errorCodeName);
                    if(tvRetries++<3 && hostSurface!=null && hostSurface.isValid()){
                        long delay=1500L<<(tvRetries-1);
                        handler.postDelayed(()->{
                            if(tvPlayer!=live || generation!=playGeneration ||
                                    hostSurface==null||!hostSurface.isValid())return;
                            report("TV retry "+tvRetries);
                            live.seekToDefaultPosition();
                            live.prepare();
                        },delay);
                    }
                }
            });
            stage="media item";
            MediaItem.Builder item=new MediaItem.Builder().setUri(uri);
            String lower=uri.toString().toLowerCase(java.util.Locale.ROOT);
            if(lower.contains(".m3u8") || lower.contains("format=m3u8") ||
                    lower.contains("type=hls"))
                item.setMimeType(MimeTypes.APPLICATION_M3U8);
            live.setMediaItem(item.build());
            live.setPlayWhenReady(startAfterPrepared);
            stage="prepare";
            live.prepare();
            report("TV preparing (network stream); autoPlay="+startAfterPrepared);
        }catch(RuntimeException e){
            TelemetryMonitor.mark(this,"errors");
            report("TV setup failed at "+stage+": "+e.getClass().getSimpleName());
            stopPlayback();
        }
    }

    private void finishPreparing(MediaPlayer p, int audioTracks) {
        if (player != p) return;
        try {
            if (startAfterPrepared) {
                p.start();
                applyRate(p);
                updateSession(true);
                report("Playing; audio tracks=" + audioTracks +
                        "; restored position=" + p.getCurrentPosition() +
                        "; audio session=" + p.getAudioSessionId() + route(p));
            } else {
                updateSession(false);
                report("Restored PAUSED at " + p.getCurrentPosition() +
                        "ms (press Play to continue); audio tracks=" + audioTracks);
            }
            handler.postDelayed(() -> {
                if (player == p) report("Playback state after 2s: " +
                        (p.isPlaying() ? "playing" : "paused") + route(p));
            }, 2000);
        } catch (RuntimeException ex) {
            report("Start after seek failed: " + ex.getClass().getSimpleName());
        }
    }

    public static String positionKey(Uri uri) {
        // Stable across reconnects/restarts and resistant to path collisions.
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(uri.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder("pos_");
            for (int i = 0; i < 16; i++) b.append(String.format(java.util.Locale.US, "%02x", hash[i] & 0xFF));
            return b.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return "pos_" + Integer.toHexString(uri.toString().hashCode());
        }
    }

    private void savePosition(String reason) {
        if (player == null || !prepared || currentUri == null) return;
        try {
            long position = Math.max(0, player.getCurrentPosition());
            long duration = player.getDuration();
            if (duration > 0 && duration - position < 2500) position = 0;
            prefs().edit().putLong(positionKey(currentUri), position)
                    .putLong("last_position_ms", position)
                    .putString("last_position_reason", reason).apply();
        } catch (RuntimeException e) {
            report("Position snapshot failed: " + e.getClass().getSimpleName());
        }
    }

    public void suspendForCarUi(String reason) {
        if ((player != null && prepared) || tvPlayer != null)
            TelemetryMonitor.mark(this,"interruptions");
        savePosition(reason);
        prefs().edit().putBoolean("resume_interrupted", true).apply();
        stopPlayback();
        report("Car display interrupted: " + reason + "; saved position; camera priority");
    }

    public long position() {
        if (tvPlayer != null) return Math.max(0L,tvPlayer.getCurrentPosition());
        if (player == null || !prepared) return prefs().getLong("last_position_ms", 0);
        try { return player.getCurrentPosition(); }
        catch (RuntimeException e) { return 0; }
    }
    public long duration() {
        if (tvPlayer != null) {
            long length=tvPlayer.getDuration();
            return length==C.TIME_UNSET?0:Math.max(0,length);
        }
        if (player == null || !prepared) return 0;
        try { return player.getDuration(); }
        catch (RuntimeException e) { return 0; }
    }
    public boolean playing() {
        if (tvPlayer != null) return tvPlayer.isPlaying();
        if (player == null || !prepared) return false;
        try { return player.isPlaying(); }
        catch (RuntimeException e) { return false; }
    }
    public void seekTo(long ms) {
        if (player == null || !prepared) return;
        try {
            long max = duration();
            long target = max > 0 ? Math.max(0L, Math.min(ms, max)) : Math.max(0L, ms);
            // Seek is asynchronous: getCurrentPosition() immediately after seekTo
            // can still return the OLD timestamp, so store the requested value.
            player.seekTo(target, MediaPlayer.SEEK_CLOSEST);
            if (currentUri != null) {
                prefs().edit().putLong(positionKey(currentUri), target)
                        .putLong("last_position_ms", target)
                        .putString("last_position_reason", "user-seek-request").apply();
            }
        } catch (RuntimeException e) {
            report("Seek error: " + e.getClass().getSimpleName());
        }
    }

    public boolean skipBy(long deltaMs) {
        if (player == null || !prepared) return false;
        long now = SystemClock.uptimeMillis();
        if (now - lastCarSkipUptimeMs < CAR_SKIP_MIN_INTERVAL_MS) return false;
        long base = now - lastCarSkipUptimeMs < 1800L
                ? lastCarSkipTargetMs : position();
        long max = duration();
        long target = max > 0 ? Math.max(0L, Math.min(base+deltaMs, max))
                : Math.max(0L, base+deltaMs);
        lastCarSkipUptimeMs = now;
        lastCarSkipTargetMs = target;
        seekTo(target);
        report("Car skip " + (deltaMs/1000) + "s -> " + target + "ms");
        return true;
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
        if (tvPlayer != null) {
            if(tvPlayer.isPlaying()) pause();
            else resume();
            return;
        }
        if (player == null) return;
        try {
            if (player.isPlaying()) pause();
            else resume();
        } catch (RuntimeException e) { report("Toggle failed: " + e.getClass().getSimpleName()); }
    }

    public void pause() {
        if (tvPlayer != null) {
            tvPlayer.pause();
            updateSession(false);
            report("TV paused");
            return;
        }
        if (player == null || !prepared) return;
        try {
            if (player.isPlaying()) player.pause();
            savePosition("user-pause");
            updateSession(false);
            report("Paused at " + position());
        } catch (RuntimeException e) { report("Pause failed: " + e.getClass().getSimpleName()); }
    }

    public void resume() {
        if (tvPlayer != null) {
            tvPlayer.play();
            prefs().edit().putBoolean("resume_interrupted",false).apply();
            updateSession(true);
            report("TV resume requested");
            return;
        }
        if (player == null || !prepared) return;
        try {
            player.start();
            applyRate(player);
            prefs().edit().putBoolean("resume_interrupted", false).apply();
            updateSession(true);
            report("Resumed at " + position());
        } catch (RuntimeException e) { report("Resume failed: " + e.getClass().getSimpleName()); }
    }

    public void stopPlayback() {
        ++playGeneration;
        savePosition("player-release");
        prepared = false;
        lastCarSkipUptimeMs = 0L;
        lastCarSkipTargetMs = 0L;
        if (player != null) {
            try { player.release(); } catch (RuntimeException ignored) {}
            player = null;
        }
        if (tvPlayer != null) {
            tvPlayer.release();
            tvPlayer = null;
        }
        tvRetries=0;
        currentUri = null;
        hostSurface = null;
        if (aspectRenderer != null) {
            aspectRenderer.close();
            aspectRenderer = null;
        }
        if (focusRequest != null) {
            audioManager.abandonAudioFocusRequest(focusRequest);
            focusRequest = null;
        }
        updateSession(false);
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(positionTick);
        if (telemetry != null) {telemetry.stop(); telemetry = null;}
        report("Foreground service destroyed");
        stopPlayback();
        instance = null;
        if (session != null) { session.release(); session = null; }
        super.onDestroy();
    }
}
