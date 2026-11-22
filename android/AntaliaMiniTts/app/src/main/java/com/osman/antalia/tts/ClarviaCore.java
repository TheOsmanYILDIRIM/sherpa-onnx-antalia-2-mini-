package com.osman.antalia.tts;

import android.content.Context;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
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
import java.util.concurrent.atomic.AtomicBoolean;

final class ClarviaCore implements AutoCloseable {
    static final int RATE = 24000;
    private static final float PEAK = 0.99f;
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

    synchronized void speak(String text, String androidVoiceName, float speed, float pitchShift,
                            float pitchRange, int threads, AtomicBoolean stop, AudioSink sink) throws Exception {
        prepare(threads);
        int voiceIndex = TtsVoiceRegistry.clarviaIndex(androidVoiceName);
        speed = Math.max(0.35f, Math.min(3.0f, speed));
        long started = android.os.SystemClock.elapsedRealtimeNanos();
        String payload = bridge.callAttr("phonemize_chunks_json", text).toString();
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
                try (OrtSession.Result result = session.run(input)) {
                    float[] audio = outputAudio(result.get(0).getValue());
                    if (audio.length == 0) throw new IllegalStateException("Clarvia returned empty audio");
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
