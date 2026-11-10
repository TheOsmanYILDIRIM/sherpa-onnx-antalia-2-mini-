package com.osman.antalia.tts;

import android.app.Activity;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainActivity extends Activity {
    private static final String PREFS = "antalia_settings";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private AntaliaCore engine;
    private AudioTrack track;
    private TextView status;
    private EditText input;
    private Switch fastMode;
    private RadioGroup threadChoices;
    private Button speakButton, stopButton, testButton, shareButton;
    private String results = "";
    private int threads = 4;

    private int dp(int n) { return (int)(n * getResources().getDisplayMetrics().density + .5f); }
    private TextView title(String s, int sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setPadding(0,dp(14),0,dp(7));
        return t;
    }
    private static byte[] pcm(float[] data) {
        byte[] bytes=new byte[data.length*2];
        for(int i=0;i<data.length;i++) {
            float value = Float.isFinite(data[i]) ? Math.max(-1f,Math.min(1f,data[i])) : 0;
            short sample=(short)Math.round(value*32767);
            bytes[i*2]=(byte)sample;
            bytes[i*2+1]=(byte)(sample>>>8);
        }
        return bytes;
    }
    private void message(String s) { runOnUiThread(() -> status.setText(s)); }
    private void busy(boolean enabled) {
        runOnUiThread(() -> {
            speakButton.setEnabled(!enabled);
            testButton.setEnabled(!enabled);
            stopButton.setEnabled(enabled);
            fastMode.setEnabled(!enabled);
            for(int i=0;i<threadChoices.getChildCount();i++)threadChoices.getChildAt(i).setEnabled(!enabled);
        });
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        engine=AntaliaCore.shared(this);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        ScrollView scroll=new ScrollView(this);
        LinearLayout page=new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18),dp(14),dp(18),dp(20));
        scroll.addView(page);
        page.addView(title("Antalia-2 Mini 1.0.1",24));
        page.addView(title("Çevrimdışı Türkçe seslendirme • 48 kHz • ARM64",14));
        input=new EditText(this);
        input.setSingleLine(false);
        input.setMinLines(4);
        input.setText("Merhaba! Antalia iki Mini Türkçe seslendirme hız testine hoş geldiniz.");
        input.setHint("Okunacak metni yazın");
        page.addView(input,new LinearLayout.LayoutParams(-1,dp(145)));
        LinearLayout controls=new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        speakButton=new Button(this);speakButton.setText("Seslendir");
        stopButton=new Button(this);stopButton.setText("Durdur");stopButton.setEnabled(false);
        controls.addView(speakButton,new LinearLayout.LayoutParams(0,-2,1));
        controls.addView(stopButton,new LinearLayout.LayoutParams(0,-2,1));
        page.addView(controls);
        page.addView(title("Ayarlar",23));
        fastMode=new Switch(this);
        fastMode.setText("Hızlı mod (4 sentez adımı)");
        fastMode.setTextSize(18);
        fastMode.setChecked(getSharedPreferences(PREFS,MODE_PRIVATE).getBoolean("fast",true));
        fastMode.setOnCheckedChangeListener((v,yes) -> getSharedPreferences(PREFS,MODE_PRIVATE).edit().putBoolean("fast",yes).apply());
        page.addView(fastMode);
        page.addView(title("8 adım daha doğal olabilir; 4 adım daha hızlıdır. Aynı model, gerçek iki ayrı ses grafiği.",13));
        page.addView(title("İşlemci iş parçacıkları",20));
        threadChoices=new RadioGroup(this);
        threadChoices.setOrientation(RadioGroup.HORIZONTAL);
        threads=getSharedPreferences(PREFS,MODE_PRIVATE).getInt("threads",4);
        for(int n:new int[]{1,2,4}) {
            RadioButton rb=new RadioButton(this);
            rb.setId(View.generateViewId());
            rb.setText(String.valueOf(n));
            rb.setTag(n);
            rb.setPadding(dp(9),0,dp(9),0);
            threadChoices.addView(rb,new RadioGroup.LayoutParams(0,-2,1));
            if(threads==n) rb.setChecked(true);
        }
        threadChoices.setOnCheckedChangeListener((group,checkedId) -> {
            View selected=group.findViewById(checkedId);
            if(selected!=null && selected.getTag() instanceof Integer) {
                threads=(Integer)selected.getTag();
                getSharedPreferences(PREFS,MODE_PRIVATE).edit().putInt("threads",threads).apply();
            }
        });
        page.addView(threadChoices);
        page.addView(title("Bu telefonda "+Runtime.getRuntime().availableProcessors()+" işlemci çekirdeği var. Daha çok iş parçacığı her zaman daha hızlı olmayabilir.",13));
        page.addView(title("Hız testi",23));
        page.addView(title("8 ve 4 adımda, 1 ve 4 iş parçacığıyla ilk sese kadar süreyi ve toplam hızı ölçer. Test sırasında ekranı açık tutun.",14));
        testButton=new Button(this);testButton.setText("Hız testini başlat");
        shareButton=new Button(this);shareButton.setText("Sonuçları paylaş");shareButton.setEnabled(false);
        page.addView(testButton);
        page.addView(shareButton);
        status=title("Hazır. İlk seslendirmede modeller hazırlanır.",14);
        page.addView(status);
        page.addView(title("Antalia-2 Mini 1.0.1 · PatientDesk AI · Apache-2.0 · çıkarım ONNX Runtime · geliştirme Sherpa-ONNX forkunda.",12));
        setContentView(scroll);
        speakButton.setOnClickListener(v -> speak());
        stopButton.setOnClickListener(v -> stopPlayback());
        testButton.setOnClickListener(v -> benchmark());
        shareButton.setOnClickListener(v -> {
            Intent send=new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_TEXT,results);
            startActivity(Intent.createChooser(send,"Hız testini paylaş"));
        });
        // Start loading selected models immediately while the user reads/enters text.
        // Uses the same serial worker as Speak/Benchmark and the process-wide core.
        final int warmSteps = fastMode.isChecked() ? 4 : 8;
        final int warmThreads = threads;
        worker.execute(() -> {
            try {
                engine.prepare(warmSteps, warmThreads);
                message("Motor hazır. Seslendirmeye başlayabilirsiniz.");
            } catch (Exception e) {
                message("Motor hazırlama hatası: " + e.getMessage());
            }
        });
    }
    private void stopPlayback() {
        cancelled.set(true);
        if(track!=null) try { track.pause();track.flush(); } catch(Exception ignored){}
        message("Durduruldu.");
    }
    private AudioTrack newTrack() {
        int size=AudioTrack.getMinBufferSize(AntaliaCore.RATE,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack t=new AudioTrack(AudioManager.STREAM_MUSIC,AntaliaCore.RATE,
            AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(16384,size),AudioTrack.MODE_STREAM);
        if(t.getState()!=AudioTrack.STATE_INITIALIZED)throw new IllegalStateException("AudioTrack init failed");
        t.play();
        return t;
    }
    private void speak() {
        String source=input.getText().toString();
        if(source.trim().isEmpty()){message("Lütfen metin girin.");return;}
        cancelled.set(false);
        boolean fast=fastMode.isChecked();
        int n=threads;
        busy(true);
        message("Modeller hazırlanıyor; seslendirme başlatılıyor…");
        worker.execute(() -> {
            PowerManager.WakeLock lock=null;
            try {
                PowerManager power=(PowerManager)getSystemService(POWER_SERVICE);
                lock=power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"antalia:preview");
                lock.acquire(10*60*1000L);
                track=newTrack();
                long start=SystemClock.elapsedRealtimeNanos();
                AtomicBoolean first=new AtomicBoolean(false);
                engine.speak(source,fast?4:8,n,.95f,cancelled,audio -> {
                    if(cancelled.get())return false;
                    if(first.compareAndSet(false,true))message(String.format(Locale.ROOT,
                        "İlk ses: %.2f sn · %d adım · %d iş parçacığı",
                        (SystemClock.elapsedRealtimeNanos()-start)/1e9,fast?4:8,n));
                    byte[] bytes=pcm(audio);
                    int offset=0;
                    while(offset<bytes.length&&!cancelled.get()) {
                        int take=Math.min(16384,bytes.length-offset);
                        int written=track.write(bytes,offset,take);
                        if(written<=0)return false;
                        offset+=written;
                    }
                    return !cancelled.get();
                });
                if(!cancelled.get())message("Seslendirme tamamlandı.");
            } catch(Exception e) { message("Seslendirme hatası: "+e.getMessage()); }
            finally {
                if(track!=null){try {track.stop();}catch(Exception ignored){}track.release();track=null;}
                if(lock!=null&&lock.isHeld())lock.release();
                busy(false);
            }
        });
    }
    private void benchmark() {
        cancelled.set(false);
        busy(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        String sample="Merhaba, bugün hava güzel. Türkçe seslendirme hızını ölçüyoruz.";
        worker.execute(() -> {
            StringBuilder report=new StringBuilder("Antalia-2 Mini 1.0.1 ARM64 hız testi\n");
            try {
                for(int steps:new int[]{8,4})for(int n:new int[]{1,4}) {
                    if(cancelled.get())break;
                    // Prepare before timing to measure real synthesis rather than repeatedly
                    // reopening all ONNX graphs during the same benchmark.
                    engine.prepare(steps,n);
                    final long start=SystemClock.elapsedRealtimeNanos();
                    final long[] first={-1},samples={0};
                    message("Test: "+steps+" adım · "+n+" iş parçacığı");
                    engine.speak(sample,steps,n,.95f,cancelled,audio -> {
                        if(first[0]<0)first[0]=SystemClock.elapsedRealtimeNanos();
                        samples[0]+=audio.length;
                        return !cancelled.get();
                    });
                    double total=(SystemClock.elapsedRealtimeNanos()-start)/1e9;
                    double firstSec=first[0]<0?Double.NaN:(first[0]-start)/1e9;
                    double duration=samples[0]/(double)AntaliaCore.RATE;
                    report.append(String.format(Locale.ROOT,
                        "%d adım / %d iş: ilk ses %.3f sn · toplam %.3f sn · RTF %.3f\n",
                        steps,n,firstSec,total,duration>0?total/duration:Double.NaN));
                    report.append("    ").append(engine.lastProfile()).append("\n");
                }
                results=report.toString();
                message(results);
                runOnUiThread(()->shareButton.setEnabled(!cancelled.get()));
            } catch(Exception e) {message("Hız testi hatası: "+e.getMessage());}
            finally{
                runOnUiThread(()->getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
                busy(false);
            }
        });
    }
    @Override public void onDestroy() {
        cancelled.set(true);worker.shutdownNow();
        // The shared engine also serves the Android system TTS service.
        super.onDestroy();
    }
}
