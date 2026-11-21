package com.osman.antalia.tts;

import android.content.Context;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * Small persistent rolling diagnostic log. It deliberately records lifecycle,
 * timing, stage and outcome metadata only; utterance/book text is never stored.
 */
final class AntaliaDiagnostics {
    private static final String TAG = "AntaliaDiag";
    private static final String FILE_NAME = "antalia-diagnostics.log";
    private static final long MAX_BYTES = 128L * 1024L;
    private static final int ROTATE_KEEP_BYTES = 80 * 1024;
    private static final int SHARE_BYTES = 96 * 1024;
    private static final Object LOCK = new Object();

    private AntaliaDiagnostics() {}

    static void event(Context context, String area, String detail) {
        if (context == null) return;
        String safeArea = sanitize(area);
        String safeDetail = sanitize(detail);
        String line = Instant.now().toString()
            + " upMs=" + SystemClock.elapsedRealtime()
            + " pid=" + Process.myPid()
            + " tid=" + Thread.currentThread().getId()
            + " " + safeArea + " " + safeDetail + "\n";
        Log.i(TAG, safeArea + " " + safeDetail);
        byte[] encoded = line.getBytes(StandardCharsets.UTF_8);
        synchronized (LOCK) {
            try {
                File file = logFile(context);
                rotateIfNeeded(file, encoded.length);
                try (FileOutputStream out = new FileOutputStream(file, true)) {
                    out.write(encoded);
                }
            } catch (Exception e) {
                Log.w(TAG, "Cannot persist diagnostics", e);
            }
        }
    }

    static void exception(Context context, String area, String detail, Throwable error) {
        String type = error == null ? "null" : error.getClass().getSimpleName();
        String message = error == null ? "" : error.getMessage();
        event(context, area, detail + " error=" + type + " message=" + sanitize(message));
    }

    static String snapshot(Context context) {
        synchronized (LOCK) {
            try {
                File file = logFile(context);
                if (!file.isFile()) return "Antalia tanılama kaydı henüz oluşmadı.";
                byte[] all = readAll(file);
                byte[] tail = tailAligned(all, SHARE_BYTES);
                return "Antalia Mini tanılama · son " + tail.length + " bayt / toplam "
                    + all.length + " bayt\n"
                    + "Metin içeriği kaydedilmez; yalnız yaşam döngüsü, süre, aşama ve sonuç bilgileri vardır.\n\n"
                    + new String(tail, StandardCharsets.UTF_8);
            } catch (Exception e) {
                return "Tanılama kaydı okunamadı: " + e.getClass().getSimpleName()
                    + ": " + sanitize(e.getMessage());
            }
        }
    }

    static String sanitize(String value) {
        if (value == null) return "";
        String safe = value.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').trim();
        if (safe.length() > 700) safe = safe.substring(0, 700) + "…";
        return safe;
    }

    static byte[] tailAligned(byte[] data, int maxBytes) {
        if (data == null || data.length == 0) return new byte[0];
        if (maxBytes <= 0 || data.length <= maxBytes) return data.clone();
        int start = data.length - maxBytes;
        // Keep an exact line boundary. Only discard bytes when the requested
        // window starts in the middle of a line.
        if (start > 0 && data[start - 1] != (byte)'\n') {
            while (start < data.length && data[start] != (byte)'\n') start++;
            if (start < data.length) start++;
        }
        int length = Math.max(0, data.length - start);
        byte[] tail = new byte[length];
        System.arraycopy(data, start, tail, 0, length);
        return tail;
    }

    private static File logFile(Context context) {
        return new File(context.getApplicationContext().getNoBackupFilesDir(), FILE_NAME);
    }

    private static byte[] readAll(File file) throws Exception {
        try (FileInputStream in = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream((int)Math.min(file.length(), MAX_BYTES))) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }

    private static void rotateIfNeeded(File file, int incomingBytes) throws Exception {
        if (!file.isFile() || file.length() + incomingBytes <= MAX_BYTES) return;
        byte[] tail = tailAligned(readAll(file), ROTATE_KEEP_BYTES);
        File temp = new File(file.getParentFile(), FILE_NAME + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp, false)) {
            out.write(tail);
            out.getFD().sync();
        }
        if (file.exists() && !file.delete())
            throw new IllegalStateException("Cannot rotate old diagnostics");
        if (!temp.renameTo(file))
            throw new IllegalStateException("Cannot finalize diagnostics rotation");
    }
}
