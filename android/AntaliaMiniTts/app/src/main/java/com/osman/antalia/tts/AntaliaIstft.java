package com.osman.antalia.tts;

/**
 * Inverse of Antalia Mini's centered 2048-point Hann-window STFT (hop 512).
 * Input is [1025][T] frequency-major real/imaginary spectrograms.
 * Implements the real 2048-point IFFT as one 1024-point complex IFFT,
 * overlap/add with squared-window normalization and centered trimming.
 * Based on the public Antalia Mini inference algorithm (Apache-2.0).
 */
final class AntaliaIstft {
    static final int FFT = 2048;
    static final int HOP = 512;
    static final int M = FFT / 2;
    static final int BINS = M + 1;
    private static final double[] WINDOW = new double[FFT];
    private static final int[] BIT_REVERSE = new int[M];
    private static final double[] COS_M = new double[M / 2];
    private static final double[] SIN_M = new double[M / 2];
    private static final double[] COS_N = new double[M];
    private static final double[] SIN_N = new double[M];

    static {
        for (int i = 0; i < FFT; i++) WINDOW[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / FFT);
        for (int i = 0; i < M; i++) {
            int reversed = Integer.reverse(i) >>> (32 - 10);
            BIT_REVERSE[i] = reversed;
            COS_N[i] = Math.cos(2 * Math.PI * i / FFT);
            SIN_N[i] = Math.sin(2 * Math.PI * i / FFT);
        }
        for (int i = 0; i < M / 2; i++) {
            COS_M[i] = Math.cos(2 * Math.PI * i / M);
            SIN_M[i] = Math.sin(2 * Math.PI * i / M);
        }
    }

    private AntaliaIstft() {}

    private static void inverseFft1024(double[] real, double[] imag) {
        for (int i = 0; i < M; i++) {
            int j = BIT_REVERSE[i];
            if (j > i) {
                double swap = real[i]; real[i] = real[j]; real[j] = swap;
                swap = imag[i]; imag[i] = imag[j]; imag[j] = swap;
            }
        }
        for (int size = 2; size <= M; size <<= 1) {
            int half = size >>> 1;
            int step = M / size;
            for (int start = 0; start < M; start += size) {
                for (int k = 0; k < half; k++) {
                    double wr = COS_M[k * step], wi = SIN_M[k * step];
                    int a = start + k, b = a + half;
                    double tr = real[b] * wr - imag[b] * wi;
                    double ti = real[b] * wi + imag[b] * wr;
                    real[b] = real[a] - tr;
                    imag[b] = imag[a] - ti;
                    real[a] += tr;
                    imag[a] += ti;
                }
            }
        }
    }

    static float[] invert(float[][] re, float[][] im) {
        if (re.length != BINS || im.length != BINS) throw new IllegalArgumentException("Expected 1025 STFT bins");
        int frames = re[0].length;
        if (frames < 2 || frames > 16000) throw new IllegalArgumentException("Invalid STFT frame count");
        for (int k = 0; k < BINS; k++) {
            if (re[k].length != frames || im[k].length != frames) throw new IllegalArgumentException("Invalid STFT shape");
        }
        int size = FFT + HOP * (frames - 1);
        double[] out = new double[size];
        double[] windowSum = new double[size];
        double[] real = new double[M], imag = new double[M];
        // Pack frequency-major OrtSession outputs into frame-major arrays for contiguous memory access.
        float[] fr = new float[frames * BINS], fi = new float[frames * BINS];
        for (int k = 0; k < BINS; k++) {
            for (int t = 0; t < frames; t++) {
                fr[t * BINS + k] = re[k][t];
                fi[t * BINS + k] = im[k][t];
            }
        }
        for (int t = 0; t < frames; t++) {
            int base = t * BINS;
            for (int k = 0; k < M; k++) {
                double ar = fr[base + k], ai = k == 0 ? 0 : fi[base + k];
                double br = fr[base + M - k], bi = k == 0 ? 0 : -fi[base + M - k];
                double er = ar + br, ei = ai + bi;
                double dr = ar - br, di = ai - bi;
                double or = dr * COS_N[k] - di * SIN_N[k];
                double oi = dr * SIN_N[k] + di * COS_N[k];
                real[k] = er - oi;
                imag[k] = ei + or;
            }
            inverseFft1024(real, imag);
            int off = t * HOP;
            for (int m = 0; m < M; m++) {
                int i = m * 2;
                double w0 = WINDOW[i], w1 = WINDOW[i + 1];
                out[off + i] += real[m] / FFT * w0;
                out[off + i + 1] += imag[m] / FFT * w1;
                windowSum[off + i] += w0 * w0;
                windowSum[off + i + 1] += w1 * w1;
            }
        }
        float[] audio = new float[frames * HOP];
        int trim = FFT / 2;
        // Last hop is zero padding, matching Antalia Mini's streaming interface.
        for (int i = 0; i < size - FFT; i++) {
            double denom = windowSum[trim + i];
            audio[i] = (float)(out[trim + i] / (denom > 1e-11 ? denom : 1.0));
        }
        return audio;
    }
}
