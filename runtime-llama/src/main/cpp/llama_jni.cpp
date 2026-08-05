// EdgeDroid llama.cpp JNI bridge.
//
// The only file in the whole SDK that knows llama.cpp exists. Everything here stays
// behind the `NativeLlama` Kotlin object; native pointers never leave this file.
// Keep this file small and self-contained (< ~300 lines).

#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <string>
#include <vector>
#include <atomic>
#include <mutex>
#include <unordered_map>
#include <ctime>

#include "llama.h"

#define TAG "EdgeDroid.LlamaJNI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

struct LlamaSession {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    std::atomic<bool> stop{false};
};

static std::mutex g_mutex;
static std::unordered_map<jlong, LlamaSession*> g_sessions;

static void register_session(jlong handle, LlamaSession* session) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_sessions[handle] = session;
}

static LlamaSession* lookup_session(jlong handle) {
    std::lock_guard<std::mutex> lock(g_mutex);
    auto it = g_sessions.find(handle);
    return it == g_sessions.end() ? nullptr : it->second;
}

static LlamaSession* remove_session(jlong handle) {
    std::lock_guard<std::mutex> lock(g_mutex);
    auto it = g_sessions.find(handle);
    if (it == g_sessions.end()) return nullptr;
    LlamaSession* s = it->second;
    g_sessions.erase(it);
    return s;
}

static void jni_throw(JNIEnv* env, const char* message) {
    jclass ex = env->FindClass("java/lang/RuntimeException");
    env->ThrowNew(ex, message);
}

