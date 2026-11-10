package com.osman.antalia.tts;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One reusable in-process Chromium normalizer, initialized once.
 * Must be used off main thread; WebView itself remains main-thread-owned.
 */
final class AntaliaNormalizer {
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private WebView view;
    private volatile boolean ready;

    AntaliaNormalizer(Context context) { this.context = context.getApplicationContext(); }

    private String asset(String path) throws Exception {
        try (InputStream in = context.getAssets().open("antalia/" + path)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] data = new byte[16 * 1024];
            int n;
            while ((n = in.read(data)) != -1) out.write(data, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    void prepare() throws Exception {
        if (ready) return;
        if (Looper.myLooper() == Looper.getMainLooper())
            throw new IllegalStateException("Antalia normalizer preparation must not block UI");
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        main.post(() -> {
            try {
                if (view == null) {
                    view = new WebView(context);
                    view.getSettings().setJavaScriptEnabled(true);
                }
                String boot = asset("normalize.js")
                    + "\nAntaliaText.setData({foreign:" + asset("foreign_dict.json")
                    + ",parts:" + asset("foreign_parts.json")
                    + ",known:" + asset("known_words.json")
                    + ",englishI:" + asset("english_i.json") + "});"
                    + "\nBoolean(typeof AntaliaText !== 'undefined' && typeof AntaliaText.toModelText === 'function')";
                view.evaluateJavascript(boot, js -> {
                    if ("true".equals(js)) ready = true;
                    else error.set(new IllegalStateException("Antalia normalize.js initialization failed: " + js));
                    latch.countDown();
                });
            } catch (Throwable e) {
                error.set(e);
                if (view != null) { view.destroy(); view = null; }
                latch.countDown();
            }
        });
        if (!latch.await(45, TimeUnit.SECONDS)) throw new IllegalStateException("Antalia normalizer initialization timed out");
        if (error.get() != null) throw new IllegalStateException("Antalia normalizer initialization error", error.get());
    }

    String normalize(String text) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper())
            throw new IllegalStateException("Antalia normalizer must run off UI thread");
        prepare();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> value = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        main.post(() -> {
            try {
                view.evaluateJavascript("AntaliaText.toModelText(" + JSONObject.quote(text) + ")", js -> {
                    try {
                        Object decoded = new JSONTokener(js).nextValue();
                        if (!(decoded instanceof String)) throw new IllegalStateException("Invalid Antalia normalizer output");
                        value.set((String) decoded);
                    } catch (Throwable e) { error.set(e); }
                    finally { latch.countDown(); }
                });
            } catch (Throwable e) {
                error.set(e);
                latch.countDown();
            }
        });
        if (!latch.await(45, TimeUnit.SECONDS)) throw new IllegalStateException("Antalia normalize.js response timed out");
        if (error.get() != null) throw new IllegalStateException("Antalia normalizer failed", error.get());
        if (value.get() == null) throw new IllegalStateException("Empty Antalia normalize.js result");
        return value.get();
    }
}
