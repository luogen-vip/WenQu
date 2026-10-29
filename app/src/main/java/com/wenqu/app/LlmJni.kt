package com.wenqu.app

/**
 * llama.cpp JNI 桥（库：libwenqu_llm.so）。
 * onTokenCb 由 native 层在推理线程回调，回传增量 UTF-8 字节片段。
 */
class LlmJni {

    var onToken: ((ByteArray) -> Unit)? = null

    /** native 回调入口（保持方法名与 llama-android.cpp 一致） */
    fun onTokenCb(bytes: ByteArray) {
        try { onToken?.invoke(bytes) } catch (_: Exception) {}
    }

    /** 加载模型；返回句柄，0 = 失败 */
    external fun nativeLoad(path: String, nCtx: Int, nThreads: Int): Long

    external fun nativeFree(handle: Long)

    /** 流式补全；false = 出错（上层回退规则合成） */
    external fun nativeComplete(handle: Long, prompt: String, maxTokens: Int, temp: Float): Boolean

    companion object {
        private var loaded = false

        @Synchronized
        fun obtain(): LlmJni? {
            return try {
                if (!loaded) {
                    System.loadLibrary("wenqu_llm")
                    loaded = true
                }
                LlmJni()
            } catch (t: Throwable) {
                null
            }
        }
    }
}
