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

/** Executes the pinned upstream normalize.js and dictionaries in Android WebView. */
final class AntaliaNormalizer {
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());

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

    String normalize(String text) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("Normalizer must run off the UI thread");
        }
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> value = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        main.post(() -> {
            WebView web = null;
            try {
                web = new WebView(context);
                web.getSettings().setJavaScriptEnabled(true);
                final WebView view = web;
                String boot = asset("normalize.js")
                    + "\nAntaliaText.setData({foreign:" + asset("foreign_dict.json")
                    + ",parts:" + asset("foreign_parts.json")
                    + ",known:" + asset("known_words.json")
                    + ",englishI:" + asset("english_i.json") + "});";
                view.evaluateJavascript(boot, ignored -> {
                    view.evaluateJavascript("AntaliaText.toModelText(" + JSONObject.quote(text) + ")", js -> {
                        try {
                            Object decoded = new JSONTokener(js).nextValue();
                            if (!(decoded instanceof String)) throw new IllegalStateException("Invalid normalizer output");
                            value.set((String) decoded);
                        } catch (Throwable e) { error.set(e); }
                        finally { view.destroy(); latch.countDown(); }
                    });
                });
            } catch (Throwable e) {
                error.set(e);
                if (web != null) web.destroy();
                latch.countDown();
            }
        });
        if (!latch.await(45, TimeUnit.SECONDS)) throw new IllegalStateException("Antalia normalize.js timeout");
        if (error.get() != null) throw new IllegalStateException("Antalia text normalizer failed", error.get());
        String result = value.get();
        if (result == null) throw new IllegalStateException("Empty normalizer callback");
        return result;
    }
}
