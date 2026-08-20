// EdgeDroid whisper.cpp JNI bridge.
//
// The only file in the whole SDK that knows whisper.cpp exists. Everything here stays
// behind the `NativeWhisper` Kotlin object; native pointers never leave this file.
// Keep this file small and self-contained.
//
// Note: whisper.cpp is vendored as a git submodule (runtime-whisper/src/main/cpp/whisper.cpp)
// and is used as an unmodified dependency — no whisper.cpp sources are changed here.

#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <string>
#include <vector>
#include <atomic>
#include <mutex>
#include <unordered_map>

#include "whisper.h"
#include "ggml.h"

#define TAG "EdgeDroid.WhisperJNI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// whisper.cpp decodes 16 kHz mono audio internally; input is (linearly) resampled to this.
static constexpr int kTargetSampleRate = 16000;

struct WhisperSession {
    whisper_context* ctx = nullptr;
    std::atomic<bool> stop{false};
    // Serializes transcription so a concurrent call can't corrupt the shared context.
    std::mutex session_mutex;
};

static std::mutex g_mutex;
static std::unordered_map<jlong, WhisperSession*> g_sessions;

// Last whisper.cpp ERROR/WARN log lines, captured so load failures can be surfaced.
static std::string g_last_error;

static void log_capture_cb(ggml_log_level level, const char* text, void* /*user_data*/) {
    if (text == nullptr) return;
    std::string s(text);
    if (!s.empty() && s.back() == '\n') s.pop_back();
    if (s.empty()) return;
    if (level == GGML_LOG_LEVEL_ERROR || level == GGML_LOG_LEVEL_WARN) {
        if (g_last_error.size() < 2048) g_last_error += s + " | ";
        else g_last_error.replace(g_last_error.end() - 3, g_last_error.end(), s + " | ");
    }
}

static void register_session(jlong handle, WhisperSession* session) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_sessions[handle] = session;
}

static WhisperSession* lookup_session(jlong handle) {
    std::lock_guard<std::mutex> lock(g_mutex);
    auto it = g_sessions.find(handle);
    return it == g_sessions.end() ? nullptr : it->second;
}

static WhisperSession* remove_session(jlong handle) {
    std::lock_guard<std::mutex> lock(g_mutex);
    auto it = g_sessions.find(handle);
    if (it == g_sessions.end()) return nullptr;
    WhisperSession* s = it->second;
    g_sessions.erase(it);
    return s;
}

static void jni_throw(JNIEnv* env, const char* message) {
    jclass ex = env->FindClass("java/lang/RuntimeException");
    env->ThrowNew(ex, message);
}

// 16-bit little-endian PCM -> float samples in [-1, 1] (what whisper_full expects).
static std::vector<float> pcm_to_float(const jbyte* data, jsize len) {
    std::vector<float> out;
    out.reserve((size_t) (len / 2));
    for (jsize i = 0; i + 1 < len; i += 2) {
        int16_t s = (int16_t) ((uint8_t) data[i] | ((uint16_t) (uint8_t) data[i + 1] << 8));
        out.push_back((float) s / 32768.0f);
    }
    return out;
}

// Simple linear-interpolation resampler. Whisper requires 16 kHz; recordings and WAV
// files often aren't. Quality is adequate for transcription demos.
static std::vector<float> resample(const std::vector<float>& in, int in_rate, int out_rate) {
    if (in_rate == out_rate || in.empty()) return in;
    const double ratio = (double) in_rate / (double) out_rate;
    const size_t out_size = (size_t) ((double) in.size() / ratio);
    std::vector<float> out(out_size);
    for (size_t i = 0; i < out_size; ++i) {
        const double src = (double) i * ratio;
        const size_t i0 = (size_t) src;
        const size_t i1 = i0 + 1 < in.size() ? i0 + 1 : i0;
        const float frac = (float) (src - (double) i0);
        out[i] = in[i0] * (1.0f - frac) + in[i1] * frac;
    }
    return out;
}

// Abort hook: whisper_full polls this during compute; return false to stop.
static bool abort_cb(void* data) {
    auto* s = static_cast<WhisperSession*>(data);
    return !s->stop.load();
}

struct SegmentCallbackCtx {
    JNIEnv* env;
    jobject callback;           // NativeWhisper.SegmentCallback
    jmethodID onSegment;
};

