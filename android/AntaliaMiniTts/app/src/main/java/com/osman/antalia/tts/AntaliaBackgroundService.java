package com.osman.antalia.tts;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** User-controlled persistent TTS service inspired by Termux foreground/wake-lock handling. */
public final class AntaliaBackgroundService extends Service {
    static final String ACTION_READ = "com.osman.antalia.tts.READ";
    static final String ACTION_STOP_READ = "com.osman.antalia.tts.STOP_READ";
    static final String ACTION_GUARD_ON = "com.osman.antalia.tts.GUARD_ON";
    static final String ACTION_GUARD_OFF = "com.osman.antalia.tts.GUARD_OFF";
    static final String ACTION_STOP_ALL = "com.osman.antalia.tts.STOP_ALL";
    static final String EXTRA_TEXT = "text";
    static final String EXTRA_STEPS = "steps";
    static final String EXTRA_THREADS = "threads";
    private static final String CHANNEL = "antalia_reading";
    private static final int NOTIFICATION = 1011;
    private static volatile boolean running, guardActive;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile AtomicBoolean cancelled = new AtomicBoolean();
    private volatile AudioTrack track;
    private PowerManager.WakeLock wakeLock;
    private boolean protect;
    private boolean reading;
    private boolean indefiniteLock;

    static boolean isGuardActive() { return running && guardActive; }
    static Intent command(Context context, String action) {
        return new Intent(context, AntaliaBackgroundService.class).setAction(action);
    }
    @Override public void onCreate() {
        super.onCreate();
        running = true;
        NotificationManager manager = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL,
            "Antalia arka plan seslendirme", NotificationManager.IMPORTANCE_LOW));
        PowerManager power = (PowerManager)getSystemService(POWER_SERVICE);
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "antalia:foreground-tts");
        wakeLock.setReferenceCounted(false);
    }
    private PendingIntent action(String name, int requestCode) {
        return PendingIntent.getService(this, requestCode, command(this, name),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 1,
            new Intent(this, MainActivity.class),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
            .setContentTitle("Antalia Mini · arka plan")
            .setContentText(reading ? "Türkçe seslendirme sürüyor"
                : "Ekran kapalıyken TTS motoru hazır tutuluyor")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setShowWhen(false);
        if (reading)
            builder.addAction(android.R.drawable.ic_media_pause,
                "Okumayı durdur", action(ACTION_STOP_READ, 2));
        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel,
            "Arka planı kapat", action(ACTION_STOP_ALL, 3));
        return builder.build();
    }
    private void enterForeground() {
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(NOTIFICATION, notification());
    }
    private void refreshWakeLock() {
        // Termux-style opt-in lock. Reading without guard gets a 10-minute cap.
        if (protect) {
            // A timed reading lock cannot be promoted by merely checking isHeld().
            if (!indefiniteLock && wakeLock.isHeld()) wakeLock.release();
            if (!wakeLock.isHeld()) wakeLock.acquire();
            indefiniteLock = true;
        } else if (reading) {
            if (indefiniteLock && wakeLock.isHeld()) wakeLock.release();
            if (!wakeLock.isHeld()) wakeLock.acquire(10 * 60 * 1000L);
            indefiniteLock = false;
        } else {
            if (wakeLock.isHeld()) wakeLock.release();
            indefiniteLock = false;
        }
    }
    private void refreshOrStop() {
        guardActive = protect;
        if (protect || reading) {
            enterForeground();
            refreshWakeLock();
        } else {
            if (wakeLock.isHeld()) wakeLock.release();
            stopForeground(true);
            stopSelf();
        }
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (action == null) { stopSelf(startId); return START_NOT_STICKY; }
        switch (action) {
            case ACTION_GUARD_ON:
                protect = true;
                refreshOrStop();
                executor.execute(() -> {
                    try {
                        int steps = getSharedPreferences("antalia_settings", MODE_PRIVATE)
                            .getBoolean("fast", true) ? 4 : 8;
                        int threads = getSharedPreferences("antalia_settings", MODE_PRIVATE)
                            .getInt("threads", 4);
                        AntaliaCore.shared(this).prepare(steps, threads);
                    } catch (Exception e) {
                        Log.w("AntaliaBackground", "Background TTS preload failed", e);
                    }
                });
                break;
            case ACTION_GUARD_OFF:
                protect = false;
                refreshOrStop();
                break;
            case ACTION_READ:
                String text = intent.getStringExtra(EXTRA_TEXT);
                if (text == null || text.trim().isEmpty()) {
                    if (!protect && !reading) stopSelf(startId);
                    return START_NOT_STICKY;
                }
                reading = true;
                refreshOrStop();  // Promote immediately; Android's 5-second FGS deadline.
                readAsync(text, intent.getIntExtra(EXTRA_STEPS, 4),
                               intent.getIntExtra(EXTRA_THREADS, 4));
                break;
            case ACTION_STOP_READ:
                cancelCurrent();
                reading = false;
                refreshOrStop();
                break;
            case ACTION_STOP_ALL:
                protect = false;
                cancelCurrent();
                reading = false;
                refreshOrStop();
                break;
            default:
                stopSelf(startId);
                break;
        }
        return START_NOT_STICKY; // No reboot or process-death auto-resurrection.
    }
    private static byte[] pcm16(float[] samples) {
        byte[] pcm = new byte[samples.length * 2];
        for (int i=0; i<samples.length; i++) {
            float f = Float.isFinite(samples[i]) ? Math.min(1f, Math.max(-1f, samples[i])) : 0f;
            short val = (short)Math.round(f * 32767);
            pcm[2*i] = (byte)val;
            pcm[2*i+1] = (byte)(val >>> 8);
        }
        return pcm;
    }
    private static AudioTrack newTrack() {
        int min = AudioTrack.getMinBufferSize(AntaliaCore.RATE,
            AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack out = new AudioTrack(AudioManager.STREAM_MUSIC, AntaliaCore.RATE,
            AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
            Math.max(16384, min), AudioTrack.MODE_STREAM);
        if (out.getState() != AudioTrack.STATE_INITIALIZED) {
            out.release();
            throw new IllegalStateException("AudioTrack initialization failed");
        }
        out.play();
        return out;
    }
    private void cancelCurrent() {
        cancelled.set(true);
        AudioTrack out = track;
        if (out != null) {
            try { out.pause(); out.flush(); } catch (Exception ignored) {}
        }
    }
    private void readAsync(String speech, int steps, int threads) {
        cancelled.set(true);
        AtomicBoolean token = new AtomicBoolean();
        cancelled = token;
        executor.execute(() -> {
            if (token.get()) return;
            AudioTrack out = null;
            try {
                out = newTrack();
                track = out;
                final AudioTrack output = out;
                AntaliaCore.shared(this).speak(speech, steps, threads, .95f, token, audio -> {
                    if (token.get()) return false;
                    byte[] bytes = pcm16(audio);
                    int at = 0;
                    while (at < bytes.length && !token.get()) {
                        int count = Math.min(16384, bytes.length - at);
                        int wrote = output.write(bytes, at, count);
                        if (wrote <= 0) return false;
                        at += wrote;
                    }
                    return !token.get();
                });
            } catch (Exception e) {
                if (!token.get()) Log.e("AntaliaBackground", "Playback failed", e);
            } finally {
                if (out != null) {
                    try { out.stop(); } catch (Exception ignored) {}
                    out.release();
                    if (track == out) track = null;
                }
                new android.os.Handler(getMainLooper()).post(() -> {
                    if (cancelled == token) {
                        reading = false;
                        refreshOrStop();
                    }
                });
            }
        });
    }
    @Override public void onDestroy() {
        running = false;
        guardActive = false;
        cancelCurrent();
        executor.shutdownNow();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
