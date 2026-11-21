package com.osman.zonos3tts;

import android.media.AudioFormat;
import android.speech.tts.*;
import java.util.Locale;

public final class ZonosTtsService extends TextToSpeechService {
    private volatile boolean stopped;

    @Override protected int onIsLanguageAvailable(String lang,String country,String variant){
        return "tr".equalsIgnoreCase(lang) ? TextToSpeech.LANG_COUNTRY_AVAILABLE : TextToSpeech.LANG_NOT_SUPPORTED;
    }

    @Override protected String[] onGetLanguage(){return new String[]{"tr","TR",""};}

    @Override protected int onLoadLanguage(String lang,String country,String variant){
        return onIsLanguageAvailable(lang,country,variant);
    }

    @Override protected void onStop(){
        stopped=true;
        ZonosEngine.get(this).cancel();
    }

    @Override protected void onSynthesizeText(SynthesisRequest request,SynthesisCallback callback){
        stopped=false;
        String text=request.getCharSequenceText()==null?"":request.getCharSequenceText().toString();
        try{
            ZonosEngine engine=ZonosEngine.get(this);
            byte[] pcm=engine.synthesize(text,Math.min(4,Runtime.getRuntime().availableProcessors()));
            int rate=engine.sampleRate(4);
            if(stopped){callback.done();return;}
            if(callback.start(rate,AudioFormat.ENCODING_PCM_16BIT,1)!=TextToSpeech.SUCCESS){return;}
            int max=Math.max(1024,Math.min(callback.getMaxBufferSize(),16384));
            for(int off=0;off<pcm.length&&!stopped;off+=max){
                int n=Math.min(max,pcm.length-off);
                int r=callback.audioAvailable(pcm,off,n);
                if(r!=TextToSpeech.SUCCESS)break;
            }
            callback.done();
        }catch(Throwable e){
            callback.error(TextToSpeech.ERROR_SYNTHESIS);
        }
    }
}
