package com.osman.zonos3tts;

import android.app.Activity;
import android.content.Intent;
import android.media.*;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int PICK_MODEL=7001;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private TextView status;
    private EditText text;
    private Button importButton,loadButton,speakButton,stopButton;
    private AudioTrack track;

    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
    private TextView label(String s,int sp){TextView v=new TextView(this);v.setText(s);v.setTextSize(sp);v.setPadding(0,dp(8),0,dp(8));return v;}
    private void status(String s){runOnUiThread(()->status.setText(s));}
    private void busy(boolean b){runOnUiThread(()->{importButton.setEnabled(!b);loadButton.setEnabled(!b);speakButton.setEnabled(!b);stopButton.setEnabled(b);});}

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        ScrollView scroll=new ScrollView(this);
        LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setPadding(dp(18),dp(18),dp(18),dp(24));scroll.addView(page);
        page.addView(label("ZONOS3 Mini TR · Android Q2 deneyi",23));
        page.addView(label("Model APK'da değil. ZIP'i bir kez seç; uygulama özel depolamada tutar ve APK güncellemelerinde tekrar istemez.",14));
        importButton=new Button(this);importButton.setText("Model ZIP seç / değiştir");page.addView(importButton);
        loadButton=new Button(this);loadButton.setText("Modeli yükle ve doğrula");page.addView(loadButton);
        text=new EditText(this);text.setMinLines(4);text.setText("Merhaba. Bu, ZONOS üç Mini Türkçe Android denemesidir.");page.addView(text,new LinearLayout.LayoutParams(-1,dp(150)));
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);
        speakButton=new Button(this);speakButton.setText("Seslendir");stopButton=new Button(this);stopButton.setText("Durdur");stopButton.setEnabled(false);
        row.addView(speakButton,new LinearLayout.LayoutParams(0,-2,1));row.addView(stopButton,new LinearLayout.LayoutParams(0,-2,1));page.addView(row);
        status=label("Model durumu kontrol ediliyor…",13);page.addView(status);
        setContentView(scroll);

        importButton.setOnClickListener(v->{
            Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/zip");
            i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/zip","application/octet-stream","application/x-zip-compressed"});
            startActivityForResult(i,PICK_MODEL);
        });
        loadButton.setOnClickListener(v->worker.execute(()->{
            busy(true);
            try{
                ModelPackage.Installed p=new ModelPackage(this).current();
                status("SHA doğrulandı: "+p.modelId+" / "+p.variant+"\nNative model yükleniyor…");
                ZonosEngine.get(this).ensureLoaded(Math.min(4,Runtime.getRuntime().availableProcessors()));
                status("Hazır · "+p.variant+" · "+String.format(java.util.Locale.ROOT,"%.1f MB",(p.backbone.length()+p.mmproj.length())/1048576.0));
            }catch(Throwable e){status("Hata: "+e.getMessage());}
            finally{busy(false);}
        }));
        speakButton.setOnClickListener(v->worker.execute(()->{
            busy(true);
            try{
                byte[] pcm=ZonosEngine.get(this).synthesize(text.getText().toString(),Math.min(4,Runtime.getRuntime().availableProcessors()));
                int rate=ZonosEngine.get(this).sampleRate(4);
                play(pcm,rate);
                status("Ses üretildi · "+String.format(java.util.Locale.ROOT,"%.2f sn",pcm.length/(rate*2.0)));
            }catch(Throwable e){status("Seslendirme hatası: "+e.getMessage());}
            finally{busy(false);}
        }));
        stopButton.setOnClickListener(v->{ZonosEngine.get(this).cancel();AudioTrack t=track;if(t!=null){try{t.stop();}catch(Exception ignored){}} status("Durduruldu.");});
        worker.execute(()->{
            try{ModelPackage.Installed p=new ModelPackage(this).current();status("Kurulu model: "+p.modelId+" / "+p.variant);}
            catch(Exception e){status("Model kurulu değil. Önce model ZIP'ini seç.");}
        });
    }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode!=PICK_MODEL||resultCode!=RESULT_OK||data==null||data.getData()==null)return;
        Uri uri=data.getData();
        worker.execute(()->{
            busy(true);status("ZIP açılıyor ve SHA-256 doğrulanıyor…");
            try{
                ModelPackage.Installed p=new ModelPackage(this).importZip(uri);
                ZonosEngine.get(this).reset();
                status("Model içe aktarıldı: "+p.modelId+" / "+p.variant+"\nToplam "+String.format(java.util.Locale.ROOT,"%.1f MB",(p.backbone.length()+p.mmproj.length())/1048576.0));
            }catch(Throwable e){status("İçe aktarma hatası: "+e.getMessage());}
            finally{busy(false);}
        });
    }

    private void play(byte[] pcm,int rate) throws Exception{
        if(pcm==null||pcm.length==0)throw new IllegalStateException("Model boş PCM döndürdü");
        int min=AudioTrack.getMinBufferSize(rate,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack t=new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(Math.max(min,32768)).build();
        AudioTrack old=track;track=t;if(old!=null)old.release();t.play();t.write(pcm,0,pcm.length,AudioTrack.WRITE_BLOCKING);
    }

    @Override protected void onDestroy(){super.onDestroy();worker.shutdownNow();AudioTrack t=track;if(t!=null)t.release();}
}
