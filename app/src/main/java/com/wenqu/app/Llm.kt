package com.wenqu.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 本地大模型管理器：Qwen2.5-0.5B-Instruct（Q6_K），随 APK 内置。
 * 模型文件伪装成 libmodel.so 放进 jniLibs（安装后位于 nativeLibraryDir，可直接 mmap）。
 * 所有回答由它结合搜索材料实时生成——不走固定模板；加载失败自动回退到规则合成。
 */
object Llm {

    sealed class St {
        object Init : St()
        object Loading : St()
        object Ready : St()
        data class Failed(val reason: String) : St()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<St>(St.Init)
    val state: StateFlow<St> = _state

    val ready: Boolean get() = _state.value is St.Ready

    private val jni: LlmJni? = LlmJni.obtain()
    private var handle = 0L
    private val mutex = Mutex()

    private const val N_CTX = 2048
    private const val MODEL_LIB = "libmodel.so"

    /** App 启动时后台加载（不阻塞 UI）；成功后做一次预热生成拉起 mmap 页 */
    fun boot() {
        if (jni == null) {
            _state.value = St.Failed("推理库不可用（不支持的机型）")
            return
        }
        if (_state.value !is St.Init) return
        _state.value = St.Loading
        scope.launch {
            try {
                val dir = AppCtx.appContext.applicationInfo.nativeLibraryDir
                val f = File(dir, MODEL_LIB)
                if (!f.exists() || f.length() < 10 * 1024 * 1024) {
                    _state.value = St.Failed("模型文件缺失")
                    return@launch
                }
                val cores = Runtime.getRuntime().availableProcessors()
                // 保守线程数：低端机 6 线程易过热降频反而更慢，2~4 是甜点位
                val threads = (cores - 1).coerceIn(2, 4)
                val h = withContext(Dispatchers.IO) { jni.nativeLoad(f.absolutePath, N_CTX, threads) }
                if (h == 0L) {
                    _state.value = St.Failed("模型加载失败（内存不足或文件损坏）")
                    return@launch
                }
                handle = h
                _state.value = St.Ready
                // 预热：极短生成，把 mmap 页拉进物理内存，避免首答卡顿
                runCatching {
                    withTimeout(30_000L) { jni.nativeComplete(h, chatPrompt("你好", "回复：好的"), 6, 0.6f) }
                }
            } catch (t: Throwable) {
                _state.value = St.Failed(t.message ?: "未知错误")
            }
        }
    }

    private fun chatPrompt(user: String, sys: String): String = buildString {
        append("<|im_start|>system\n").append(sys).append("<|im_end|>\n")
        append("<|im_start|>user\n").append(user).append("<|im_end|>\n")
        append("<|im_start|>assistant\n")
    }

    /**
     * 流式生成。onDelta 回调增量后的全量文本（可在任意线程）。
     * 返回完整回答；模型未就绪/生成失败返回 null（调用方回退规则合成）。
     */
    suspend fun complete(
        system: String,
        user: String,
        maxTokens: Int = 320,
        temp: Float = 0.72f,
        onDelta: (String) -> Unit = {}
    ): String? = mutex.withLock {
        val j = jni ?: return@withLock null
        if (_state.value !is St.Ready || handle == 0L) return@withLock null
        val sb = StringBuilder()
        try {
            val ok = withTimeout(120_000L) {
                withContext(Dispatchers.IO) {
                    j.onToken = { b ->
                        sb.append(String(b, Charsets.UTF_8))
                        onDelta(sb.toString())
                    }
                    try { j.nativeComplete(handle, chatPrompt(user, system), maxTokens, temp) } catch (t: Throwable) { false }
                }
            }
            if (!ok) return@withLock null
        } catch (t: Throwable) {
            j.onToken = null
            return@withLock null
        }
        j.onToken = null
        val text = sb.toString().trim()
        if (text.length < 2) null else text
    }
}
