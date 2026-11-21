#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

#include "arg.h"
#include "chat.h"
#include "common.h"
#include "llama.h"
#include "sampling.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#define LOG_TAG "Zonos3Mini"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
std::once_flag g_backend_once;

struct Engine {
    common_params params;
    common_init_result_ptr llama_init;
    mtmd::context_ptr mctx;
    std::mutex mutex;
    std::atomic<bool> cancelled{false};
    int sample_rate = 44100;
    std::string voice;

    Engine(const std::string & backbone, const std::string & mmproj, int threads) {
        std::call_once(g_backend_once, [] {
            llama_backend_init();
        });
        params.model.path = backbone;
        params.mmproj.path = mmproj;
        params.embedding = true;
        params.n_ctx = 4096;
        params.n_batch = 512;
        params.n_ubatch = 128;
        params.n_gpu_layers = 0;
        params.mmproj_use_gpu = false;
        params.fit_params = false;
        params.warmup = false;
        params.cpuparams.n_threads = threads;
        params.cpuparams_batch.n_threads = threads;

        llama_init = common_init_from_params(params);
        if (!llama_init || !llama_init->model() || !llama_init->context()) {
            throw std::runtime_error("backbone load failed");
        }

        mtmd_context_params mp = mtmd_context_params_default();
        mp.use_gpu = false;
        mp.n_threads = threads;
        mp.warmup = false;
        mctx.reset(mtmd_init_from_file(mmproj.c_str(), llama_init->model(), mp));
        if (!mctx) throw std::runtime_error("mmproj load failed");

        auto info = mtmd_gen_audio_get_info(mctx.get());
        if (info.type != MTMD_GEN_AUDIO_TYPE_ZONOS3) {
            throw std::runtime_error("mmproj is not a ZONOS3 audio generator");
        }
        sample_rate = info.sample_rate;
        if (info.n_voices > 0) {
            if (const char * v = mtmd_gen_audio_voice_name(mctx.get(), 0)) voice = v;
        }
    }

    std::vector<int16_t> synthesize(const std::string & input) {
        std::lock_guard<std::mutex> lock(mutex);
        cancelled.store(false);
        llama_context * lctx = llama_init->context();
        llama_model * model = llama_init->model();
        common_sampler * smpl = llama_init->sampler(0);
        llama_memory_clear(llama_get_memory(lctx), true);
        common_sampler_reset(smpl);

        auto tmpls = common_chat_templates_init(model, "");
        std::string prompt = common_chat_get_tts_prompt(
            tmpls.get(), input, "tr", voice,
            [&](const std::string & word) {
                char buf[512];
                return mtmd_gen_audio_g2p(mctx.get(), word.c_str(), buf, sizeof(buf)) > 0 ? std::string(buf) : std::string();
            });
        if (prompt.empty()) prompt = input;

        mtmd::bitmap_ptr speaker;
        if (!voice.empty()) {
            speaker.reset(mtmd_gen_audio_get_voice(mctx.get(), voice.c_str()));
            if (!speaker) throw std::runtime_error("default voice unavailable");
        }

        mtmd_helper::gen_audio gen;
        gen.init(lctx, mctx.get());
        if (!gen.valid()) throw std::runtime_error("audio pipeline init failed");

        auto inp = mtmd_helper_gen_audio_inp_default();
        inp.seq_id = 0;
        inp.prompt = prompt.c_str();
        inp.prompt_len = prompt.size();
        inp.speaker_ref = speaker.get();
        inp.lang = "tr";
        inp.out_type = MTMD_HELPER_GEN_AUDIO_OUTTYPE_PCM;
        inp.stream = false;

        if (gen.set_input(&inp) != 0) throw std::runtime_error("audio input rejected");
        for (;;) {
            int32_t r = gen.step_prompt(llama_n_batch(lctx));
            if (r < 0) throw std::runtime_error("prompt processing failed");
            if (r == 0) break;
            if (cancelled.load()) throw std::runtime_error("cancelled");
        }

        auto sample_semantic = [&]() -> llama_token {
            llama_token t = common_sampler_sample(smpl, lctx, -1);
            common_sampler_accept(smpl, t, true);
            return t;
        };

        int max_frames = gen.max_frames(900);
        llama_token sampled = sample_semantic();
        const float * h_state = llama_get_embeddings_ith(lctx, -1);
        bool stop = false;
        for (int n = 0; !stop && n < max_frames; ++n) {
            if (cancelled.load()) throw std::runtime_error("cancelled");
            const float * h_next = nullptr;
            if (gen.step_gen(sampled, h_state, &h_next, &stop) != 0) {
                throw std::runtime_error("audio generation failed");
            }
            if (!h_next) break;
            h_state = h_next;
            sampled = sample_semantic();
        }

        int32_t rate = 0;
        const char * data = nullptr;
        size_t data_len = 0;
        int64_t n_samples = 0;
        if (gen.get_output(&rate, &data, &data_len, &n_samples) != 0 || !data || data_len == 0) {
            throw std::runtime_error("decoder returned no PCM");
        }
        sample_rate = rate;
        if (data_len % sizeof(float) != 0) throw std::runtime_error("invalid PCM buffer");
        size_t count = data_len / sizeof(float);
        const float * pcm = reinterpret_cast<const float *>(data);
        std::vector<int16_t> out(count);
        for (size_t i = 0; i < count; ++i) {
            float x = std::isfinite(pcm[i]) ? std::max(-1.0f, std::min(1.0f, pcm[i])) : 0.0f;
            out[i] = static_cast<int16_t>(std::lrintf(x * 32767.0f));
        }
        return out;
    }
};

