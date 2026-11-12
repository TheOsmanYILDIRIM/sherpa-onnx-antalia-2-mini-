package com.osman.antalia.tts;

import android.media.AudioFormat;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.speech.tts.SynthesisCallback;
import android.speech.tts.SynthesisRequest;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeechService;
import android.speech.tts.Voice;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** Android system TextToSpeech engine, not a stand-alone playback-only demo. */
public final class AntaliaTtsService extends TextToSpeechService {
    private AntaliaCore core;
    private volatile AtomicBoolean cancelled = new AtomicBoolean();
    // Aggregate only timing and character counts, never the reader's private book text.
    private final java.util.concurrent.atomic.AtomicLong requestSequence = new java.util.concurrent.atomic.AtomicLong();
    private volatile long previousReturnNs;

    @Override public void onCreate() {
        super.onCreate();
        core = AntaliaCore.shared(this);
        // Prepare models on a background thread before Android's first synthesis request.
        new Thread(() -> {
            try {
                int steps = getSharedPreferences("antalia_settings", MODE_PRIVATE).getBoolean("fast", true) ? 4 : 8;
                int threads = getSharedPreferences("antalia_settings", MODE_PRIVATE).getInt("threads", 4);
                core.prepare(steps, threads);
            } catch (Exception e) {
                android.util.Log.w("AntaliaTtsService", "Early model initialization failed", e);
            }
        }, "antalia-tts-prepare").start();
    }

    @Override protected String[] onGetLanguage() {
        return new String[]{"tur","TUR",""};
    }

    @Override protected int onIsLanguageAvailable(String lang,String country,String variant) {
        return ("tur".equalsIgnoreCase(lang) || "tr".equalsIgnoreCase(lang))
            ? (country == null || country.isEmpty() ? TextToSpeech.LANG_AVAILABLE : TextToSpeech.LANG_COUNTRY_AVAILABLE)
            : TextToSpeech.LANG_NOT_SUPPORTED;
    }

    @Override protected int onLoadLanguage(String lang,String country,String variant) {
        return onIsLanguageAvailable(lang,country,variant);
    }

    @Override public List<Voice> onGetVoices() {
        Voice voice = new Voice("antalia-mini-tr-TR", new Locale("tr","TR"),
            Voice.QUALITY_NORMAL,Voice.LATENCY_NORMAL,false,
            Collections.singleton("offline"));
        return Collections.singletonList(voice);
    }

    @Override protected void onSynthesizeText(SynthesisRequest request, SynthesisCallback callback) {
        if (request == null || callback == null) return;
        final long startNs = SystemClock.elapsedRealtimeNanos();
        final long sequence = requestSequence.incrementAndGet();
        // Most readers supply sentence fragments as independent Android requests.
        // Record lengths, pauses and latency; deliberately never log utterance text.
        final String speech = request.getCharSequenceText() == null ? ""
            : request.getCharSequenceText().toString();
        final long gapMs = previousReturnNs == 0 ? -1
            : Math.max(0, (startNs - previousReturnNs) / 1_000_000);
        final boolean terminalPunctuation = speech.trim().matches("(?s).*[.!?…]$");
        final AtomicBoolean stop = new AtomicBoolean();
        cancelled = stop;
        PowerManager.WakeLock lock = null;
        final long[] firstAudioNs = {-1};
        final long[] totalFrames = {0};
        final int[] callbackPackets = {0};
        final AtomicBoolean consumerFailed = new AtomicBoolean(false);
        String outcome = "unknown";
        try {
            if (onIsLanguageAvailable(request.getLanguage(), request.getCountry(),
                                      request.getVariant()) == TextToSpeech.LANG_NOT_SUPPORTED) {
                callback.error();
                outcome = "unsupported_language";
                return;
            }
            if (callback.start(AntaliaCore.RATE, AudioFormat.ENCODING_PCM_16BIT, 1)
                != TextToSpeech.SUCCESS) {
                outcome = "start_rejected";
                return;
            }
            if (speech.trim().isEmpty()) {
                callback.done();
                outcome = "empty";
                return;
            }
            final int steps = getSharedPreferences("antalia_settings", MODE_PRIVATE)
                .getBoolean("fast", true) ? 4 : 8;
            final int threads = getSharedPreferences("antalia_settings", MODE_PRIVATE)
                .getInt("threads", 4);
            final int speechRate = request.getSpeechRate();
            float speed = .95f * (speechRate > 0 ? speechRate / 100f : 1f);
            speed = Math.max(.35f, Math.min(3f, speed));
            PowerManager power = (PowerManager)getSystemService(POWER_SERVICE);
            lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "antalia:android-tts");
            lock.acquire(10 * 60 * 1000L);
            // Readers control pause/continuation between Android TTS requests.
            // Do not inject an additional 60/120 ms silence into every internal chunk.
            core.speak(speech, steps, threads, speed, stop, audio -> {
                if (stop.get()) return false;
                int max = Math.min(16000, Math.max(256, callback.getMaxBufferSize()));
                byte[] bytes = new byte[audio.length * 2];
                for (int i = 0; i < audio.length; i++) {
                    float f = Float.isFinite(audio[i])
                        ? Math.max(-1f, Math.min(1f, audio[i])) : 0f;
                    short value = (short)Math.round(f * 32767);
                    bytes[i * 2] = (byte)value;
                    bytes[i * 2 + 1] = (byte)(value >>> 8);
                }
                int offset = 0;
                while (offset < bytes.length && !stop.get()) {
                    int length = Math.min(max, bytes.length - offset);
                    // Timestamp before callback; the downstream player may block here.
                    if (firstAudioNs[0] < 0) firstAudioNs[0] = SystemClock.elapsedRealtimeNanos();
                    if (callback.audioAvailable(bytes, offset, length) != TextToSpeech.SUCCESS) {
                        consumerFailed.set(true);
                        stop.set(true);
                        return false;
                    }
                    callbackPackets[0]++;
                    totalFrames[0] += length / 2;
                    offset += length;
                }
                return !stop.get();
            }, false);
            if (consumerFailed.get()) {
                callback.error();
                outcome = "audio_delivery_failed";
            } else if (!stop.get() && callbackPackets[0] == 0) {
                callback.error();
                outcome = "no_audio";
            } else if (!stop.get()) {
                callback.done();
                outcome = "done";
            } else {
                outcome = "cancelled";
            }
        } catch (Exception e) {
            Log.e("AntaliaTtsService", "External TTS synthesis failed", e);
            outcome = "error";
            if (!stop.get()) callback.error();
        } finally {
            if (lock != null && lock.isHeld()) lock.release();
            long endNs = SystemClock.elapsedRealtimeNanos();
            previousReturnNs = endNs;
            long firstMs = firstAudioNs[0] < 0 ? -1 : (firstAudioNs[0] - startNs) / 1_000_000;
            long elapsedMs = (endNs - startNs) / 1_000_000;
            Log.i("AntaliaTtsTiming", "request=" + sequence
                + " chars=" + speech.length()
                + " terminalPunctuation=" + terminalPunctuation
                + " gapFromPreviousMs=" + gapMs
                + " firstPcmMs=" + firstMs
                + " totalMs=" + elapsedMs
                + " audioMs=" + (totalFrames[0] * 1000 / AntaliaCore.RATE)
                + " packets=" + callbackPackets[0]
                + " outcome=" + outcome);
        }
    }

    @Override protected void onStop() { cancelled.set(true); }

    @Override public void onDestroy() {
        cancelled.set(true);
        // Retain model sessions for subsequent service and UI requests in this process.
        super.onDestroy();
    }
}
