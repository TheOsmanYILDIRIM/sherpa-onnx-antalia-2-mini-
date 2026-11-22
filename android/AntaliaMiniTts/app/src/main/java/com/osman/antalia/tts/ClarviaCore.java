package com.osman.antalia.tts;

import android.content.Context;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

final class ClarviaCore implements AutoCloseable {
    static final int RATE = 24000;
    private static final float PEAK = 0.99f;
    private static final long PHONEMIZE_TIMEOUT_MS = 2000L;
    private static final long ONNX_FIRST_ATTEMPT_TIMEOUT_MS = 4000L;
    private static final long ONNX_RETRY_TIMEOUT_MS = 5000L;
    private static ClarviaCore singleton;

    interface AudioSink { boolean deliver(float[] audio); }

    static synchronized ClarviaCore shared(Context context) {
        if (singleton == null) singleton = new ClarviaCore(context.getApplicationContext());
        return singleton;
    }

    private final Context context;
    private final OrtEnvironment env = OrtEnvironment.getEnvironment();
    private OrtSession session;
    private OrtSession.SessionOptions options;
    private File modelFile;
    private PyObject bridge;
    private int currentThreads = -1;
    private volatile String lastProfile = "";
    private final AtomicLong runSequence = new AtomicLong();

    private ClarviaCore(Context context) {
        this.context = context;
    }

    String lastProfile() { return lastProfile; }

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