// Called by whisper.cpp for every newly finalized segment. Runs on the thread that
// called whisper_full() — the attached JVM thread — so JNI calls are safe here.
static void on_new_segment(whisper_context* /*ctx*/, whisper_state* state, int n_new, void* user_data) {
    auto* cb = static_cast<SegmentCallbackCtx*>(user_data);
    if (!cb || !cb->env || !cb->callback) return;

    const int n_segments = whisper_full_n_segments_from_state(state);
    for (int i = n_segments - n_new; i < n_segments; ++i) {
        const int64_t t0 = whisper_full_get_segment_t0_from_state(state, i);
        const int64_t t1 = whisper_full_get_segment_t1_from_state(state, i);
        const char* text = whisper_full_get_segment_text_from_state(state, i);
        if (text == nullptr) continue;

        jstring jText = cb->env->NewStringUTF(text);
        cb->env->CallVoidMethod(cb->callback, cb->onSegment, (jlong) t0, (jlong) t1, jText);
        cb->env->DeleteLocalRef(jText);
        if (cb->env->ExceptionCheck()) {
            cb->env->ExceptionClear();
            break;
        }
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_sgaikar1_edgedroid_runtime_whisper_NativeWhisper_nativeInit(JNIEnv*, jobject) {
    whisper_log_set(log_capture_cb, nullptr);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_sgaikar1_edgedroid_runtime_whisper_NativeWhisper_nativeLoadModel(
        JNIEnv* env, jobject, jstring jPath, jint nThreads) {

    const char* path = env->GetStringUTFChars(jPath, nullptr);
    if (!path) return 0L;

    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false; // CPU-only on Android (ggml CPU backend).

    g_last_error.clear();
    whisper_log_set(log_capture_cb, nullptr);

    whisper_context* ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(jPath, path);

    if (!ctx) {
        std::string message = "failed to load whisper model";
        if (!g_last_error.empty()) {
            message += ": " + g_last_error;
        }
        LOGE("%s", message.c_str());
        jni_throw(env, message.c_str());
        return 0L;
    }

    auto* session = new WhisperSession();
    session->ctx = ctx;

    jlong handle = reinterpret_cast<jlong>(session);
    register_session(handle, session);
    return handle;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_sgaikar1_edgedroid_runtime_whisper_NativeWhisper_nativeTranscribe(
        JNIEnv* env, jobject,
        jlong handle,
        jbyteArray jPcm, jint sampleRate,
        jstring jLanguage, jint nThreads,
        jfloat temperature, jstring jInitialPrompt,
        jint maxSegmentChars, jboolean singleSegment, jboolean translate,
        jobject callback) {

    WhisperSession* s = lookup_session(handle);
    if (!s || !s->ctx) {
        jni_throw(env, "whisper model not loaded");
        return JNI_FALSE;
    }
    std::lock_guard<std::mutex> lock(s->session_mutex);
    s->stop = false;

    if (sampleRate <= 0) {
        jni_throw(env, "invalid sample rate");
        return JNI_FALSE;
    }

    jsize pcmLen = env->GetArrayLength(jPcm);
    jbyte* pcmBuf = env->GetByteArrayElements(jPcm, nullptr);
    std::vector<float> samples = pcm_to_float(pcmBuf, pcmLen);
    env->ReleaseByteArrayElements(jPcm, pcmBuf, JNI_ABORT);
    samples = resample(samples, sampleRate, kTargetSampleRate);

    if (samples.empty()) {
        jni_throw(env, "no audio samples provided");
        return JNI_FALSE;
    }

    const char* lang = jLanguage ? env->GetStringUTFChars(jLanguage, nullptr) : nullptr;
    const bool auto_lang = (lang == nullptr) || (lang[0] == '\0') || (strcmp(lang, "auto") == 0);
    const char* prompt = jInitialPrompt ? env->GetStringUTFChars(jInitialPrompt, nullptr) : nullptr;

    whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wparams.n_threads = nThreads > 0 ? nThreads : 4;
    wparams.translate = translate != 0;
    wparams.language = auto_lang ? nullptr : lang;
    wparams.detect_language = auto_lang;
    wparams.temperature = temperature;
    wparams.initial_prompt = prompt;
    wparams.max_len = maxSegmentChars > 0 ? maxSegmentChars : 0;
    wparams.single_segment = singleSegment != 0;
    wparams.print_progress = false;
    wparams.print_realtime = false;
    wparams.print_timestamps = false;
    wparams.print_special = false;

    // Streaming/partial results: segments are delivered as they are finalized.
    SegmentCallbackCtx cb = {};
    if (callback != nullptr) {
        jclass cbClass = env->GetObjectClass(callback);
        cb.env = env;
        cb.callback = env->NewGlobalRef(callback);
        cb.onSegment = env->GetMethodID(cbClass, "onSegment", "(JJLjava/lang/String;)V");
        env->DeleteLocalRef(cbClass);
        wparams.new_segment_callback = on_new_segment;
        wparams.new_segment_callback_user_data = &cb;
    }
    wparams.abort_callback = abort_cb;
    wparams.abort_callback_user_data = s;

    const int rc = whisper_full(s->ctx, wparams, samples.data(), (int) samples.size());

    if (lang) env->ReleaseStringUTFChars(jLanguage, lang);
    if (prompt) env->ReleaseStringUTFChars(jInitialPrompt, prompt);
    if (cb.callback) {
        env->DeleteGlobalRef(cb.callback);
        cb.callback = nullptr;
    }

    if (rc != 0) {
        jni_throw(env, "whisper transcription failed");
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

// Detected (or used) language id -> ISO-639-1 code, valid after a transcription pass.
extern "C" JNIEXPORT jstring JNICALL
Java_com_sgaikar1_edgedroid_runtime_whisper_NativeWhisper_nativeLanguage(JNIEnv* env, jobject, jlong handle) {
    WhisperSession* s = lookup_session(handle);
    if (!s || !s->ctx) return nullptr;
    const int lang_id = whisper_full_lang_id(s->ctx);
    if (lang_id < 0) return nullptr;
    const char* lang = whisper_lang_str(lang_id);
    if (!lang) return nullptr;
    return env->NewStringUTF(lang);
}

extern "C" JNIEXPORT void JNICALL
Java_com_sgaikar1_edgedroid_runtime_whisper_NativeWhisper_nativeStop(JNIEnv*, jobject, jlong handle) {
    WhisperSession* s = lookup_session(handle);
    if (s) s->stop = true;
}

extern "C" JNIEXPORT void JNICALL
Java_com_sgaikar1_edgedroid_runtime_whisper_NativeWhisper_nativeUnload(JNIEnv*, jobject, jlong handle) {
    WhisperSession* s = remove_session(handle);
    if (!s) return;
    if (s->ctx) whisper_free(s->ctx);
    delete s;
}