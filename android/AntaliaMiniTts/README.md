# Antalia-2 Mini Android — original split ONNX graph path

This experimental Android system TTS app lives in the Sherpa-ONNX fork and does not fetch HayaiTTS. Audio inference uses ONNX Runtime Java rather than a native Sherpa C++ model handler.

## Why this revision
The original APK provided for comparison uses four ONNX graph roles:

1. text.onnx (FP32): input character IDs -> 192-dimensional features + durations.
2. cond.onnx (FP32): duration expansion indices -> conditioned/null representations.
3. flow.onnx (FP32): one CFG2 Euler step; reuse the graph 4 or 8 times.
4. vocoder.fp16.onnx (mixed precision): latent -> complex STFT re/im.
5. Java 2048-point FFT / hop 512 Hann overlap-add iSTFT -> 48 kHz samples.

The previous text_stage / sound_stage_{4,8} / decoder design was significantly slower on the user's phone. The original graph assets are pinned to a Hugging Face Space revision and validated against exact SHA-256 hashes extracted from the user-provided original 0.1.1 APK. Any mismatch fails CI.

## Controls and performance
- 4 / 8 actual Euler steps (no duplicate sound-stage graph).
- 1 / 2 / 4 CPU intra-op threads.
- Benchmark timing and per-stage text / cond / flow / vocoder / iSTFT instrumentation.
- Background preload of reusable ONNX sessions and persistent WebView normalizer.
- Turkish Android TextToSpeechService, PCM16 chunks, PARTIAL_WAKE_LOCK during synthesis.
- Single ARM64 debug APK, minification and compressed native library packaging.

## Remaining differences
The JavaScript normalize.js frontend and its four dictionary assets remain in use, rather than the original APK's AWT1 words.bin and compiled normalizer. The 47-character tokenizer is compatible, but some unusual text normalizations may differ. The current post-EQ/soft-limiter follows the web converter's output chain, not proven identical to the original APK's de-esser and full curve. Gaussian seed generation also differs from Python/Torch's exact PRNG. No Android device speedup, playback parity, or listening quality claim until a new physical phone test.

Source model: cloud0day3/antalia-mini 1.0.1 (Apache-2.0). Split graph origin: cloud0day3/antalia-mini Hugging Face Space revision 2c24b406fedada11bd731a2ec9f4535bfd354f03. JNI/runtime ONNX Runtime 1.21.0. Main branch and signing keys are unchanged.


## Screen-off and background operation (Termux-style)

The preview reader now runs inside AntaliaBackgroundService, not MainActivity.
Closing the Activity no longer stops the active AudioTrack. The service uses an
ongoing Android foreground notification with explicit Stop reading / Stop all
actions, plus PARTIAL_WAKE_LOCK while reading. Users can explicitly opt into
long-lived keep-awake mode via a switch; in that mode the CPU WakeLock remains
held even while idle, increasing battery consumption. Disabling the switch
releases it after active speech ends. Android system TextToSpeechService
continues to accept requests from other reader apps using its scoped wake lock.

For targetSdk 35, the persistent offline speech engine uses a foreground
specialUse declaration and corresponding permission, with the use case
described in the manifest. Google Play publication requires review of that
justification; no claim of store approval is made. Foreground service starting
is initiated by visible, explicit user action, not boot/autostart. Android
13+ notification permission is requested. Termux patterns were adapted,
not copied verbatim from its GPL-licensed source.

HiOS battery management: a UI button opens Android battery optimization
settings. The user must configure exemptions manually, and some Tecno devices
may have extra vendor autostart/background controls. No invisible overlay or
Android 15 background-start policy bypass is attempted: Android 15 requires
a **visible** overlay for the corresponding exemption.

Outstanding tests: Tecno Android 15, screen off/on, leaving Activity, one-hour
long reading, external TTS client, notification actions, user stop,
battery drain, OEM task-kill and notification-denied behavior.
GitHub Actions success establishes build/packaging only.
