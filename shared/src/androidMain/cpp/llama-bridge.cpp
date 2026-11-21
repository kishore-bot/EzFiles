#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <cmath>
#include <exception>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>
#include "ggml-backend.h"
#include "llama.h"

#define TAG "EzLlama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// 4096: room for longer document excerpts. LFM2 has few attention layers, so the extra KV cache is small.
static const int N_CTX = 4096;

static llama_model * model = nullptr;
static bool model_on_gpu = false;
static std::string model_path_str;
// Set once the GPU fails; every later call runs on this CPU copy instead.
static llama_model * cpu_model = nullptr;
// Engine settings from the last loadModel call; contexts are created with them.
static int engine_threads = 4;
static int engine_ubatch = 512;

/**
 * One live context per model. Fily's model is a hybrid with recurrent layers, whose memory can be
 * extended but not rewound to an earlier position, so a call either appends to what the context
 * already holds, restores the snapshot of the shared prompt start, or starts again.
 */
struct Engine {
    llama_context * ctx = nullptr;
    bool cpu_only = false;
    std::vector<llama_token> tokens;        // exactly what the context's memory holds
    std::vector<llama_token> prefix_tokens; // the shared prompt start the snapshot holds
    std::vector<uint8_t> prefix_state;
    std::mutex lock;
};

static std::mutex engines_lock;
static std::unordered_map<llama_model *, Engine *> engines;

static void log_to_logcat(ggml_log_level level, const char * text, void *) {
    if (level == GGML_LOG_LEVEL_DEBUG) return;
    __android_log_print(level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR : level == GGML_LOG_LEVEL_WARN ? ANDROID_LOG_WARN : ANDROID_LOG_INFO,
                        "EzGgml", "%s", text);
}

// The CPU and GPU backends are separate libraries, loaded by name: that finds them whether the APK
// keeps native libraries compressed inside it or extracted to disk.
static void load_backends() {
    static bool loaded = false;
    if (loaded) return;
    loaded = true;
    llama_log_set(log_to_logcat, nullptr);
    // Best first; a library the cores cannot run reports a zero score and is skipped.
    static const char * cpu_libraries[] = {
        "libggml-cpu-android_armv9.2_2.so", "libggml-cpu-android_armv9.2_1.so", "libggml-cpu-android_armv9.0_1.so",
        "libggml-cpu-android_armv8.6_1.so", "libggml-cpu-android_armv8.2_2.so", "libggml-cpu-android_armv8.2_1.so",
        "libggml-cpu-android_armv8.0_1.so", "libggml-cpu.so",
    };
    for (const char * name : cpu_libraries) {
        if (ggml_backend_load(name) != nullptr) {
            LOGI("CPU backend: %s", name);
            break;
        }
    }
    // Absent on x86_64 and on phones without a vendor OpenCL driver; the CPU still works.
    if (ggml_backend_load("libggml-opencl.so") == nullptr) LOGI("No OpenCL backend");
    for (size_t i = 0; i < ggml_backend_dev_count(); i++) {
        auto * dev = ggml_backend_dev_get(i);
        LOGI("Backend device: %s (%s)", ggml_backend_dev_name(dev), ggml_backend_dev_description(dev));
    }
}

static llama_model * load(const std::string & path, bool gpu) {
    auto params = llama_model_default_params();
    params.n_gpu_layers = gpu ? 99 : 0;
    // An empty device list keeps every tensor in host memory; n_gpu_layers = 0 alone still uses the GPU.
    static ggml_backend_dev_t no_devices[] = { nullptr };
    if (!gpu) params.devices = no_devices;
    return llama_model_load_from_file(path.c_str(), params);
}

static Engine * engine_for(llama_model * mdl, bool cpu_only) {
    std::lock_guard<std::mutex> guard(engines_lock);
    auto found = engines.find(mdl);
    if (found != engines.end()) return found->second;
    auto params = llama_context_default_params();
    params.n_ctx = N_CTX;
    params.n_batch = N_CTX;
    params.n_threads = engine_threads;
    params.n_threads_batch = engine_threads;
    params.n_ubatch = engine_ubatch;
    params.no_perf = false;
    // Otherwise large batches are still sent to the GPU even when no layers live there.
    params.op_offload = !cpu_only;
    llama_context * ctx = llama_init_from_model(mdl, params);
    if (ctx == nullptr) return nullptr;
    auto * engine = new Engine();
    engine->ctx = ctx;
    engine->cpu_only = cpu_only;
    engines[mdl] = engine;
    return engine;
}

static void free_engine(llama_model * mdl) {
    std::lock_guard<std::mutex> guard(engines_lock);
    auto found = engines.find(mdl);
    if (found == engines.end()) return;
    llama_free(found->second->ctx);
    delete found->second;
    engines.erase(found);
}

