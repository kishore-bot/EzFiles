#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include "llama.h"
#include "common.h"

#define TAG "EzLlama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static llama_model * model = nullptr;

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_ezfiles_reader_platform_LlamaBridge_loadModel(
    JNIEnv *env, jobject /* this */, jstring path, jboolean useGpu) {

    const char *model_path = env->GetStringUTFChars(path, nullptr);
    LOGI("Loading model: %s (GPU=%d)", model_path, useGpu);

    llama_backend_init();

    auto params = llama_model_default_params();
    if (useGpu) {
        params.n_gpu_layers = 99;
    } else {
        params.n_gpu_layers = 0;
    }

    model = llama_model_load_from_file(model_path, params);
    env->ReleaseStringUTFChars(path, model_path);

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

    auto *mdl = reinterpret_cast<llama_model *>(modelPtr);
    if (mdl == nullptr) {
        return env->NewStringUTF("{\"kind\":\"reply\",\"text\":\"Model not loaded.\"}");
    }

    const char *prompt_cstr = env->GetStringUTFChars(prompt, nullptr);
    std::string prompt_str(prompt_cstr);
    env->ReleaseStringUTFChars(prompt, prompt_cstr);

    auto ctx_params = llama_context_default_params();
    ctx_params.n_ctx = 2048;
    ctx_params.n_batch = 512;

    llama_context *ctx = llama_init_from_model(mdl, ctx_params);
    if (ctx == nullptr) {
        LOGE("Failed to create context");
        return env->NewStringUTF("{\"kind\":\"reply\",\"text\":\"Context creation failed.\"}");
    }

    auto vocab = llama_model_get_vocab(mdl);

    // Tokenize prompt
    int n_prompt_max = prompt_str.size() + 256;
    std::vector<llama_token> tokens(n_prompt_max);
    int n_tokens = llama_tokenize(vocab, prompt_str.c_str(), prompt_str.size(),
                                   tokens.data(), n_prompt_max, true, true);
    if (n_tokens < 0) {
        tokens.resize(-n_tokens);
        n_tokens = llama_tokenize(vocab, prompt_str.c_str(), prompt_str.size(),
                                   tokens.data(), -n_tokens, true, true);
    }
    tokens.resize(n_tokens);

    LOGI("Prompt tokens: %d", n_tokens);

    // Create batch and evaluate prompt
    llama_batch batch = llama_batch_init(n_tokens, 0, 1);
    for (int i = 0; i < n_tokens; i++) {
        llama_batch_add(batch, tokens[i], i, { 0 }, false);
    }
    batch.logits[batch.n_tokens - 1] = true;

    if (llama_decode(ctx, batch) != 0) {
        LOGE("Decode failed");
        llama_batch_free(batch);
        llama_free(ctx);
        return env->NewStringUTF("{\"kind\":\"reply\",\"text\":\"Decode failed.\"}");
    }

    // Generate tokens
    std::string result;
    int max = maxTokens > 0 ? maxTokens : 512;
    llama_token eos = llama_vocab_eos(vocab);

    for (int i = 0; i < max; i++) {
        auto *logits = llama_get_logits_ith(ctx, batch.n_tokens - 1);
        int n_vocab_size = llama_vocab_n_tokens(vocab);

        // Greedy sampling
        llama_token best = 0;
        float best_logit = logits[0];
        for (int v = 1; v < n_vocab_size; v++) {
            if (logits[v] > best_logit) {
                best_logit = logits[v];
                best = v;
            }
        }

        if (best == eos) break;

        char buf[128];
        int len = llama_token_to_piece(vocab, best, buf, sizeof(buf), 0, true);
        if (len > 0) {
            result.append(buf, len);
        }

        // Check for end of JSON object
        if (!result.empty()) {
            int brace_count = 0;
            bool in_string = false;
            bool found_complete = false;
            for (size_t j = 0; j < result.size(); j++) {
                char c = result[j];
                if (c == '"' && (j == 0 || result[j-1] != '\\')) in_string = !in_string;
                if (!in_string) {
                    if (c == '{') brace_count++;
                    if (c == '}') { brace_count--; if (brace_count == 0) { found_complete = true; result = result.substr(0, j+1); break; } }
                }
            }
            if (found_complete) break;
        }

        // Next token
        llama_batch_clear(batch);
        llama_batch_add(batch, best, n_tokens + i, { 0 }, true);
        if (llama_decode(ctx, batch) != 0) {
            LOGE("Decode step %d failed", i);
            break;
        }
    }

    llama_batch_free(batch);
    llama_free(ctx);

    LOGI("Generated: %s", result.c_str());
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT void JNICALL
Java_com_ezfiles_reader_platform_LlamaBridge_freeModel(
    JNIEnv *env, jobject /* this */, jlong modelPtr) {

    auto *mdl = reinterpret_cast<llama_model *>(modelPtr);
    if (mdl != nullptr) {
        llama_model_free(mdl);
        LOGI("Model freed");
    }
    llama_backend_free();
}

JNIEXPORT jstring JNICALL
Java_com_ezfiles_reader_platform_LlamaBridge_systemInfo(
    JNIEnv *env, jobject /* this */) {
    return env->NewStringUTF(llama_print_system_info());
}

} // extern "C"
