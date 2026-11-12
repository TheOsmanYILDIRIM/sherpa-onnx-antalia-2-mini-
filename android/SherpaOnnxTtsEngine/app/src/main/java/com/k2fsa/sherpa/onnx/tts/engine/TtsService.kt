package com.k2fsa.sherpa.onnx.tts.engine

import android.media.AudioFormat
import com.k2fsa.sherpa.onnx.GenerationConfig
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/*
https://developer.android.com/reference/java/util/Locale#getISO3Language()
https://developer.android.com/reference/java/util/Locale#getISO3Country()

eng, USA,
eng, USA, POSIX
eng,
eng, GBR
afr,
afr, NAM
afr, ZAF
agq
agq, CMR
aka,
aka, GHA
amh,
amh, ETH
ara,
ara, 001
ara, ARE
ara, BHR,
deu
deu, AUT
deu, BEL
deu, CHE
deu, ITA
deu, ITA
deu, LIE
deu, LUX
spa,
spa, 419
spa, ARG,
spa, BRA
fra,
fra, BEL,
fra, FRA,

E  Failed to check TTS data, no activity found for Intent
{ act=android.speech.tts.engine.CHECK_TTS_DATA pkg=com.k2fsa.sherpa.chapter5 })

E Failed to get default language from engine com.k2fsa.sherpa.chapter5
Engine failed voice data integrity check (null return)com.k2fsa.sherpa.chapter5
Failed to get default language from engine com.k2fsa.sherpa.chapter5

*/

class TtsService : TextToSpeechService() {
    private val stopRequested = AtomicBoolean(false)

