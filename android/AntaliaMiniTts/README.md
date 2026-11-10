# Antalia-2 Mini Android (Sherpa-ONNX fork)

Experimental compact Android TTS app directly in the Sherpa-ONNX fork, no HayaiTTS checkout. Inference is performed through the ONNX Runtime Java SDK; native Sherpa-ONNX C++ model integration is not claimed.

Controls: 4/8 genuine flow steps, 1/2/4 intra-op threads, four-configuration latency benchmark with time-to-first PCM, Android Turkish TTS service and limited wake-lock usage.

Weights are per-channel INT8 QDQ with sensitive vocoder blocks in FP32. Official 1.0.1 model revision and text normalization sources are pinned. Numeric parity testing and CI do not constitute Android device or listening tests. Screen-off behavior and official de-esser parity remain unverified.

Uses only Android arm64-v8a ONNX Runtime, Java platform UI without Compose, R8 shrinking, compressed model assets (expanded into app-private files at first run). The goal is to minimize APK size; 35 MB is not guaranteed. Experimental APKs use debug signing only.
