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
    private OrtSession sound;
    private OrtSession decoder;
    private OrtSession.SessionOptions options;
    private int currentThreads = -1;
    private int currentSteps = -1;
    private static AntaliaCore singleton;

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
        File dir = new File(context.getFilesDir(), "antalia_101_int8");
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Cannot create private model directory");
        String[] models = {"text_stage", "sound_stage", "sound_stage_4", "decoder"};
        String manifest;
        try (InputStream in = context.getAssets().open("antalia/quantization.json")) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096]; int n;
            while ((n = in.read(buf)) != -1) bytes.write(buf, 0, n);
            manifest = bytes.toString("UTF-8");
        }
        JSONObject stages = new JSONObject(manifest).getJSONObject("stage_tests");
        for (String name : models) {
            String filename = name + ".onnx";
            String expected = stages.getJSONObject(name).getString("sha256");
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
        closeSession(sound); closeSession(decoder); closeSession(text);
        sound = decoder = text = null;
        if (options != null) try { options.close(); } catch (Exception ignored) {}
        options = null;
        currentThreads = -1;
        currentSteps = -1;
    }

    private void open(int steps, int threads) throws Exception {
        if (steps != 4 && steps != 8) throw new IllegalArgumentException("Steps must be 4 or 8");
        if (threads != 1 && threads != 2 && threads != 4) throw new IllegalArgumentException("Threads must be 1, 2 or 4");
        install();
        // Critical cold-start fix: do not initialize both flow graphs.
        // Reuse common text/vocoder sessions when switching 4/8 steps.
        if (currentThreads != threads || text == null || decoder == null) {
            closeSessions();
            options = new OrtSession.SessionOptions();
            options.setIntraOpNumThreads(threads);
            options.setInterOpNumThreads(1);
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            try {
                text = env.createSession(new File(modelDir, "text_stage.onnx").getAbsolutePath(), options);
                decoder = env.createSession(new File(modelDir, "decoder.onnx").getAbsolutePath(), options);
                currentThreads = threads;
            } catch (Exception e) {
                closeSessions();
                throw e;
            }
        }
        if (sound == null || currentSteps != steps) {
            closeSession(sound);
            sound = null;
            currentSteps = -1;
            try {
                String name = steps == 4 ? "sound_stage_4.onnx" : "sound_stage.onnx";
                sound = env.createSession(new File(modelDir, name).getAbsolutePath(), options);
                currentSteps = steps;
            } catch (Exception e) {
                closeSessions();
                throw e;
            }
        }
    }

    /** Called on the background executor when app/service starts; no playback. */
    synchronized void prepare(int steps, int threads) throws Exception {
        open(steps, threads);
        normalizer.prepare();
    }

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

    private float[] infer(String part, int steps, float speed, int chunkIndex) throws Exception {
        String prefix = (part.startsWith("ö") || part.startsWith("ı")) ? "ee, " : "";
        String suffix = " ee.";
        long[] ids = tokens(prefix + part + suffix);
        float[][] hidden;
        float[] logd;
        byte[] mask = new byte[ids.length];
        Arrays.fill(mask, (byte)1);
        try (OnnxTensor idsTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), new long[]{1,ids.length});
             OnnxTensor maskTensor = OnnxTensor.createTensor(env, ByteBuffer.wrap(mask), new long[]{1,ids.length}, OnnxJavaType.BOOL);
             OrtSession.Result result = text.run(feed("ids",idsTensor,"mask",maskTensor))) {
            hidden = ((float[][][])result.get("h").orElseThrow().getValue())[0];
            logd = ((float[][])result.get("logd").orElseThrow().getValue())[0];
        }
        if (hidden.length != ids.length || logd.length != ids.length) throw new IllegalStateException("Text stage tensor shape mismatch");
        for (int i=2;i<ids.length-1;i++) {
            if (ids[i]==ids[i-1] && ids[i]!=3L) logd[i] += (float)Math.log(1.3);
            else if (ids[i-1]==3L && ids[i]==ids[i-2]) logd[i-1] += (float)Math.log(1.3);
        }
        int[] duration = new int[ids.length]; int frames=0;
        for (int i=0;i<duration.length;i++) {
            if (!Float.isFinite(logd[i])) throw new IllegalStateException("Non-finite duration");
            int n=(int)Math.round(Math.exp(logd[i])/speed);
            duration[i]=Math.max(1,Math.min(60,n));
            frames+=duration[i];
        }
        frames = ((frames+STACK-1)/STACK)*STACK;
        if (frames < 2 || frames > 16000) throw new IllegalStateException("Too many predicted audio frames: "+frames);
        float[] cond = new float[frames*HIDDEN];int frame=0;
        for(int i=0;i<duration.length;i++) {
            if(hidden[i].length != HIDDEN) throw new IllegalStateException("Hidden dimension mismatch");
            for(int j=0;j<duration[i];j++) {
                System.arraycopy(hidden[i],0,cond,frame*HIDDEN,HIDDEN);frame++;
            }
        }
        while(frame<frames) {
            System.arraycopy(hidden[ids.length-1],0,cond,frame*HIDDEN,HIDDEN);frame++;
        }
        byte[] fmask=new byte[frames];Arrays.fill(fmask,(byte)1);
        float[] noise=new float[(frames/STACK)*BINS*STACK];
        Random random=new Random(0xA77A1A+chunkIndex);
        for(int i=0;i<noise.length;i++)noise[i]=(float)(random.nextGaussian()*0.6);
        float[][] mel;
        try (OnnxTensor c = OnnxTensor.createTensor(env,FloatBuffer.wrap(cond),new long[]{1,frames,HIDDEN});
             OnnxTensor m = OnnxTensor.createTensor(env,ByteBuffer.wrap(fmask),new long[]{1,frames},OnnxJavaType.BOOL);
             OnnxTensor z = OnnxTensor.createTensor(env,FloatBuffer.wrap(noise),new long[]{1,frames/STACK,BINS*STACK});
             OrtSession.Result out=sound.run(feed("cond",c,"fmask",m,"noise",z))) {
            mel=((float[][][])out.get("mel").orElseThrow().getValue())[0];
        }
        if(mel.length!=BINS)throw new IllegalStateException("Mel dimension mismatch");
        float[] flat=new float[BINS*frames];
        for(int i=0;i<BINS;i++) {
            if(mel[i].length!=frames)throw new IllegalStateException("Mel frame mismatch");
            System.arraycopy(mel[i],0,flat,i*frames,frames);
        }
        float[] audio;
        try(OnnxTensor mt=OnnxTensor.createTensor(env,FloatBuffer.wrap(flat),new long[]{1,BINS,frames});
            OrtSession.Result out=decoder.run(Collections.singletonMap("mel",mt))) {
            audio=((float[][])out.get("audio").orElseThrow().getValue())[0];
        }
        int prefixCount=prefix.length();
        int suffixCount=suffix.length();
        int first=0,last=0;
        for(int i=0;i<duration.length;i++) {
            if (i<=prefixCount) first+=duration[i];
            if (i<=ids.length-2-suffixCount) last+=duration[i];
        }
        int from=prefixCount>0 ? Math.max(0,first*HOP):0;
        int to=Math.min(audio.length,last*HOP);
        if(to<=from)throw new IllegalStateException("Invalid PCM crop");
        float[] clipped=Arrays.copyOfRange(audio,from,to);
        int fade=Math.min(240,clipped.length/2);
        if(prefixCount>0)for(int i=0;i<fade;i++)clipped[i]*=i/(float)fade;
        for(int i=0;i<fade;i++)clipped[clipped.length-1-i]*=i/(float)fade;
        return clipped;
    }

    synchronized void speak(String raw, int steps, int threads, float speed,
                            AtomicBoolean stop, AudioSink sink) throws Exception {
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
            float[] audio=tone.process(infer(cleaned,steps,speed,i));
            if(!sink.deliver(audio))break;
            if(i!=parts.size()-1 && !stop.get()) {
                char tail=parts.get(i).charAt(parts.get(i).length()-1);
                int pause=(tail=='.'||tail=='!'||tail=='?')?5760:2880;
                if(!sink.deliver(new float[pause]))break;
            }
        }
    }

    @Override public synchronized void close(){ closeSessions(); }
}
