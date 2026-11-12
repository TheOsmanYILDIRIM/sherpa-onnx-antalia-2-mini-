package com.osman.antalia.tts;

import android.content.Context;
import ai.onnxruntime.OnnxJavaType;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import android.os.SystemClock;
import android.util.Log;

/**
 * 1.0.1 three-stage ONNX inference runner, located in the Sherpa-ONNX fork Android app.
 * Does not claim the unmodified sherpa-onnx OfflineTts model enum supports Antalia.
 */
final class AntaliaCore implements AutoCloseable {
    static final int RATE = 48000;
    private static final int HOP = 512;
    private static final int HIDDEN = 192;
    private static final int BINS = 128;
    private static final int STACK = 2;
    private final Context context;
    private final AntaliaNormalizer normalizer;
    private final OrtEnvironment env;
    private File modelDir;
    private JSONObject dictionary;
    private OrtSession text;
    private OrtSession conditioning;
    private OrtSession flow;
    private OrtSession vocoder;
    private volatile String lastProfile = "";
    private OrtSession.SessionOptions options;
    private int currentThreads = -1;
    private int currentSteps = -1;
    private static AntaliaCore singleton;
    private static final ScheduledExecutorService RUN_WATCHDOG =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread worker = new Thread(r, "antalia-onnx-watchdog");
            worker.setDaemon(true);
            return worker;
        });
    private final ConcurrentHashMap<String, Long> typicalStageMs = new ConcurrentHashMap<>();

    /** One engine per app process; shared with Android TTS service. */
    static synchronized AntaliaCore shared(Context context) {
        if (singleton == null) singleton = new AntaliaCore(context);
        return singleton;
    }

    interface AudioSink { boolean deliver(float[] audio); }

    private static Map<String, OnnxTensor> feed(Object... pairs) {
        Map<String, OnnxTensor> inputs = new java.util.HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            inputs.put((String)pairs[i], (OnnxTensor)pairs[i + 1]);
        }
        return inputs;
    }

    AntaliaCore(Context context) {
        this.context = context.getApplicationContext();
        normalizer = new AntaliaNormalizer(this.context);
        env = OrtEnvironment.getEnvironment();
    }

    private static String sha256(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new java.io.FileInputStream(file)) {
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) != -1) md.update(buffer, 0, n);
        }
        StringBuilder b = new StringBuilder();
        for (byte value : md.digest()) b.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return b.toString();
    }

    private void install() throws Exception {
        if (modelDir != null) return;
        File dir = new File(context.getFilesDir(), "antalia_101_split_fp16");
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Cannot create private model directory");
        String[] models = {"text.onnx", "cond.onnx", "flow.onnx", "vocoder.fp16.onnx"};
        String manifest;
        try (InputStream in = context.getAssets().open("antalia/graph_manifest.json")) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096]; int n;
            while ((n = in.read(buf)) != -1) bytes.write(buf, 0, n);
            manifest = bytes.toString("UTF-8");
        }
        JSONObject stages = new JSONObject(manifest).getJSONObject("sha256");
        for (String filename : models) {
            String expected = stages.getString(filename);
            File dest = new File(dir, filename);
            if (!dest.isFile() || !expected.equals(sha256(dest))) {
                File temp = new File(dir, filename + ".part");
                try (InputStream input = context.getAssets().open("antalia/" + filename);
                     FileOutputStream output = new FileOutputStream(temp)) {
                    byte[] buf = new byte[16384]; int n;
                    while ((n = input.read(buf)) != -1) output.write(buf, 0, n);
                    output.getFD().sync();
                }
                if (!expected.equals(sha256(temp))) throw new IllegalStateException("Model SHA-256 mismatch: " + filename);
                if (dest.exists() && !dest.delete()) throw new IllegalStateException("Cannot replace stale model: " + filename);
                if (!temp.renameTo(dest)) throw new IllegalStateException("Cannot finalize model: " + filename);
            }
        }
        try (InputStream in = context.getAssets().open("antalia/vocab.json")) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096]; int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            dictionary = new JSONObject(out.toString("UTF-8")).getJSONObject("stoi");
        }
        modelDir = dir;
    }

    private static void closeSession(OrtSession session) {
        if (session != null) try { session.close(); } catch (Exception ignored) {}
    }

    private void closeSessions() {
        closeSession(vocoder); closeSession(flow); closeSession(conditioning); closeSession(text);
        vocoder = flow = conditioning = text = null;
        if (options != null) try { options.close(); } catch (Exception ignored) {}
        options = null;
        currentThreads = -1;
    }

    private void open(int steps, int threads) throws Exception {
        if (steps != 4 && steps != 8) throw new IllegalArgumentException("Flow steps must be 4 or 8");
        if (threads != 1 && threads != 2 && threads != 4) throw new IllegalArgumentException("Threads must be 1, 2 or 4");
        install();
        // The *same* flow graph is reused for 4 and 8 steps. Do not reload sessions
        // when switching fast mode, only when intra-op thread count changes.
        if (currentThreads == threads && text != null && flow != null) return;
        closeSessions();
        options = new OrtSession.SessionOptions();
        options.setIntraOpNumThreads(threads);
        options.setInterOpNumThreads(1);
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        try {
            text = env.createSession(new File(modelDir, "text.onnx").getAbsolutePath(), options);
            conditioning = env.createSession(new File(modelDir, "cond.onnx").getAbsolutePath(), options);
            flow = env.createSession(new File(modelDir, "flow.onnx").getAbsolutePath(), options);
            vocoder = env.createSession(new File(modelDir, "vocoder.fp16.onnx").getAbsolutePath(), options);
            currentThreads = threads;
        } catch (Exception e) {
            closeSessions();
            throw e;
        }
    }

    synchronized void prepare(int steps, int threads) throws Exception {
        open(steps, threads);
        normalizer.prepare();
    }

    String lastProfile() { return lastProfile; }

    private long[] tokens(String normalized) throws Exception {
        long[] ids = new long[normalized.length() + 2];
        ids[0] = 1;
        for (int i = 0; i < normalized.length(); i++) {
            String key = String.valueOf(normalized.charAt(i));
            if (!dictionary.has(key)) throw new IllegalArgumentException("Unsupported normalized symbol: " + key);
            ids[i + 1] = dictionary.getLong(key);
        }
        ids[ids.length - 1] = 2;
        return ids;
    }

    private static List<String> chunks(String text) {
        List<String> list = new ArrayList<>();
        StringBuilder chunk = new StringBuilder();
        for (String word : text.trim().split("\\s+")) {
            if (word.isEmpty()) continue;
            if (word.length() > 110) throw new IllegalArgumentException("Single token exceeds safe Antalia chunk length");
            if (chunk.length() > 0 && chunk.length() + word.length() + 1 > 110) {
                list.add(chunk.toString()); chunk.setLength(0);
            }
            if (chunk.length() > 0) chunk.append(' ');
            chunk.append(word);
            if (word.endsWith(".") || word.endsWith("!") || word.endsWith("?")) {
                list.add(chunk.toString()); chunk.setLength(0);
            }
        }
        if (chunk.length() > 0) list.add(chunk.toString());
        return list;
    }

    private static final class Tone {
        private static final double FLOOR = Math.pow(10, -1.0 / 20);
        private static final double CEIL = Math.pow(10, -0.1 / 20);
        private static final double ROOM = CEIL - FLOOR;
        private final Biquad low = new Biquad(1.0067698504364737,-1.9670261142112211,0.9613064506769197,-1.9672880375281616,0.9678143777964527);
        private final Biquad high = new Biquad(0.4282380647227790,-0.4673907653709056,0.1672663299532398,-1.4337659737473694,0.5618796030524825);
        float[] process(float[] raw) {
            for (int i=0;i<raw.length;i++) {
                double y=high.apply(low.apply(raw[i]));
                double a=Math.abs(y);
                if (a>FLOOR) y=Math.signum(y)*(FLOOR+ROOM*Math.tanh((a-FLOOR)/ROOM));
                raw[i]=(float)y;
            }
            return raw;
        }
    }
    private static final class Biquad {
        private final double b0,b1,b2,a1,a2;
        private double x1,x2,y1,y2;
        Biquad(double b0,double b1,double b2,double a1,double a2) {
            this.b0=b0;this.b1=b1;this.b2=b2;this.a1=a1;this.a2=a2;
        }
        double apply(double x) {
            double y=b0*x+b1*x1+b2*x2-a1*y1-a2*y2;
            x2=x1;x1=x;y2=y1;y1=y;return y;
        }
    }

    /** Per-stage deadline with learned timings; ONNX termination avoids concurrent native runs. */
    private OrtSession.Result runWatched(OrtSession session, Map<String, OnnxTensor> inputs,
                                         AtomicBoolean stop, String stage, int complexity) throws Exception {
        if (stop.get()) throw new java.util.concurrent.CancellationException("TTS stopped");
        long floorMs = "vocoder".equals(stage) ? 20000L : ("flow".equals(stage) ? 15000L : 12000L);
        long multiplier = Math.max(1L, (complexity + 499L) / 500L);
        long defaultMs = Math.min(90000L, floorMs * multiplier);
        Long baseline = typicalStageMs.get(stage);
        long budgetMs = Math.min(90000L, Math.max(defaultMs,
            baseline == null ? 0L : baseline * 4L + 3000L));
        long started = SystemClock.elapsedRealtime();
        AtomicBoolean active = new AtomicBoolean(true);
        AtomicBoolean timedOut = new AtomicBoolean(false);
        try (OrtSession.RunOptions runOptions = new OrtSession.RunOptions()) {
            ScheduledFuture<?> watcher = RUN_WATCHDOG.scheduleAtFixedRate(() -> {
                if (!active.get()) return;
                boolean timeout = SystemClock.elapsedRealtime() - started > budgetMs;
                if (timeout) timedOut.set(true);
                if (timeout || stop.get()) {
                    try { runOptions.setTerminate(true); }
                    catch (Exception e) { Log.w("AntaliaTtsWatchdog", "Cannot terminate " + stage, e); }
                }
            }, 250, 250, TimeUnit.MILLISECONDS);
            try {
                OrtSession.Result output = session.run(inputs, runOptions);
                if (stop.get() || timedOut.get()) {
                    output.close();
                    throw new java.util.concurrent.CancellationException(
                        timedOut.get() ? "ONNX " + stage + " timed out" : "TTS stopped");
                }
                long elapsed = Math.max(1L, SystemClock.elapsedRealtime() - started);
                typicalStageMs.merge(stage, elapsed, (a, b) -> (3L * a + b) / 4L);
                return output;
            } catch (Exception e) {
                if (timedOut.get()) Log.w("AntaliaTtsWatchdog",
                    "Stage " + stage + " timed out after " + budgetMs + "ms", e);
                throw e;
            } finally {
                active.set(false);
                watcher.cancel(false);
            }
        }
    }

    private float[] infer(String part, int steps, float speed, int chunkIndex,
                          AtomicBoolean stop) throws Exception {
        // This matches the original 1.0.1 four-stage split: text -> cond -> flow -> vocoder.
        // The flow model is *one* Euler step and gets invoked 4 or 8 times.
        final long startNanos = System.nanoTime();
        String prefix = (part.startsWith("ö") || part.startsWith("ı")) ? "ee, " : "";
        String suffix = " ee.";
        long[] ids = tokens(prefix + part + suffix);
        float[][] hidden;
        float[] logd;
        try (OnnxTensor idsTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), new long[]{1, ids.length});
             OrtSession.Result result = runWatched(text, Collections.singletonMap("ids", idsTensor), stop, "text", ids.length)) {
            hidden = ((float[][][])result.get("h").orElseThrow().getValue())[0];
            logd = ((float[][])result.get("logd").orElseThrow().getValue())[0];
        }
        long afterText = System.nanoTime();
        if (hidden.length != ids.length || logd.length != ids.length)
            throw new IllegalStateException("Text model tensor shape mismatch");
        for (int i = 2; i < ids.length - 1; i++) {
            if (ids[i] == ids[i - 1] && ids[i] != 3L) logd[i] += (float)Math.log(1.3);
            else if (ids[i - 1] == 3L && ids[i] == ids[i - 2]) logd[i - 1] += (float)Math.log(1.3);
        }

        int[] durations = new int[ids.length];
        int frames = 0;
        for (int i = 0; i < durations.length; i++) {
            if (!Float.isFinite(logd[i])) throw new IllegalStateException("Non-finite token duration");
            int duration = (int)Math.rint(Math.exp(logd[i]) / speed);  // round-half-to-even
            durations[i] = Math.max(1, Math.min(60, duration));
            frames += durations[i];
        }
        frames = ((frames + STACK - 1) / STACK) * STACK;
        if (frames < 2 || frames > 16000) throw new IllegalStateException("Invalid duration frames: " + frames);
        final int halfFrames = frames / STACK;
        long[] index = new long[frames];
        int pos = 0;
        for (int i = 0; i < ids.length; i++) {
            for (int j = 0; j < durations[i]; j++) index[pos++] = i;
        }
        while (pos < frames) index[pos++] = ids.length - 1;
        float[] flattenedHidden = new float[ids.length * HIDDEN];
        for (int i = 0; i < ids.length; i++) {
            if (hidden[i].length != HIDDEN) throw new IllegalStateException("Invalid text hidden size");
            System.arraycopy(hidden[i], 0, flattenedHidden, i * HIDDEN, HIDDEN);
        }

        float[][] ctok;
        float[][] cnull;
        try (OnnxTensor h = OnnxTensor.createTensor(env, FloatBuffer.wrap(flattenedHidden),
                                      new long[]{1, ids.length, HIDDEN});
             OnnxTensor idx = OnnxTensor.createTensor(env, LongBuffer.wrap(index),
                                        new long[]{1, frames});
             OrtSession.Result result = runWatched(conditioning, feed("h", h, "idx", idx), stop, "cond", frames)) {
            ctok = ((float[][][])result.get("ctok").orElseThrow().getValue())[0];
            cnull = ((float[][][])result.get("cnull").orElseThrow().getValue())[0];
        }
        long afterCond = System.nanoTime();
        if (ctok.length != halfFrames || cnull.length != halfFrames)
            throw new IllegalStateException("Conditioning frame mismatch");
        float[] conditioned = new float[halfFrames * HIDDEN];
        float[] unconditioned = new float[halfFrames * HIDDEN];
        for (int i = 0; i < halfFrames; i++) {
            if (ctok[i].length != HIDDEN || cnull[i].length != HIDDEN)
                throw new IllegalStateException("Conditioning hidden mismatch");
            System.arraycopy(ctok[i], 0, conditioned, i * HIDDEN, HIDDEN);
            System.arraycopy(cnull[i], 0, unconditioned, i * HIDDEN, HIDDEN);
        }

        // Keep a single seeded Gaussian noise sample across the entire Euler loop.
        float[] noise = new float[halfFrames * BINS * STACK];
        Random rng = new Random(0xA77A1AL + chunkIndex);
        for (int i = 0; i < noise.length; i++) noise[i] = (float)(rng.nextGaussian() * 0.6);
        float dtv = 1.0f / steps;
        try (OnnxTensor ctx = OnnxTensor.createTensor(env, FloatBuffer.wrap(conditioned),
                                            new long[]{1, halfFrames, HIDDEN});
             OnnxTensor nul = OnnxTensor.createTensor(env, FloatBuffer.wrap(unconditioned),
                                            new long[]{1, halfFrames, HIDDEN});
             OnnxTensor dt = OnnxTensor.createTensor(env, FloatBuffer.wrap(new float[]{dtv}),
                                            new long[]{1});
             OnnxTensor cfg = OnnxTensor.createTensor(env, FloatBuffer.wrap(new float[]{2.0f}),
                                             new long[]{1})) {
            for (int step = 0; step < steps; step++) {
                try (OnnxTensor x = OnnxTensor.createTensor(env, FloatBuffer.wrap(noise),
                                                 new long[]{1, halfFrames, BINS * STACK});
                     OnnxTensor t = OnnxTensor.createTensor(env, FloatBuffer.wrap(new float[]{step * dtv}),
                                                 new long[]{1});
                     OrtSession.Result result = runWatched(flow, feed("x", x, "ctok", ctx, "cnull", nul,
                                                              "t", t, "dt", dt, "cfg", cfg), stop, "flow", halfFrames)) {
                    float[][] next = ((float[][][])result.get("x_next").orElseThrow().getValue())[0];
                    if (next.length != halfFrames) throw new IllegalStateException("Flow frame mismatch");
                    for (int i = 0; i < halfFrames; i++) {
                        if (next[i].length != BINS * STACK) throw new IllegalStateException("Flow dimension mismatch");
                        System.arraycopy(next[i], 0, noise, i * BINS * STACK, BINS * STACK);
                    }
                }
            }
        }
        long afterFlow = System.nanoTime();

        float[] audio;
        long afterVocoder;
        try (OnnxTensor z = OnnxTensor.createTensor(env, FloatBuffer.wrap(noise),
                                            new long[]{1, halfFrames, BINS * STACK});
             OrtSession.Result result = runWatched(vocoder, Collections.singletonMap("z", z), stop, "vocoder", halfFrames)) {
            float[][] re = ((float[][][])result.get("re").orElseThrow().getValue())[0];
            float[][] im = ((float[][][])result.get("im").orElseThrow().getValue())[0];
            afterVocoder = System.nanoTime();
            audio = AntaliaIstft.invert(re, im);
        }
        long afterIstft = System.nanoTime();

        int prefixCount = prefix.length();
        int suffixCount = suffix.length();
        int first = 0, last = 0;
        for (int i = 0; i < durations.length; i++) {
            if (i <= prefixCount) first += durations[i];
            if (i <= ids.length - 2 - suffixCount) last += durations[i];
        }
        int from = prefixCount > 0 ? Math.max(0, first * HOP) : 0;
        int to = Math.min(audio.length, last * HOP);
        if (to <= from) throw new IllegalStateException("Invalid PCM crop");
        float[] clipped = Arrays.copyOfRange(audio, from, to);
        int fade = Math.min(240, clipped.length / 2);
        if (prefixCount > 0) for (int i = 0; i < fade; i++) clipped[i] *= i / (float)fade;
        for (int i = 0; i < fade; i++) clipped[clipped.length - 1 - i] *= i / (float)fade;

        lastProfile = String.format(java.util.Locale.ROOT,
            "text %.0fms · cond %.0fms · flow %.0fms · vocoder %.0fms · iSTFT %.0fms",
            (afterText - startNanos) / 1e6, (afterCond - afterText) / 1e6,
            (afterFlow - afterCond) / 1e6, (afterVocoder - afterFlow) / 1e6,
            (afterIstft - afterVocoder) / 1e6);
        return clipped;
    }

    synchronized void speak(String raw, int steps, int threads, float speed,
                            AtomicBoolean stop, AudioSink sink) throws Exception {
        speak(raw, steps, threads, speed, stop, sink, true);
    }

    /** External Android TTS requests suppress synthetic intra-request pauses.
     * The reader itself controls when successive requests are spoken. */
    synchronized void speak(String raw, int steps, int threads, float speed,
                            AtomicBoolean stop, AudioSink sink, boolean addPauses) throws Exception {
        if(steps!=4&&steps!=8)throw new IllegalArgumentException("Unsupported flow steps");
        if(!Float.isFinite(speed)||speed<=0)throw new IllegalArgumentException("Invalid speed");
        if(raw==null||raw.trim().isEmpty())return;
        open(steps,threads);
        Tone tone=new Tone();
        List<String> parts=chunks(raw);
        for(int i=0;i<parts.size();i++) {
            if(stop.get()||Thread.currentThread().isInterrupted())break;
            String cleaned=normalizer.normalize(parts.get(i));
            if(cleaned.isBlank())continue;
            float[] audio = null;
            for (int attempt = 0; attempt < 2 && !stop.get(); attempt++) {
                try {
                    audio = infer(cleaned, steps, speed, i, stop);
                    if (audio.length == 0) throw new IllegalStateException("Empty PCM output");
                    break;
                } catch (Exception failure) {
                    if (stop.get()) throw failure;
                    if (attempt == 1) throw failure;
                    Log.w("AntaliaTtsWatchdog", "Retrying chunk " + i + " after stage failure", failure);
                    closeSessions();
                    open(steps, threads);
                }
            }
            if (stop.get()) break;
            if (audio == null) throw new IllegalStateException("No PCM after retry");
            audio = tone.process(audio);
            if(!sink.deliver(audio))break;
            if(addPauses && i!=parts.size()-1 && !stop.get()) {
                char tail=parts.get(i).charAt(parts.get(i).length()-1);
                int pause=(tail=='.'||tail=='!'||tail=='?')?5760:2880;
                if(!sink.deliver(new float[pause]))break;
            }
        }
    }

    @Override public synchronized void close(){ closeSessions(); }
}
