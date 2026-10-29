#include <jni.h>
#include <string>
#include <vector>
#include <algorithm>
#include "llama.h"

/**
 * 问渠本地大模型 JNI 层（llama.cpp）
 * 模型：Qwen2.5-0.5B-Instruct（Q6_K 量化，以 libmodel.so 形式随 APK 分发）
 * 纯 CPU 推理，无 GPU 依赖；complete() 流式回调 UTF-8 字节片段。
 */

struct WqHandle {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
};

extern "C" JNIEXPORT jlong JNICALL
Java_com_wenqu_app_LlmJni_nativeLoad(JNIEnv* env, jobject, jstring jpath, jint n_ctx, jint n_threads) {
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    if (!path) return 0;
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;  // 纯 CPU，兼容所有机型
    llama_model* model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(jpath, path);
    if (!model) return 0;

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = (uint32_t) n_ctx;
    cp.n_threads = n_threads;
    cp.n_threads_batch = n_threads;
    cp.no_perf = true;
    llama_context* ctx = llama_init_from_model(model, cp);
    if (!ctx) {
        llama_model_free(model);
        return 0;
    }
    auto* h = new WqHandle();
    h->model = model;
    h->ctx = ctx;
    return (jlong) h;
}

extern "C" JNIEXPORT void JNICALL
Java_com_wenqu_app_LlmJni_nativeFree(JNIEnv*, jobject, jlong handle) {
    auto* h = (WqHandle*) handle;
    if (!h) return;
    if (h->ctx) llama_free(h->ctx);
    if (h->model) llama_model_free(h->model);
    delete h;
}

static std::vector<llama_token> tokenizeText(const llama_vocab* vocab, const std::string& text, bool addBos) {
    std::vector<llama_token> out(text.size() + (addBos ? 8 : 0));
    int n = llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), out.data(), (int32_t) out.size(), addBos, false);
    if (n < 0) {
        out.resize((size_t)(-n));
        n = llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), out.data(), (int32_t) out.size(), addBos, false);
    }
    if (n < 0) out.clear(); else out.resize((size_t) n);
    return out;
}

/**
 * 流式补全：prompt 按 ChatML 拼好传入；每生成一个 token 通过 callback.onToken(byte[]) 回传原始 UTF-8 字节。
 * 返回 true=正常完成；false=出错（调用方应回退到规则合成）。
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_wenqu_app_LlmJni_nativeComplete(JNIEnv* env, jobject, jlong handle, jstring jprompt, jint max_tokens, jfloat temp, jobject callback) {
    auto* h = (WqHandle*) handle;
    if (!h || !h->model || !h->ctx) return JNI_FALSE;

    const char* prompt = env->GetStringUTFChars(jprompt, nullptr);
    if (!prompt) return JNI_FALSE;
    std::string ptext(prompt);
    env->ReleaseStringUTFChars(jprompt, prompt);
    if (ptext.empty()) return JNI_FALSE;

    jclass cbClass = env->GetObjectClass(callback);
    if (!cbClass) return JNI_FALSE;
    jmethodID onTok = env->GetMethodID(cbClass, "onToken", "([B)V");
    if (!onTok) return JNI_FALSE;

    const llama_model* model = h->model;
    const llama_vocab* vocab = llama_model_get_vocab(h->model);
    llama_context* ctx = h->ctx;
    llama_kv_cache_clear(ctx);

    std::vector<llama_token> toks = tokenizeText(vocab, ptext, true);
    if (toks.empty() || (int) toks.size() > (int) llama_n_ctx(ctx) - 8) return JNI_FALSE;

    llama_batch batch = llama_batch_get_one(toks.data(), (int) toks.size());
    if (llama_decode(ctx, batch) != 0) return JNI_FALSE;

    llama_sampler* smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(temp > 0.01f ? 0.9f : 1.0f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(-1));

    char buf[512];
    int n_gen = 0;
    jboolean ok = JNI_TRUE;
    while (n_gen < max_tokens) {
        llama_token tok = llama_sampler_sample(smpl, ctx, -1);
        if (llama_token_is_eog(vocab, tok)) break;
        int n = llama_token_to_piece(vocab, tok, buf, sizeof(buf) - 8, 0, true);
        if (n > 0) {
            jbyteArray arr = env->NewByteArray(n);
            if (arr) {
                env->SetByteArrayRegion(arr, 0, n, (const jbyte*) buf);
                env->CallVoidMethod(callback, onTok, arr);
                env->DeleteLocalRef(arr);
            }
        }
        llama_batch nb = llama_batch_get_one(&tok, 1);
        if (llama_decode(ctx, nb) != 0) { ok = JNI_FALSE; break; }
        n_gen++;
    }
    llama_sampler_free(smpl);
    return ok;
}
