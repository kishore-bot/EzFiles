#include <jni.h>
#include <android/log.h>
#include <exception>
#include <string>
#include <vector>
#include "llama.h"

#define TAG "EzLlama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static llama_model * model = nullptr;
static bool model_on_gpu = false;
static std::string model_path_str;
// Set once the GPU fails; every later call runs on this CPU copy instead.
static llama_model * cpu_model = nullptr;

static llama_model * load(const std::string & path, bool gpu) {
    auto params = llama_model_default_params();
    params.n_gpu_layers = gpu ? 99 : 0;
    // An empty device list keeps every tensor in host memory; n_gpu_layers = 0 alone still uses the GPU.
    static ggml_backend_dev_t no_devices[] = { nullptr };
    if (!gpu) params.devices = no_devices;
    return llama_model_load_from_file(path.c_str(), params);
}

static void add_token(llama_batch & batch, llama_token token, int pos, bool logits) {
    batch.token[batch.n_tokens] = token;
    batch.pos[batch.n_tokens] = pos;
    batch.n_seq_id[batch.n_tokens] = 1;
    batch.seq_id[batch.n_tokens][0] = 0;
    batch.logits[batch.n_tokens] = logits;
    batch.n_tokens++;
}

// Throws whatever the backend throws (e.g. vk::DeviceLostError); the caller decides to fall back.
static std::string generate(llama_model * mdl, const std::string & prompt, int max, bool cpu_only) {
    auto ctx_params = llama_context_default_params();
    // Otherwise large batches are still sent to the GPU even when no layers live there.
    ctx_params.op_offload = !cpu_only;
    // The training-format prompt (policy + tool schemas + state + a tool result) runs to ~1200 tokens.
    ctx_params.n_ctx = 2048;
    ctx_params.n_batch = 2048;
    // Small GPU submissions: one large prompt pass trips the Adreno watchdog (DeviceLost).
    ctx_params.n_ubatch = 64;
    ctx_params.n_threads = 4;
    ctx_params.n_threads_batch = 4;

    llama_context * ctx = llama_init_from_model(mdl, ctx_params);
    if (ctx == nullptr) {
        LOGE("Failed to create context");
        return "{\"kind\":\"reply\",\"text\":\"Context creation failed.\"}";
    }

    auto vocab = llama_model_get_vocab(mdl);

    int n_prompt_max = prompt.size() + 256;
    std::vector<llama_token> tokens(n_prompt_max);
    int n_tokens = llama_tokenize(vocab, prompt.c_str(), prompt.size(), tokens.data(), n_prompt_max, true, true);
    if (n_tokens < 0) {
        tokens.resize(-n_tokens);
        n_tokens = llama_tokenize(vocab, prompt.c_str(), prompt.size(), tokens.data(), -n_tokens, true, true);
    }
    tokens.resize(n_tokens);
    LOGI("Prompt tokens: %d", n_tokens);

    llama_batch batch = llama_batch_init(n_tokens, 0, 1);
    for (int i = 0; i < n_tokens; i++) add_token(batch, tokens[i], i, i == n_tokens - 1);

    if (llama_decode(ctx, batch) != 0) {
        LOGE("Decode failed");
        llama_batch_free(batch);
        llama_free(ctx);
        return "{\"kind\":\"reply\",\"text\":\"Decode failed.\"}";
    }

    std::string result;
    llama_token eos = llama_vocab_eos(vocab);
    int n_vocab_size = llama_vocab_n_tokens(vocab);

    for (int i = 0; i < max; i++) {
        auto * logits = llama_get_logits_ith(ctx, batch.n_tokens - 1);

        llama_token best = 0;
        float best_logit = logits[0];
        for (int v = 1; v < n_vocab_size; v++) {
            if (logits[v] > best_logit) { best_logit = logits[v]; best = v; }
        }
        if (best == eos) break;

        char buf[128];
        int len = llama_token_to_piece(vocab, best, buf, sizeof(buf), 0, true);
        if (len > 0) result.append(buf, len);

        // Stop at the end of the first complete JSON object.
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

        batch.n_tokens = 0;
        add_token(batch, best, n_tokens + i, true);
        if (llama_decode(ctx, batch) != 0) {
            LOGE("Decode step %d failed", i);
            break;
        }
    }

    llama_batch_free(batch);
    llama_free(ctx);
    LOGI("Generated: %s", result.c_str());
    return result;
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_ezfiles_reader_platform_LlamaBridge_loadModel(
    JNIEnv *env, jobject /* this */, jstring path, jboolean useGpu) {

    const char *model_path = env->GetStringUTFChars(path, nullptr);
    model_path_str = model_path;
    env->ReleaseStringUTFChars(path, model_path);
    LOGI("Loading model: %s (GPU=%d)", model_path_str.c_str(), useGpu);

    llama_backend_init();
    model = load(model_path_str, useGpu);
    model_on_gpu = useGpu;
    if (model == nullptr) {
        LOGE("Failed to load model");
        return 0;
    }
    LOGI("Model loaded successfully");
    return reinterpret_cast<jlong>(model);
}

JNIEXPORT jstring JNICALL
Java_com_ezfiles_reader_platform_LlamaBridge_runInference(
    JNIEnv *env, jobject /* this */, jlong modelPtr, jstring prompt, jint maxTokens) {

    auto *mdl = cpu_model != nullptr ? cpu_model : reinterpret_cast<llama_model *>(modelPtr);
    if (mdl == nullptr) {
        return env->NewStringUTF("{\"kind\":\"reply\",\"text\":\"Model not loaded.\"}");
    }

    const char *prompt_cstr = env->GetStringUTFChars(prompt, nullptr);
    std::string prompt_str(prompt_cstr);
    env->ReleaseStringUTFChars(prompt, prompt_cstr);
    int max = maxTokens > 0 ? maxTokens : 512;

    try {
        return env->NewStringUTF(generate(mdl, prompt_str, max, cpu_model != nullptr || !model_on_gpu).c_str());
    } catch (const std::exception & e) {
        LOGE("Inference threw: %s", e.what());
    } catch (...) {
        LOGE("Inference threw an unknown error");
    }

    if (cpu_model != nullptr) {
        return env->NewStringUTF("{\"kind\":\"reply\",\"text\":\"Something went wrong. Try again.\"}");
    }

    // The GPU context is unusable after a device loss; the GPU model is left unfreed rather than
    // risk another throw while tearing it down.
    LOGI("GPU failed, falling back to CPU");
    cpu_model = load(model_path_str, false);
    if (cpu_model == nullptr) {
        return env->NewStringUTF("{\"kind\":\"reply\",\"text\":\"Model failed to reload.\"}");
    }
    try {
        return env->NewStringUTF(generate(cpu_model, prompt_str, max, true).c_str());
    } catch (const std::exception & e) {
        LOGE("CPU inference threw: %s", e.what());
        return env->NewStringUTF("{\"kind\":\"reply\",\"text\":\"Something went wrong. Try again.\"}");
    }
}

JNIEXPORT void JNICALL
Java_com_ezfiles_reader_platform_LlamaBridge_freeModel(
    JNIEnv *env, jobject /* this */, jlong modelPtr) {

    auto *mdl = reinterpret_cast<llama_model *>(modelPtr);
    if (mdl != nullptr) llama_model_free(mdl);
    if (cpu_model != nullptr) { llama_model_free(cpu_model); cpu_model = nullptr; }
    model = nullptr;
    LOGI("Model freed");
    llama_backend_free();
}

JNIEXPORT jstring JNICALL
Java_com_ezfiles_reader_platform_LlamaBridge_systemInfo(
    JNIEnv *env, jobject /* this */) {
    return env->NewStringUTF(llama_print_system_info());
}

} // extern "C"