extern "C" JNIEXPORT void JNICALL
Java_com_sgaikar1_edgedroid_runtime_llama_NativeLlama_nativeInit(JNIEnv*, jobject) {
    llama_backend_init();
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_sgaikar1_edgedroid_runtime_llama_NativeLlama_nativeLoadModel(
        JNIEnv* env, jobject,
        jstring jPath,
        jint nCtx, jint nThreads, jint nThreadsBatch, jint nBatch,
        jint nGpuLayers, jboolean mmap) {

    const char* path = env->GetStringUTFChars(jPath, nullptr);
    if (!path) return 0L;

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = nGpuLayers;
    mparams.load_mode = mmap != 0 ? LLAMA_LOAD_MODE_MMAP : LLAMA_LOAD_MODE_NONE;

    llama_model* model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(jPath, path);
    if (!model) {
        jni_throw(env, "failed to load GGUF model");
        return 0L;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = nCtx > 0 ? (uint32_t) nCtx : 0;
    cparams.n_batch = (uint32_t) nBatch;
    cparams.n_threads = nThreads;
    cparams.n_threads_batch = nThreadsBatch;

    llama_context* ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        llama_model_free(model);
        jni_throw(env, "failed to create context");
        return 0L;
    }

    auto* session = new LlamaSession();
    session->model = model;
    session->ctx = ctx;

    jlong handle = reinterpret_cast<jlong>(session);
    register_session(handle, session);
    return handle;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_sgaikar1_edgedroid_runtime_llama_NativeLlama_nativeTokenize(
        JNIEnv* env, jobject, jlong handle, jstring jText) {

    LlamaSession* s = lookup_session(handle);
    if (!s || !s->model) return nullptr;

    const char* text = env->GetStringUTFChars(jText, nullptr);
    if (!text) return nullptr;
    size_t len = strlen(text);

    const llama_vocab* vocab = llama_model_get_vocab(s->model);
    std::vector<llama_token> tokens(len + 8);
    int n = llama_tokenize(vocab, text, (int32_t) len, tokens.data(), (int32_t) tokens.size(), false, false);
    env->ReleaseStringUTFChars(jText, text);

    if (n < 0) return nullptr;
    jintArray result = env->NewIntArray(n);
    env->SetIntArrayRegion(result, 0, n, reinterpret_cast<const jint*>(tokens.data()));
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_sgaikar1_edgedroid_runtime_llama_NativeLlama_nativeGenerate(
        JNIEnv* env, jobject,
        jlong handle, jstring jPrompt,
        jfloat temperature, jint topK, jfloat topP, jfloat minP,
        jint maxTokens, jfloat repeatPenalty, jint seed,
        jobject callback) {

    LlamaSession* s = lookup_session(handle);
    if (!s || !s->model || !s->ctx) {
        jni_throw(env, "model not loaded");
        return;
    }
    s->stop = false;

    const char* prompt = env->GetStringUTFChars(jPrompt, nullptr);
    std::string text(prompt ? prompt : "");
    env->ReleaseStringUTFChars(jPrompt, prompt);

    const llama_vocab* vocab = llama_model_get_vocab(s->model);
    if (!vocab) return;

    // --- encode prompt ---
    std::vector<llama_token> tokens(text.length() + 8);
    int32_t n_tok = llama_tokenize(vocab, text.data(), (int32_t) text.length(),
                                   tokens.data(), (int32_t) tokens.size(), true, true);
    if (n_tok < 0) n_tok = 0;
    tokens.resize(n_tok);

    if (!tokens.empty()) {
        llama_batch batch = llama_batch_get_one(tokens.data(), (int32_t) tokens.size());
        if (llama_decode(s->ctx, batch) != 0) {
            jni_throw(env, "prompt decoding failed");
            return;
        }
    }

    // --- sampler chain ---
    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    sparams.no_perf = true;
    llama_sampler* smpl = llama_sampler_chain_init(sparams);

    if (temperature > 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    }
    if (topK > 0) llama_sampler_chain_add(smpl, llama_sampler_init_top_k(topK));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(topP, 1));
    if (minP > 0.0f) llama_sampler_chain_add(smpl, llama_sampler_init_min_p(minP, 1));
    if (repeatPenalty > 1.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_penalties(
                llama_vocab_n_tokens(vocab), 64, repeatPenalty, 0.0f, 0.0f));
    }
    uint32_t rng_seed = seed >= 0 ? (uint32_t) seed : (uint32_t) time(nullptr);
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(rng_seed));

    jclass cbClass = env->GetObjectClass(callback);
    jmethodID onToken = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)V");

    // --- generation loop ---
    char piece[128];
    llama_token next[1] = {0};
    for (int i = 0; i < maxTokens && !s->stop; i++) {
        llama_token id = llama_sampler_sample(smpl, s->ctx, -1);
        llama_sampler_accept(smpl, id);

        if (llama_vocab_is_eog(vocab, id)) break;

        int32_t np = llama_token_to_piece(vocab, id, piece, (int32_t) sizeof(piece), 0, false);
        if (np > 0) {
            std::string piece_str(piece, (size_t) np);
            jstring jPiece = env->NewStringUTF(piece_str.c_str());
            env->CallVoidMethod(callback, onToken, jPiece);
            env->DeleteLocalRef(jPiece);
        }

        next[0] = id;
        llama_batch batch = llama_batch_get_one(next, 1);
        if (llama_decode(s->ctx, batch) != 0) {
            LOGE("decode failed at step %d", i);
            break;
        }
    }

    llama_sampler_free(smpl);
}

extern "C" JNIEXPORT void JNICALL
Java_com_sgaikar1_edgedroid_runtime_llama_NativeLlama_nativeStop(JNIEnv*, jobject, jlong handle) {
    LlamaSession* s = lookup_session(handle);
    if (s) s->stop = true;
}

extern "C" JNIEXPORT void JNICALL
Java_com_sgaikar1_edgedroid_runtime_llama_NativeLlama_nativeUnload(JNIEnv*, jobject, jlong handle) {
    LlamaSession* s = remove_session(handle);
    if (!s) return;
    if (s->ctx) llama_free(s->ctx);
    if (s->model) llama_model_free(s->model);
    delete s;
}