static std::vector<llama_token> tokenize(const llama_vocab * vocab, const std::string & text) {
    int n = text.size() + 16;
    std::vector<llama_token> tokens(n);
    n = llama_tokenize(vocab, text.c_str(), text.size(), tokens.data(), n, true, true);
    if (n < 0) {
        tokens.resize(-n);
        n = llama_tokenize(vocab, text.c_str(), text.size(), tokens.data(), -n, true, true);
    }
    tokens.resize(std::max(n, 0));
    return tokens;
}

// Decodes tokens[from, to) after what the context holds. Throws whatever the backend throws.
static bool decode(Engine & e, const std::vector<llama_token> & tokens, size_t from, size_t to, bool logits_last) {
    if (from >= to) return true;
    llama_batch batch = llama_batch_init(to - from, 0, 1);
    for (size_t i = from; i < to; i++) {
        batch.token[batch.n_tokens] = tokens[i];
        batch.pos[batch.n_tokens] = e.tokens.size() + (i - from);
        batch.n_seq_id[batch.n_tokens] = 1;
        batch.seq_id[batch.n_tokens][0] = 0;
        batch.logits[batch.n_tokens] = logits_last && i == to - 1;
        batch.n_tokens++;
    }
    int status = llama_decode(e.ctx, batch);
    llama_batch_free(batch);
    if (status != 0) return false;
    e.tokens.insert(e.tokens.end(), tokens.begin() + from, tokens.begin() + to);
    return true;
}

static void reset(Engine & e) {
    llama_memory_clear(llama_get_memory(e.ctx), true);
    e.tokens.clear();
}

/** Brings the context to the prompt's last token, reading as little of it as possible. */
static bool prepare(Engine & e, const std::vector<llama_token> & prompt, const std::vector<llama_token> & stable) {
    size_t common = 0;
    while (common < e.tokens.size() && common < prompt.size() && e.tokens[common] == prompt[common]) common++;
    if (common == e.tokens.size() && common < prompt.size()) {
        return decode(e, prompt, common, prompt.size(), true);
    }
    reset(e);
    bool stable_fits = !stable.empty() && stable.size() < prompt.size() && std::equal(stable.begin(), stable.end(), prompt.begin());
    if (stable_fits && e.prefix_tokens == stable && !e.prefix_state.empty() &&
        llama_state_seq_set_data(e.ctx, e.prefix_state.data(), e.prefix_state.size(), 0) > 0) {
        e.tokens = stable;
    } else if (stable_fits) {
        if (!decode(e, prompt, 0, stable.size(), false)) return false;
        e.prefix_state.resize(llama_state_seq_get_size(e.ctx, 0));
        llama_state_seq_get_data(e.ctx, e.prefix_state.data(), e.prefix_state.size(), 0);
        e.prefix_tokens = stable;
    }
    return decode(e, prompt, e.tokens.size(), prompt.size(), true);
}

/**
 * The model's top token if the grammar allows it, else the best token the grammar allows. Checking
 * the top token alone first avoids scoring all 65k candidates against the grammar on every step.
 */
static llama_token constrained_best(llama_sampler * grammar, const float * logits, int n_vocab, llama_token best,
                                    std::vector<llama_token_data> & candidates) {
    llama_token_data one = { best, logits[best], 0.0f };
    llama_token_data_array single = { &one, 1, -1, false };
    llama_sampler_apply(grammar, &single);
    if (one.logit != -INFINITY) return best;
    candidates.resize(n_vocab);
    for (int v = 0; v < n_vocab; v++) candidates[v] = { v, logits[v], 0.0f };
    llama_token_data_array all = { candidates.data(), static_cast<size_t>(n_vocab), -1, false };
    llama_sampler_apply(grammar, &all);
    llama_token allowed = -1;
    for (size_t i = 0; i < all.size; i++) {
        if (all.data[i].logit == -INFINITY) continue;
        if (allowed < 0 || all.data[i].logit > logits[allowed]) allowed = all.data[i].id;
    }
    return allowed;
}

