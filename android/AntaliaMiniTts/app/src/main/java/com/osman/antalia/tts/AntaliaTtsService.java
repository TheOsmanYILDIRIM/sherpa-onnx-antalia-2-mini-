package com.osman.antalia.tts;

import android.media.AudioFormat;
import android.os.PowerManager;
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

    @Override protected void onSynthesizeText(SynthesisRequest request,SynthesisCallback callback) {
        if(request==null || callback==null) return;
        AtomicBoolean stop = new AtomicBoolean(false);
        cancelled = stop;
        PowerManager.WakeLock lock = null;
        try {
            String speech = request.getCharSequenceText().toString();
            int language = onIsLanguageAvailable(request.getLanguage(),request.getCountry(),request.getVariant());
            if(language==TextToSpeech.LANG_NOT_SUPPORTED){callback.error();return;}
            if(callback.start(AntaliaCore.RATE,AudioFormat.ENCODING_PCM_16BIT,1)!=TextToSpeech.SUCCESS)return;
            if(speech.trim().isEmpty()){callback.done();return;}
            int steps = getSharedPreferences("antalia_settings",MODE_PRIVATE).getBoolean("fast",true)?4:8;
            int threads = getSharedPreferences("antalia_settings",MODE_PRIVATE).getInt("threads",4);
            int speechRate = request.getSpeechRate();
            float speed = .95f * (speechRate>0?speechRate/100f:1f);
            speed = Math.max(.35f,Math.min(3f,speed));
            PowerManager power = (PowerManager)getSystemService(POWER_SERVICE);
            lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"antalia:android-tts");
            lock.acquire(10*60*1000L);
            core.speak(speech,steps,threads,speed,stop,audio -> {
                if(stop.get())return false;
                int max = Math.min(16000,Math.max(256,callback.getMaxBufferSize()));
                byte[] bytes = new byte[audio.length*2];
                for(int i=0;i<audio.length;i++) {
                    float f=Float.isFinite(audio[i])?Math.max(-1f,Math.min(1f,audio[i])):0f;
                    short value=(short)Math.round(f*32767);
                    bytes[i*2]=(byte)value;
                    bytes[i*2+1]=(byte)(value>>>8);
                }
                int offset=0;
                while(offset<bytes.length && !stop.get()) {
                    int n=Math.min(max,bytes.length-offset);
                    if(callback.audioAvailable(bytes,offset,n)!=TextToSpeech.SUCCESS)return false;
                    offset+=n;
                }
                return !stop.get();
            });
            if(!stop.get())callback.done();
        } catch(Exception e) {
            android.util.Log.e("AntaliaTtsService","Synthesis failed",e);
            if(!stop.get())callback.error();
        } finally {
            if(lock!=null&&lock.isHeld())lock.release();
        }
    }

    @Override protected void onStop() { cancelled.set(true); }

    @Override public void onDestroy() {
        cancelled.set(true);
        // Retain model sessions for subsequent service and UI requests in this process.
        super.onDestroy();
    }
}
