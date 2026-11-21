package com.osman.zonos3tts;

import android.content.Context;

final class ZonosEngine {
    private static ZonosEngine instance;
    private final Context context;
    private long handle;
    private String loadedKey="";

    private ZonosEngine(Context context) { this.context=context.getApplicationContext(); }

    static synchronized ZonosEngine get(Context context) {
        if (instance==null) instance=new ZonosEngine(context);
        return instance;
    }

    synchronized ModelPackage.Installed installed() throws Exception {
        return new ModelPackage(context).current();
    }

    synchronized void ensureLoaded(int threads) throws Exception {
        ModelPackage.Installed p=installed();
        String key=p.backbone.getAbsolutePath()+"|"+p.mmproj.getAbsolutePath()+"|"+p.backbone.lastModified()+"|"+p.mmproj.lastModified();
        if (handle!=0 && key.equals(loadedKey)) return;
        closeLocked();
        long h=ZonosNative.create(p.backbone.getAbsolutePath(),p.mmproj.getAbsolutePath(),Math.max(1,Math.min(8,threads)));
        if (h==0) throw new IllegalStateException("ZONOS3 native model yüklenemedi");
        handle=h; loadedKey=key;
    }

    synchronized byte[] synthesize(String text,int threads) throws Exception {
        ensureLoaded(threads);
        if (text==null || text.trim().isEmpty()) throw new IllegalArgumentException("Metin boş");
        return ZonosNative.synthesize(handle,text.trim());
    }

    synchronized int sampleRate(int threads) throws Exception {
        ensureLoaded(threads);
        return ZonosNative.sampleRate(handle);
    }

    synchronized void cancel() {
        if (handle!=0) ZonosNative.cancel(handle);
    }

    synchronized void reset() { closeLocked(); }

    private void closeLocked() {
        if (handle!=0) {
            ZonosNative.destroy(handle);
            handle=0;
        }
        loadedKey="";
    }
}