    private void installModel() throws Exception {
        if (modelFile != null) return;
        String manifestText;
        try (InputStream in = context.getAssets().open("clarvia/manifest.json")) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            manifestText = out.toString("UTF-8");
        }
        JSONObject manifest = new JSONObject(manifestText);
        String expected = manifest.getString("model_sha256");
        File dir = new File(context.getFilesDir(), "clarvia_tts_tiny");
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Cannot create Clarvia model directory");
        File dest = new File(dir, "model.onnx");
        if (!dest.isFile() || !expected.equals(sha256(dest))) {
            File temp = new File(dir, "model.onnx.part");
            try (InputStream input = context.getAssets().open("clarvia/model.onnx");
                 FileOutputStream output = new FileOutputStream(temp)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = input.read(buf)) != -1) output.write(buf, 0, n);
                output.getFD().sync();
            }
            if (!expected.equals(sha256(temp))) throw new IllegalStateException("Clarvia model SHA-256 mismatch");
            if (dest.exists() && !dest.delete()) throw new IllegalStateException("Cannot replace Clarvia model");
            if (!temp.renameTo(dest)) throw new IllegalStateException("Cannot finalize Clarvia model");
        }
        modelFile = dest;
    }

    private void preparePython() {
        if (bridge != null) return;
        if (!Python.isStarted()) Python.start(new AndroidPlatform(context));
        bridge = Python.getInstance().getModule("clarvia_bridge");
    }

    private void closeSession() {
        if (session != null) try { session.close(); } catch (Exception ignored) {}
        session = null;
        if (options != null) try { options.close(); } catch (Exception ignored) {}
        options = null;
        currentThreads = -1;
    }

    synchronized void prepare(int threads) throws Exception {
        if (threads != 1 && threads != 2 && threads != 4) threads = 2;
        installModel();
        preparePython();
        if (session != null && currentThreads == threads) return;
        closeSession();
        options = new OrtSession.SessionOptions();
        options.setIntraOpNumThreads(threads);
        options.setInterOpNumThreads(1);
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        session = env.createSession(modelFile.getAbsolutePath(), options);
        currentThreads = threads;
    }

    private static long[] jsonLongs(JSONArray array) {
        long[] values = new long[array.length()];
        for (int i = 0; i < values.length; i++) values[i] = array.optLong(i);
        return values;
    }

    private static float[] outputAudio(Object value) {
        if (value instanceof float[]) return (float[]) value;
        if (value instanceof float[][]) {
            float[][] rows = (float[][]) value;
            return rows.length == 0 ? new float[0] : rows[0];
        }
        if (value instanceof float[][][]) {
            float[][][] planes = (float[][][]) value;
            return (planes.length == 0 || planes[0].length == 0) ? new float[0] : planes[0][0];
        }
        throw new IllegalStateException("Unexpected Clarvia audio tensor type: " + value.getClass());
    }

    static long onnxBudgetMs(int attempt) {
        return attempt <= 0 ? ONNX_FIRST_ATTEMPT_TIMEOUT_MS : ONNX_RETRY_TIMEOUT_MS;
    }

    static boolean shouldRetryInference(boolean stopped, int attempt) {
        return !stopped && attempt == 0;
    }

    private String phonemizeWatched(String text, AtomicBoolean stop) throws Exception {
        if (stop.get()) throw new CancellationException("TTS stopped");
        final long started = android.os.SystemClock.elapsedRealtime();
        AntaliaDiagnostics.event(context, "clarvia_phonemize_begin",
            "chars=" + text.length() + " budgetMs=" + PHONEMIZE_TIMEOUT_MS);
        FutureTask<String> task = new FutureTask<>(
            () -> bridge.callAttr("phonemize_chunks_json", text).toString());
        Thread worker = new Thread(task, "clarvia-phonemize-" + runSequence.incrementAndGet());
        worker.setDaemon(true);
        worker.start();
        try {
            String payload = task.get(PHONEMIZE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            AntaliaDiagnostics.event(context, "clarvia_phonemize_end",
                "elapsedMs=" + Math.max(0L, android.os.SystemClock.elapsedRealtime() - started));
            return payload;
        } catch (TimeoutException timeout) {
            task.cancel(true);
            AntaliaDiagnostics.event(context, "clarvia_phonemize_timeout",
                "elapsedMs=" + Math.max(0L, android.os.SystemClock.elapsedRealtime() - started)
                    + " budgetMs=" + PHONEMIZE_TIMEOUT_MS);
            throw timeout;
        }
    }

    private OrtSession.Result runWatched(Map<String, OnnxTensor> input, AtomicBoolean stop,
                                         int chunkIndex, int attempt, int complexity) throws Exception {
        if (stop.get()) throw new CancellationException("TTS stopped");
        final long budgetMs = onnxBudgetMs(attempt);
        final long started = android.os.SystemClock.elapsedRealtime();
        final AtomicBoolean active = new AtomicBoolean(true);
        final AtomicBoolean timedOut = new AtomicBoolean(false);
        final AtomicBoolean terminateRequested = new AtomicBoolean(false);
        final long runId = runSequence.incrementAndGet();
        AntaliaDiagnostics.event(context, "clarvia_onnx_begin",
            "run=" + runId + " chunk=" + chunkIndex + " attempt=" + (attempt + 1)
                + " phonemes=" + complexity + " budgetMs=" + budgetMs);
        try (OrtSession.RunOptions runOptions = new OrtSession.RunOptions()) {
            Thread watcher = new Thread(() -> {
                while (active.get()) {
                    try {
                        Thread.sleep(50L);
                    } catch (InterruptedException interrupted) {
                        if (!active.get()) return;
                    }
                    if (!active.get()) return;
                    long elapsed = Math.max(0L, android.os.SystemClock.elapsedRealtime() - started);
                    boolean timeout = elapsed > budgetMs;
                    if (timeout && timedOut.compareAndSet(false, true)) {
                        AntaliaDiagnostics.event(context, "clarvia_onnx_timeout",
                            "run=" + runId + " chunk=" + chunkIndex
                                + " attempt=" + (attempt + 1)
                                + " elapsedMs=" + elapsed + " budgetMs=" + budgetMs);
                    }
                    if ((timeout || stop.get())
                            && terminateRequested.compareAndSet(false, true)) {
                        String reason = timeout ? "timeout" : "stop";
                        AntaliaDiagnostics.event(context, "clarvia_terminate_begin",
                            "run=" + runId + " chunk=" + chunkIndex
                                + " attempt=" + (attempt + 1) + " reason=" + reason);
                        try {
                            runOptions.setTerminate(true);
                            AntaliaDiagnostics.event(context, "clarvia_terminate_end",
                                "run=" + runId + " chunk=" + chunkIndex
                                    + " attempt=" + (attempt + 1) + " reason=" + reason);
                        } catch (Exception error) {
                            AntaliaDiagnostics.exception(context, "clarvia_terminate_error",
                                "run=" + runId + " chunk=" + chunkIndex
                                    + " attempt=" + (attempt + 1) + " reason=" + reason, error);
                        }
                        return;
                    }
                }
            }, "clarvia-watchdog-" + runId);
            watcher.setDaemon(true);
            watcher.setPriority(Thread.MAX_PRIORITY);
            watcher.start();
            try {
                OrtSession.Result result = session.run(input, runOptions);
                if (stop.get() || timedOut.get()) {
                    result.close();
                    if (timedOut.get()) {
                        throw new TimeoutException("Clarvia ONNX inference timed out");
                    }
                    throw new CancellationException("TTS stopped");
                }
                AntaliaDiagnostics.event(context, "clarvia_onnx_end",
                    "run=" + runId + " chunk=" + chunkIndex
                        + " attempt=" + (attempt + 1)
                        + " elapsedMs="
                        + Math.max(1L, android.os.SystemClock.elapsedRealtime() - started));
                return result;
            } catch (OrtException error) {
                AntaliaDiagnostics.exception(context, "clarvia_onnx_error",
                    "run=" + runId + " chunk=" + chunkIndex
                        + " attempt=" + (attempt + 1)
                        + " timedOut=" + timedOut.get() + " stopped=" + stop.get(), error);
                if (timedOut.get()) {
                    TimeoutException timeout = new TimeoutException("Clarvia ONNX inference timed out");
                    timeout.initCause(error);
                    throw timeout;
                }
                throw error;
            } finally {
                active.set(false);
                watcher.interrupt();
            }
        }
    }

    synchronized void speak(String text, String androidVoiceName, float speed, float pitchShift,
                            float pitchRange, int threads, AtomicBoolean stop, AudioSink sink) throws Exception {
        prepare(threads);
        int voiceIndex = TtsVoiceRegistry.clarviaIndex(androidVoiceName);
        speed = Math.max(0.35f, Math.min(3.0f, speed));
        long started = android.os.SystemClock.elapsedRealtimeNanos();
        String payload = phonemizeWatched(text, stop);
        JSONArray chunks = new JSONArray(payload);
        int samples = 0;
        for (int ci = 0; ci < chunks.length() && !stop.get(); ci++) {
            JSONObject chunk = chunks.getJSONObject(ci);
            long[] ids = jsonLongs(chunk.getJSONArray("ids"));
            long[] stress = jsonLongs(chunk.getJSONArray("stress"));
            if (ids.length != stress.length || ids.length < 3 || ids.length > 512)
                throw new IllegalStateException("Invalid Clarvia phoneme payload length: " + ids.length);

            try (OnnxTensor tokens = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), new long[]{1, ids.length});
                 OnnxTensor stressTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(stress), new long[]{1, stress.length});
                 OnnxTensor voice = OnnxTensor.createTensor(env, LongBuffer.wrap(new long[]{voiceIndex}), new long[]{1});
                 OnnxTensor speedTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(new float[]{speed}), new long[]{1});
                 OnnxTensor pitchShiftTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(new float[]{pitchShift}), new long[]{1});
                 OnnxTensor pitchRangeTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(new float[]{pitchRange}), new long[]{1})) {
                Map<String, OnnxTensor> input = new HashMap<>();
                input.put("tokens", tokens);
                input.put("stress", stressTensor);
                input.put("voice", voice);
                input.put("speed", speedTensor);
                input.put("pitch_shift", pitchShiftTensor);
                input.put("pitch_range", pitchRangeTensor);
                float[] audio = null;
                for (int attempt = 0; attempt < 2 && !stop.get(); attempt++) {
                    try (OrtSession.Result result = runWatched(
                            input, stop, ci, attempt, ids.length)) {
                        audio = outputAudio(result.get(0).getValue());
                        if (audio.length == 0) {
                            throw new IllegalStateException("Clarvia returned empty audio");
                        }
                        break;
                    } catch (TimeoutException | OrtException failure) {
                        AntaliaDiagnostics.exception(context, "clarvia_attempt_error",
                            "chunk=" + ci + " attempt=" + (attempt + 1)
                                + " stopped=" + stop.get(), failure);
                        if (!shouldRetryInference(stop.get(), attempt)) throw failure;
                        AntaliaDiagnostics.event(context, "clarvia_retry",
                            "chunk=" + ci + " nextAttempt=" + (attempt + 2)
                                + " reason=" + failure.getClass().getSimpleName());
                        closeSession();
                        prepare(threads);
                    }
                }
                if (stop.get()) break;
                if (audio == null) throw new IllegalStateException("No Clarvia PCM after retry");
                float loudest = 0f;
                for (float sample : audio) if (Float.isFinite(sample)) loudest = Math.max(loudest, Math.abs(sample));
                if (loudest > PEAK) {
                    float scale = PEAK / loudest;
                    for (int i = 0; i < audio.length; i++) audio[i] = Float.isFinite(audio[i]) ? audio[i] * scale : 0f;
                } else {
                    for (int i = 0; i < audio.length; i++) if (!Float.isFinite(audio[i])) audio[i] = 0f;
                }
                samples += audio.length;
                if (!sink.deliver(audio)) break;
            }
        }
        double elapsed = (android.os.SystemClock.elapsedRealtimeNanos() - started) / 1e9;
        lastProfile = String.format(java.util.Locale.ROOT,
            "clarvia voice=%s chunks=%d audio=%.2fs total=%.2fs rtf=%.3f",
            TtsVoiceRegistry.clarviaId(androidVoiceName), chunks.length(),
            samples / (double) RATE, elapsed,
            samples > 0 ? elapsed / (samples / (double) RATE) : Double.NaN);
    }

    @Override public synchronized void close() {
        closeSession();
    }
}
