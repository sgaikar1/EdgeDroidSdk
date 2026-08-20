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
#include "mtmd.h"
#include "mtmd-helper.h"

#define TAG "EdgeDroid.LlamaJNI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

struct LlamaSession {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    int32_t n_batch = 256;
    int32_t n_threads = 4;
    std::atomic<bool> stop{false};
    // Vision (mmproj) context — loaded when a vision model is used.
    mtmd_context* mm = nullptr;
    // Cached KV prefix (system prompt + user opener). Decoded once, reused across calls.
    std::vector<llama_token> cached_prefix;
    bool prefix_valid = false;
    // Serializes decode/generate so a concurrent call can't corrupt the shared KV cache.
    std::mutex session_mutex;
};

static std::mutex g_mutex;
static std::unordered_map<jlong, LlamaSession*> g_sessions;

// Last llama.cpp ERROR/WARN log lines, captured so load failures can be surfaced.
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

// Lenient UTF-8 -> UTF-16 conversion for JNI. Byte-fallback tokenizers (e.g. Qwen3, GPT-2
// style) can split one multi-byte character across two tokens, so a single piece may hold a
// truncated/invalid UTF-8 sequence. NewStringUTF would abort on that; NewString() does not
// validate, and malformed bytes are replaced with U+FFFD so streaming never crashes.
static jstring to_jstring(JNIEnv* env, const char* data, size_t len) {
    std::vector<jchar> out;
    out.reserve(len);
    size_t i = 0;
    while (i < len) {
        const unsigned char c = (unsigned char) data[i];
        uint32_t cp = 0;
        int extra = 0;
        if (c < 0x80) {
            cp = c;
        } else if ((c & 0xE0) == 0xC0) {
            cp = c & 0x1F; extra = 1;
        } else if ((c & 0xF0) == 0xE0) {
            cp = c & 0x0F; extra = 2;
        } else if ((c & 0xF8) == 0xF0) {
            cp = c & 0x07; extra = 3;
        } else {
            out.push_back(0xFFFD);
            i++;
            continue;
        }
        bool valid = i + extra < len;
        for (int k = 1; valid && k <= extra; ++k) {
            const unsigned char cc = (unsigned char) data[i + k];
            if ((cc & 0xC0) != 0x80) {
                valid = false;
            } else {
                cp = (cp << 6) | (cc & 0x3F);
            }
        }
        if (!valid) {
            out.push_back(0xFFFD);
            i++;
            continue;
        }
        i += extra + 1;
        if (cp >= 0x10000 && cp <= 0x10FFFF) {
            cp -= 0x10000;
            out.push_back((jchar)(0xD800 | (cp >> 10)));
            out.push_back((jchar)(0xDC00 | (cp & 0x3FF)));
        } else {
            out.push_back((jchar) cp);
        }
    }
    return env->NewString(out.data(), (jsize) out.size());
}

// Decodes tokens into the KV cache at explicit positions [base, base+n). Positions are
// explicit so the prefix (0..P-1) can be cached and reused; the body starts at P. Only the
// final prompt token requests logits (needed to sample the first generated token).
static bool decode_tokens_at(LlamaSession* s, const std::vector<llama_token>& tokens,
                             llama_pos base, bool want_last_logits) {
    const int32_t n_total = (int32_t) tokens.size();
    if (n_total == 0) return true;
    for (int32_t off = 0; off < n_total; off += s->n_batch) {
        const int32_t n = std::min(s->n_batch, n_total - off);
        llama_batch batch = llama_batch_init(n, 0, 1);
        batch.n_tokens = n;
        for (int32_t i = 0; i < n; ++i) {
            batch.token[i] = tokens[off + i];
            batch.pos[i] = base + off + i;
            batch.n_seq_id[i] = 1;
            batch.seq_id[i][0] = 0; // seq_id[i] is pre-allocated (n_seq_max=1); write into it
            batch.logits[i] = want_last_logits && (off + i == n_total - 1);
        }
        const bool ok = llama_decode(s->ctx, batch) == 0;
        llama_batch_free(batch);
        if (!ok) return false;
    }
    return true;
}