static std::string generate(Engine & e, llama_model * mdl, const std::string & prompt, const std::string & stable_prefix, int max,
                            const std::string & grammar_text) {
    std::lock_guard<std::mutex> guard(e.lock);
    auto vocab = llama_model_get_vocab(mdl);
    auto tokens = tokenize(vocab, prompt);
    if (tokens.empty() || tokens.size() + max > N_CTX) {
        LOGE("Prompt of %zu tokens does not fit", tokens.size());
        return "{\"kind\":\"reply\",\"text\":\"That was too long for me. Try a shorter request.\",\"citations\":[]}";
    }
    llama_perf_context_reset(e.ctx);
    size_t held = e.tokens.size();
    if (!prepare(e, tokens, stable_prefix.empty() ? std::vector<llama_token>() : tokenize(vocab, stable_prefix))) {
        LOGE("Decode failed");
        reset(e);
        return "{\"kind\":\"reply\",\"text\":\"Something went wrong. Try again.\",\"citations\":[]}";
    }

    std::string result;
    llama_token eos = llama_vocab_eos(vocab);
    int n_vocab = llama_vocab_n_tokens(vocab);
    llama_sampler * grammar = grammar_text.empty() ? nullptr : llama_sampler_init_grammar(vocab, grammar_text.c_str(), "root");
    if (!grammar_text.empty() && grammar == nullptr) LOGE("Grammar did not parse; answering unconstrained");
    std::vector<llama_token_data> candidates;
    for (int i = 0; i < max; i++) {
        const float * logits = llama_get_logits_ith(e.ctx, -1);
        llama_token best = 0;
        for (int v = 1; v < n_vocab; v++) if (logits[v] > logits[best]) best = v;
        if (grammar != nullptr) {
            best = constrained_best(grammar, logits, n_vocab, best, candidates);
            if (best < 0) break;
            llama_sampler_accept(grammar, best);
        }
        if (best == eos || llama_vocab_is_eog(vocab, best)) break;

        char buf[128];
        int len = llama_token_to_piece(vocab, best, buf, sizeof(buf), 0, true);
        if (len > 0) result.append(buf, len);

        // Stop at the end of the first complete JSON object. That token is not decoded: the
        // follow-up prompt carries it, so the context stays an exact prefix of the next prompt.
        int depth = 0;
        bool in_string = false;
        bool complete = false;
        for (size_t j = 0; j < result.size(); j++) {
            char c = result[j];
            if (c == '"' && (j == 0 || result[j - 1] != '\\')) in_string = !in_string;
            if (in_string) continue;
            if (c == '{') depth++;
            if (c == '}' && --depth == 0) { result = result.substr(0, j + 1); complete = true; break; }
        }
        if (complete) break;

        std::vector<llama_token> next = { best };
        if (!decode(e, next, 0, 1, true)) {
            LOGE("Decode step %d failed", i);
            break;
        }
    }

    if (grammar != nullptr) llama_sampler_free(grammar);
    auto perf = llama_perf_context(e.ctx);
    LOGI("Timing: %zu prompt tok (%zu already held, %d read in %.0f ms), generated %d tok in %.0f ms (%.1f tok/s)",
         tokens.size(), held, perf.n_p_eval, perf.t_p_eval_ms,
         perf.n_eval, perf.t_eval_ms, perf.n_eval * 1000.0 / (perf.t_eval_ms + 1e-9));
    LOGI("Generated: %s", result.c_str());
    return result;
}

/**
 * Reads the prompt start every request shares and keeps its snapshot, then decodes one more token:
 * multi-token and single-token kernels are compiled now rather than during the first question,
 * which also starts with the shared part already read. A GPU that fails here falls back early.
 */
static void warm_up(Engine & e, llama_model * mdl, const std::string & stable_prefix) {
    std::lock_guard<std::mutex> guard(e.lock);
    auto vocab = llama_model_get_vocab(mdl);
    std::vector<llama_token> tokens = stable_prefix.empty()
        ? std::vector<llama_token>{ llama_vocab_bos(vocab) } : tokenize(vocab, stable_prefix);
    if (!decode(e, tokens, 0, tokens.size(), true) || stable_prefix.empty()) {
        reset(e);
        return;
    }
    e.prefix_state.resize(llama_state_seq_get_size(e.ctx, 0));
    llama_state_seq_get_data(e.ctx, e.prefix_state.data(), e.prefix_state.size(), 0);
    e.prefix_tokens = tokens;
    std::vector<llama_token> next = { tokens.back() };
    decode(e, next, 0, 1, true);
    reset(e);
    if (llama_state_seq_set_data(e.ctx, e.prefix_state.data(), e.prefix_state.size(), 0) > 0) e.tokens = tokens;
    LOGI("Warm-up read %zu prompt tokens", tokens.size());
}