void throw_java(JNIEnv * env, const std::string & message) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls) env->ThrowNew(cls, message.c_str());
}

std::string jstr(JNIEnv * env, jstring s) {
    if (!s) return {};
    const char * p = env->GetStringUTFChars(s, nullptr);
    std::string out = p ? p : "";
    if (p) env->ReleaseStringUTFChars(s, p);
    return out;
}

Engine * from(jlong h) { return reinterpret_cast<Engine *>(static_cast<intptr_t>(h)); }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_osman_zonos3tts_ZonosNative_create(JNIEnv * env, jclass, jstring backbone, jstring mmproj, jint threads) {
    try {
        auto * e = new Engine(jstr(env, backbone), jstr(env, mmproj), std::max(1, (int) threads));
        return static_cast<jlong>(reinterpret_cast<intptr_t>(e));
    } catch (const std::exception & ex) {
        LOGE("create: %s", ex.what());
        throw_java(env, ex.what());
        return 0;
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_osman_zonos3tts_ZonosNative_synthesize(JNIEnv * env, jclass, jlong handle, jstring text) {
    Engine * e = from(handle);
    if (!e) { throw_java(env, "engine not loaded"); return nullptr; }
    try {
        auto pcm = e->synthesize(jstr(env, text));
        jsize bytes = static_cast<jsize>(pcm.size() * sizeof(int16_t));
        jbyteArray arr = env->NewByteArray(bytes);
        if (!arr) throw std::runtime_error("PCM allocation failed");
        env->SetByteArrayRegion(arr, 0, bytes, reinterpret_cast<const jbyte *>(pcm.data()));
        return arr;
    } catch (const std::exception & ex) {
        LOGE("synthesize: %s", ex.what());
        throw_java(env, ex.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_osman_zonos3tts_ZonosNative_sampleRate(JNIEnv *, jclass, jlong handle) {
    Engine * e = from(handle);
    return e ? e->sample_rate : 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_osman_zonos3tts_ZonosNative_cancel(JNIEnv *, jclass, jlong handle) {
    if (Engine * e = from(handle)) e->cancelled.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_osman_zonos3tts_ZonosNative_destroy(JNIEnv *, jclass, jlong handle) {
    delete from(handle);
}