    override fun onCreate() {
        Log.i(TAG, "onCreate tts service")
        super.onCreate()

        // see https://github.com/Miserlou/Android-SDK-Samples/blob/master/TtsEngine/src/com/example/android/ttsengine/RobotSpeakTtsService.java#L68
        onLoadLanguage(TtsEngine.lang, "", "")
        if (TtsEngine.lang2 != null) {
            onLoadLanguage(TtsEngine.lang2, "", "")
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy tts service")
        super.onDestroy()
    }

    // https://developer.android.com/reference/kotlin/android/speech/tts/TextToSpeechService#onislanguageavailable
    override fun onIsLanguageAvailable(_lang: String?, _country: String?, _variant: String?): Int {
        val lang = _lang ?: ""

        if (lang == TtsEngine.lang || lang == TtsEngine.lang2) {
            return TextToSpeech.LANG_AVAILABLE
        }

        return TextToSpeech.LANG_NOT_SUPPORTED
    }

    override fun onGetLanguage(): Array<String> {
        return arrayOf(TtsEngine.lang!!, "", "")
    }

    // https://developer.android.com/reference/kotlin/android/speech/tts/TextToSpeechService#onLoadLanguage(kotlin.String,%20kotlin.String,%20kotlin.String)
    override fun onLoadLanguage(_lang: String?, _country: String?, _variant: String?): Int {
        Log.i(TAG, "onLoadLanguage: $_lang, $_country")
        val lang = _lang ?: ""

        return if (lang == TtsEngine.lang || lang == TtsEngine.lang2) {
            Log.i(TAG, "creating tts, lang :$lang")
            TtsEngine.createTts(application)
            TextToSpeech.LANG_AVAILABLE
        } else {
            Log.i(TAG, "lang $lang not supported, tts engine lang: ${TtsEngine.lang}, ${TtsEngine.lang2}")
            TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    override fun onStop() {
        // Android may invoke this on another thread while synthesis is active.
        stopRequested.set(true)
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) {
            return
        }
        val language = request.language
        val country = request.country
        val variant = request.variant
        val text = request.charSequenceText.toString()
        // Map Android TTS speech rate (where 100 == normal) to engine speed (1.0 == normal)
        // Allow per-request override from external apps; fallback to engine default if absent.
        val rate = runCatching { request.speechRate }.getOrDefault(-1)
        val engineSpeed = if (rate > 0) {
            // Map 100 -> 1.0f
            val mapped = rate / 100.0f
            mapped.coerceIn(MIN_TTS_SPEED, MAX_TTS_SPEED)
        } else {
            // Fallback to current engine/global setting
            TtsEngine.speed
        }

        val ret = onIsLanguageAvailable(language, country, variant)
        if (ret == TextToSpeech.LANG_NOT_SUPPORTED) {
            callback.error()
            return
        }
        Log.i(TAG, "text: $text, engineSpeed: $engineSpeed")
        val tts = TtsEngine.tts ?: run {
            Log.e(TAG, "TTS engine is not initialized")
            callback.error()
            return
        }
        stopRequested.set(false)

        // Note that AudioFormat.ENCODING_PCM_FLOAT requires API level >= 24
        // callback.start(tts.sampleRate(), AudioFormat.ENCODING_PCM_FLOAT, 1)

        if (callback.start(tts.sampleRate(), AudioFormat.ENCODING_PCM_16BIT, 1) != TextToSpeech.SUCCESS) {
            Log.e(TAG, "Synthesis callback refused to start")
            return
        }

        if (text.isBlank() || text.isEmpty()) {
            callback.done()
            return
        }

        // Retry only when the native generator has returned without delivering audio.
        // A running JNI call cannot be safely interrupted/restarted on the same model.
        val deliveredBytes = AtomicInteger(0)
        val ttsCallback: (FloatArray) -> Int = fun(floatSamples): Int {
            if (stopRequested.get()) return 0
            if (floatSamples.isEmpty()) return 1

            val samples = floatArrayToByteArray(floatSamples)
            val maxBufferSize = callback.maxBufferSize
            if (maxBufferSize <= 0) return 0
            var offset = 0
            while (offset < samples.size) {
                if (stopRequested.get()) return 0
                val bytesToWrite = minOf(maxBufferSize, samples.size - offset)
                if (callback.audioAvailable(samples, offset, bytesToWrite) != TextToSpeech.SUCCESS) {
                    Log.w(TAG, "Audio consumer rejected data")
                    stopRequested.set(true)
                    return 0
                }
                deliveredBytes.addAndGet(bytesToWrite)
                offset += bytesToWrite
            }
            return 1
        }

        val genConfig = GenerationConfig(sid = TtsEngine.speakerId, speed = engineSpeed)
        var genText = text
        try {
            if (TtsEngine.usePhonemize) {
                genConfig.phonemeCodepoints = TtsEngine.phonemize(text)
                genText = ""
            }

            for (attempt in 1..2) {
                if (stopRequested.get()) {
                    callback.error()
                    return
                }
                try {
                    val generated = tts.generateWithConfigAndCallback(
                        text = genText,
                        config = genConfig,
                        callback = ttsCallback,
                    )
                    if (stopRequested.get()) {
                        callback.error()
                        return
                    }
                    if (deliveredBytes.get() == 0 && generated.samples.isNotEmpty()) {
                        ttsCallback(generated.samples)
                    }
                    if (deliveredBytes.get() > 0) {
                        callback.done()
                        return
                    }
                    Log.w(TAG, "TTS generated no audio (attempt $attempt)")
                } catch (e: Exception) {
                    Log.e(TAG, "TTS synthesis failed (attempt $attempt)", e)
                    // Never replay text after partial audio: that would duplicate speech.
                    if (deliveredBytes.get() > 0) break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "TTS preparation failed", e)
        }

        callback.error()
    }

    private fun floatArrayToByteArray(audio: FloatArray): ByteArray {
        // byteArray is actually a ShortArray
        val byteArray = ByteArray(audio.size * 2)
        for (i in audio.indices) {
            val sample = (audio[i] * 32767).toInt()
            byteArray[2 * i] = sample.toByte()
            byteArray[2 * i + 1] = (sample shr 8).toByte()
        }
        return byteArray
    }
}
