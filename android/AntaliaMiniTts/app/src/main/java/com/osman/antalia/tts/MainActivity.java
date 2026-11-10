package com.osman.antalia.tts;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.Manifest;
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
    private TextView status;
    private EditText input;
    private Switch fastMode;
    private RadioGroup threadChoices;
    private Button speakButton, stopButton, testButton, shareButton;
    private Switch keepAlive;
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
        page.addView(title("8 adım daha doğal olabilir; 4 adım daha hızlıdır. Aynı flow grafiği farklı sayıda çalıştırılır.",13));
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
        page.addView(title("Arka plan · Termux yöntemi",23));
        keepAlive = new Switch(this);
        keepAlive.setText("Ekran kapalıyken TTS motorunu hazır tut");
        keepAlive.setTextSize(18);
        keepAlive.setChecked(AntaliaBackgroundService.isGuardActive());
        page.addView(keepAlive);
        page.addView(title("Termux gibi kalıcı bildirim + işlemci WakeLock kullanır. Açıkken pil tüketimi artabilir. Sistem uygulamayı yine de durdurabilir.",13));
        Button battery = new Button(this);
        battery.setText("Tecno / Android pil yönetimi");
        page.addView(battery);
        battery.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception unavailable) {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
            }
        });
        final boolean[] syncingToggle = {false};
        keepAlive.setOnCheckedChangeListener((button, enabled) -> {
            if (syncingToggle[0]) return;
            try {
                Intent cmd = AntaliaBackgroundService.command(this, enabled
                    ? AntaliaBackgroundService.ACTION_GUARD_ON
                    : AntaliaBackgroundService.ACTION_GUARD_OFF);
                if (enabled) {
                    startForegroundService(cmd);   // user is in a visible Activity
                    requestNotificationPermission();
                    message("Arka plan koruması açık. Bildirimden kapatabilirsiniz.");
                } else {
                    startService(cmd);
                    message("Arka plan koruması kapalı.");
                }
            } catch (Exception error) {
                syncingToggle[0] = true;
                keepAlive.setChecked(!enabled);
                syncingToggle[0] = false;
                message("Arka plan servisi başlatılamadı: " + error.getMessage());
            }
        });
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
    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
            && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 77);
    }

    private void stopPlayback() {
        // Preview AudioTrack belongs to the long-running service, not this Activity.
        try {
            startService(AntaliaBackgroundService.command(this,
                AntaliaBackgroundService.ACTION_STOP_READ));
            message("Okuma durduruluyor.");
            stopButton.setEnabled(false);
        } catch (Exception e) {
            message("Durdurulamadı: " + e.getMessage());
        }
    }

    private void speak() {
        String speech = input.getText().toString();
        if (speech.trim().isEmpty()) { message("Lütfen metin girin."); return; }
        Intent cmd = AntaliaBackgroundService.command(this, AntaliaBackgroundService.ACTION_READ);
        cmd.putExtra(AntaliaBackgroundService.EXTRA_TEXT, speech);
        cmd.putExtra(AntaliaBackgroundService.EXTRA_STEPS, fastMode.isChecked() ? 4 : 8);
        cmd.putExtra(AntaliaBackgroundService.EXTRA_THREADS, threads);
        try {
            startForegroundService(cmd); // visible/user-initiated; no background-start bypass
            requestNotificationPermission();
            stopButton.setEnabled(true);
            message("Seslendirme arka plan servisinde başladı. Ekranı kapatabilirsiniz.");
        } catch (Exception e) {
            message("Seslendirme başlatılamadı: " + e.getMessage());
        }
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