// Reads llama.cpp context perf counters and packs them in the layout used by
// NativeLlama.kt: [t_p_eval_ms, t_eval_ms, n_p_eval, n_eval]. These are cumulative
// session counters; callers that want a per-call value snapshot before and after.
static jdoubleArray perf_to_jdoublearray(JNIEnv* env, const llama_perf_context_data& perf) {
    const double vals[4] = {
        perf.t_p_eval_ms,
        perf.t_eval_ms,
        (double) perf.n_p_eval,
        (double) perf.n_eval,
    };
    jdoubleArray result = env->NewDoubleArray(4);
    if (result) env->SetDoubleArrayRegion(result, 0, 4, vals);
    return result;
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_sgaikar1_edgedroid_runtime_llama_NativeLlama_nativePerf(
        JNIEnv* env, jobject, jlong handle) {
    LlamaSession* s = lookup_session(handle);
    if (!s || !s->ctx) return nullptr;
    return perf_to_jdoublearray(env, llama_perf_context(s->ctx));
}

extern "C" JNIEXPORT void JNICALL
Java_com_sgaikar1_edgedroid_runtime_llama_NativeLlama_nativeInit(JNIEnv*, jobject) {
    llama_backend_init();
    llama_log_set(log_capture_cb, nullptr);
}

// Number of GPU (Vulkan) devices the backend actually enumerated. 0 = CPU only.
extern "C" JNIEXPORT jint JNICALL
Java_com_sgaikar1_edgedroid_runtime_llama_NativeLlama_nativeGpuDeviceCount(JNIEnv*, jobject) {
    int count = 0;
    const size_t n_devs = ggml_backend_dev_count();
    for (size_t i = 0; i < n_devs; ++i) {
        if (ggml_backend_dev_type(ggml_backend_dev_get(i)) == GGML_BACKEND_DEVICE_TYPE_GPU) {
            count++;
        }
    }
    return count;
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

    g_last_error.clear();
    llama_log_set(log_capture_cb, nullptr);

    llama_model* model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(jPath, path);
    if (!model) {
        std::string message = "failed to load GGUF model";
        if (!g_last_error.empty()) {
            message += ": " + g_last_error;
        }
        LOGE("%s", message.c_str());
        jni_throw(env, message.c_str());
        return 0L;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = nCtx > 0 ? (uint32_t) nCtx : 0;
    cparams.n_batch = (uint32_t) nBatch;
    cparams.n_threads = nThreads;
    cparams.n_threads_batch = nThreadsBatch;
    // Measure decode/eval timings so the streaming metrics (tok/s, TTFT) have real numbers.
    // The vendored llama.cpp default for no_perf is true, which would zero out llama_perf_*.
    cparams.no_perf = false;

    g_last_error.clear();
    llama_context* ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        std::string message = "failed to create context";
        if (!g_last_error.empty()) {
            message += ": " + g_last_error;
        }
        llama_model_free(model);
        LOGE("%s", message.c_str());
        jni_throw(env, message.c_str());
        return 0L;
    }

    auto* session = new LlamaSession();
    session->model = model;
    session->ctx = ctx;
    session->n_batch = nBatch > 0 ? nBatch : 256;
    session->n_threads = nThreads > 0 ? nThreads : 4;

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

extern "C" JNIEXPORT jboolean JNICALL
Java_com_sgaikar1_edgedroid_runtime_llama_NativeLlama_nativeSetPrefix(
        JNIEnv* env, jobject, jlong handle, jstring jPrefix) {

    LlamaSession* s = lookup_session(handle);
    if (!s || !s->model || !s->ctx) {
        jni_throw(env, "model not loaded");
        return JNI_FALSE;
    }
    std::lock_guard<std::mutex> lock(s->session_mutex);

    const char* prefix = env->GetStringUTFChars(jPrefix, nullptr);
    std::string text(prefix ? prefix : "");
    env->ReleaseStringUTFChars(jPrefix, prefix);

    const llama_vocab* vocab = llama_model_get_vocab(s->model);
    if (!vocab) return JNI_FALSE;

    std::vector<llama_token> tokens(text.length() + 8);
    int32_t n_tok = llama_tokenize(vocab, text.data(), (int32_t) text.length(),
                                   tokens.data(), (int32_t) tokens.size(), true, true);
    if (n_tok < 0) n_tok = 0;
    tokens.resize(n_tok);

    // Same prefix as last time -> KV is already cached; skip the re-decode.
    if (s->prefix_valid && s->cached_prefix == tokens) {
        return JNI_TRUE;
    }

    // New prefix: clear the whole cache and decode the prefix once at positions 0..P-1.
    llama_memory_seq_rm(llama_get_memory(s->ctx), -1, -1, -1);
    if (!decode_tokens_at(s, tokens, 0, false)) {
        std::string message = "prefix decoding failed";
        if (!g_last_error.empty()) message += ": " + g_last_error;
        jni_throw(env, message.c_str());
        return JNI_FALSE;
    }
    s->cached_prefix = std::move(tokens);
    s->prefix_valid = true;
    return JNI_TRUE;
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_sgaikar1_edgedroid_runtime_llama_NativeLlama_nativeGenerate(
        JNIEnv* env, jobject,
        jlong handle, jstring jBody,
        jfloat temperature, jint topK, jfloat topP, jfloat minP,
        jint maxTokens, jfloat repeatPenalty, jint seed,
        jobject callback) {

    LlamaSession* s = lookup_session(handle);
    if (!s || !s->model || !s->ctx) {
        jni_throw(env, "model not loaded");
        return nullptr;
    }
    std::lock_guard<std::mutex> lock(s->session_mutex);
    s->stop = false;

    const llama_vocab* vocab = llama_model_get_vocab(s->model);
    if (!vocab) return nullptr;

    // The prefix (system prompt) KV lives at positions [0, P). Trim everything >= P so a
    // shorter previous body doesn't leave stale KV behind, then decode the body at P.
    const llama_pos prefix_len = s->prefix_valid ? (llama_pos) s->cached_prefix.size() : 0;
    llama_memory_seq_rm(llama_get_memory(s->ctx), -1, prefix_len, -1);

    const char* body = env->GetStringUTFChars(jBody, nullptr);
    std::string text(body ? body : "");
    env->ReleaseStringUTFChars(jBody, body);

    std::vector<llama_token> tokens(text.length() + 8);
    int32_t n_tok = llama_tokenize(vocab, text.data(), (int32_t) text.length(),
                                   tokens.data(), (int32_t) tokens.size(), true, true);
    if (n_tok < 0) n_tok = 0;
    tokens.resize(n_tok);

    // Guard: prefix + body + generated tokens must fit the context window.
    const int32_t total = prefix_len + n_tok;
    const int32_t room = (int32_t) llama_n_ctx(s->ctx) - maxTokens - 1;
    if (room <= 0) {
        jni_throw(env, "context window too small for the requested maxTokens");
        return nullptr;
    }
    if (total > room) {
        jni_throw(env, ("prompt of " + std::to_string(total) +
                        " tokens exceeds the context window (n_ctx=" +
                        std::to_string((int32_t) llama_n_ctx(s->ctx)) +
                        ", maxTokens=" + std::to_string(maxTokens) + ")").c_str());
        return nullptr;
    }

    if (!decode_tokens_at(s, tokens, prefix_len, true)) {
        std::string message = "prompt decoding failed";
        if (!g_last_error.empty()) message += ": " + g_last_error;
        jni_throw(env, message.c_str());
        return nullptr;
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
            jstring jPiece = to_jstring(env, piece, (size_t) np);
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

    // Absolute cumulative counters at completion; the caller diffs against a snapshot taken
    // before the call (which also covers the cached-prefix decode in nativeSetPrefix).
    return perf_to_jdoublearray(env, llama_perf_context(s->ctx));
}

// Load the mmproj vision encoder (image->embeddings) for a vision-capable model.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_sgaikar1_edgedroid_runtime_llama_NativeLlama_nativeLoadVisionModel(
        JNIEnv* env, jobject, jlong handle, jstring jMmproj, jint nThreads) {

    LlamaSession* s = lookup_session(handle);
    if (!s || !s->model || !s->ctx) {
        jni_throw(env, "model not loaded");
        return JNI_FALSE;
    }
    const char* path = env->GetStringUTFChars(jMmproj, nullptr);
    if (!path) return JNI_FALSE;

    if (s->mm) {
        mtmd_free(s->mm);
        s->mm = nullptr;
    }
    mtmd_context_params params = mtmd_context_params_default();
    params.use_gpu = false;
    params.n_threads = nThreads > 0 ? nThreads : s->n_threads;
    // SmolVLM (and most LLaVA-style GGUF VLMs) use "<image>" as the media marker.
    params.media_marker = "<image>";

    s->mm = mtmd_init_from_file(path, s->model, params);
    env->ReleaseStringUTFChars(jMmproj, path);
    if (!s->mm) {
        LOGE("failed to init mtmd/mmproj");
        jni_throw(env, "failed to load mmproj vision model");
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

// Image -> text generation: the prompt text must contain the <image> marker where the
// image tokens are inserted. Uses the mtmd helper to process text + image chunks, then the
// standard sampling loop.
extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_sgaikar1_edgedroid_runtime_llama_NativeLlama_nativeGenerateVision(
        JNIEnv* env, jobject,
        jlong handle, jstring jPrompt, jbyteArray jImage,
        jfloat temperature, jint topK, jfloat topP, jfloat minP,
        jint maxTokens, jfloat repeatPenalty, jint seed,
        jobject callback) {

    LlamaSession* s = lookup_session(handle);
    if (!s || !s->model || !s->ctx || !s->mm) {
        jni_throw(env, "vision model not loaded");
        return nullptr;
    }
    std::lock_guard<std::mutex> lock(s->session_mutex);
    s->stop = false;

    const llama_vocab* vocab = llama_model_get_vocab(s->model);
    if (!vocab) return nullptr;

    const char* prompt = env->GetStringUTFChars(jPrompt, nullptr);
    std::string text(prompt ? prompt : "");
    env->ReleaseStringUTFChars(jPrompt, prompt);

    jsize imgLen = env->GetArrayLength(jImage);
    jbyte* imgBuf = env->GetByteArrayElements(jImage, nullptr);

    // Stateless: start from position 0.
    llama_memory_seq_rm(llama_get_memory(s->ctx), -1, -1, -1);

    mtmd_input_text inp;
    inp.text = text.c_str();
    inp.text_len = (int32_t) text.size();
    inp.add_special = true;
    inp.parse_special = true;

    mtmd::input_chunks chunks(mtmd_input_chunks_init());
    mtmd_helper_bitmap_wrapper bw =
        mtmd_helper_bitmap_init_from_buf(s->mm, (const unsigned char*) imgBuf, (size_t) imgLen, false);
    env->ReleaseByteArrayElements(jImage, imgBuf, JNI_ABORT);

    if (bw.bitmap == nullptr) {
        jni_throw(env, "failed to decode image");
        return nullptr;
    }

    const mtmd_bitmap* bitmaps[1] = { bw.bitmap };
    if (mtmd_tokenize(s->mm, chunks.ptr.get(), &inp, bitmaps, 1) != 0) {
        mtmd_bitmap_free(bw.bitmap);
        jni_throw(env, "vision prompt tokenization failed");
        return nullptr;
    }

    llama_pos n_past = 0;
    if (mtmd_helper_eval_chunks(s->mm, s->ctx, chunks.ptr.get(), 0, 0, s->n_batch, true, &n_past) != 0) {
        mtmd_bitmap_free(bw.bitmap);
        jni_throw(env, "vision prompt decoding failed");
        return nullptr;
    }
    mtmd_bitmap_free(bw.bitmap);

    // --- sampler chain (same as text generation) ---
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

    char piece[128];
    llama_token next[1] = {0};
    for (int i = 0; i < maxTokens && !s->stop; i++) {
        llama_token id = llama_sampler_sample(smpl, s->ctx, -1);
        llama_sampler_accept(smpl, id);
        if (llama_vocab_is_eog(vocab, id)) break;

        int32_t np = llama_token_to_piece(vocab, id, piece, (int32_t) sizeof(piece), 0, false);
        if (np > 0) {
            jstring jPiece = to_jstring(env, piece, (size_t) np);
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

    // Absolute cumulative counters at completion (vision path has no prefix cache to diff).
    return perf_to_jdoublearray(env, llama_perf_context(s->ctx));
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
    if (s->mm) mtmd_free(s->mm);
    if (s->ctx) llama_free(s->ctx);
    if (s->model) llama_model_free(s->model);
    delete s;
}