static bool switch_to_cpu() {
    LOGI("GPU failed, falling back to CPU");
    // A failed GPU context is left alone rather than risk another throw while tearing it down.
    cpu_model = load(model_path_str, false);
    if (cpu_model == nullptr) return false;
    Engine * e = engine_for(cpu_model, true);
    return e != nullptr;
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_ezfiles_reader_platform_LlamaBridge_loadModel(
    JNIEnv *env, jobject /* this */, jstring path, jboolean useGpu, jint threads, jint ubatch, jstring stablePrefix) {

    const char *model_path = env->GetStringUTFChars(path, nullptr);
    model_path_str = model_path;
    env->ReleaseStringUTFChars(path, model_path);
    const char *stable_cstr = env->GetStringUTFChars(stablePrefix, nullptr);
    std::string stable_str(stable_cstr);
    env->ReleaseStringUTFChars(stablePrefix, stable_cstr);
    engine_threads = threads > 0 ? threads : 4;
    engine_ubatch = ubatch > 0 ? ubatch : 512;
    LOGI("Loading model: %s (GPU=%d, threads=%d, ubatch=%d)", model_path_str.c_str(), useGpu, engine_threads, engine_ubatch);

    load_backends();
    llama_backend_init();
    model = load(model_path_str, useGpu);
    model_on_gpu = useGpu;
    if (model == nullptr) {
        LOGE("Failed to load model");
        return 0;
    }
    Engine * e = engine_for(model, !useGpu);
    if (e == nullptr) {
        LOGE("Failed to create context");
        return 0;
    }
    try {
        warm_up(*e, model, stable_str);
    } catch (const std::exception & ex) {
        LOGE("Warm-up threw: %s", ex.what());
        if (!useGpu || !switch_to_cpu()) return 0;
    }
    LOGI("Model loaded successfully");
    return reinterpret_cast<jlong>(model);
}

JNIEXPORT jstring JNICALL
Java_com_ezfiles_reader_platform_LlamaBridge_runInference(
    JNIEnv *env, jobject /* this */, jlong modelPtr, jstring prompt, jint maxTokens, jstring stablePrefix, jstring grammar) {

    auto *mdl = cpu_model != nullptr ? cpu_model : reinterpret_cast<llama_model *>(modelPtr);
    if (mdl == nullptr) {
        return env->NewStringUTF("{\"kind\":\"reply\",\"text\":\"Model not loaded.\",\"citations\":[]}");
    }

    const char *prompt_cstr = env->GetStringUTFChars(prompt, nullptr);
    std::string prompt_str(prompt_cstr);
    env->ReleaseStringUTFChars(prompt, prompt_cstr);
    const char *stable_cstr = env->GetStringUTFChars(stablePrefix, nullptr);
    std::string stable_str(stable_cstr);
    env->ReleaseStringUTFChars(stablePrefix, stable_cstr);
    const char *grammar_cstr = env->GetStringUTFChars(grammar, nullptr);
    std::string grammar_str(grammar_cstr);
    env->ReleaseStringUTFChars(grammar, grammar_cstr);
    int max = maxTokens > 0 ? maxTokens : 128;

    Engine * e = engine_for(mdl, cpu_model != nullptr || !model_on_gpu);
    if (e == nullptr) return env->NewStringUTF("{\"kind\":\"reply\",\"text\":\"Context creation failed.\",\"citations\":[]}");
    try {
        return env->NewStringUTF(generate(*e, mdl, prompt_str, stable_str, max, grammar_str).c_str());
    } catch (const std::exception & ex) {
        LOGE("Inference threw: %s", ex.what());
    } catch (...) {
        LOGE("Inference threw an unknown error");
    }

    if (cpu_model != nullptr || !switch_to_cpu()) {
        return env->NewStringUTF("{\"kind\":\"reply\",\"text\":\"Something went wrong. Try again.\",\"citations\":[]}");
    }
    try {
        return env->NewStringUTF(generate(*engine_for(cpu_model, true), cpu_model, prompt_str, stable_str, max, grammar_str).c_str());
    } catch (const std::exception & ex) {
        LOGE("CPU inference threw: %s", ex.what());
        return env->NewStringUTF("{\"kind\":\"reply\",\"text\":\"Something went wrong. Try again.\",\"citations\":[]}");
    }
}

JNIEXPORT void JNICALL
Java_com_ezfiles_reader_platform_LlamaBridge_freeModel(
    JNIEnv *env, jobject /* this */, jlong modelPtr) {

    auto *mdl = reinterpret_cast<llama_model *>(modelPtr);
    if (mdl != nullptr) {
        free_engine(mdl);
        llama_model_free(mdl);
    }
    if (cpu_model != nullptr) {
        free_engine(cpu_model);
        llama_model_free(cpu_model);
        cpu_model = nullptr;
    }
    model = nullptr;
    LOGI("Model freed");
    llama_backend_free();
}

JNIEXPORT jboolean JNICALL
Java_com_ezfiles_reader_platform_LlamaBridge_gpuFailed(
    JNIEnv *env, jobject /* this */) {
    return cpu_model != nullptr;
}

JNIEXPORT jstring JNICALL
Java_com_ezfiles_reader_platform_LlamaBridge_systemInfo(
    JNIEnv *env, jobject /* this */) {
    return env->NewStringUTF(llama_print_system_info());
}

} // extern "C"
