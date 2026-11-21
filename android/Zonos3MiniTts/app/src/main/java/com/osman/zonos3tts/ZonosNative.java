package com.osman.zonos3tts;

final class ZonosNative {
    static { System.loadLibrary("zonos3_jni"); }
    private ZonosNative() {}
    static native long create(String backbone, String mmproj, int threads);
    static native byte[] synthesize(long handle, String text);
    static native int sampleRate(long handle);
    static native void cancel(long handle);
    static native void destroy(long handle);
}
