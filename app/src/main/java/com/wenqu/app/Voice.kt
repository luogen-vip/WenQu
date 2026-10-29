package com.wenqu.app

import android.content.Context
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/**
 * 语音回答：系统 TTS，可在设置里开关；每条回答也有播放/停止按钮。
 * speaking 状态实时可感知：播放中点按钮 = 停止（而不是从头再播）。
 */
class Voice(private val ctx: Context) {
    private var tts: TextToSpeech? = null
    private var ready = false

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking

    init {
        tts = TextToSpeech(ctx) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                try {
                    tts?.language = Locale.SIMPLIFIED_CHINESE
                    pickBestVoice()
                    tts?.setSpeechRate(1.0f)
                    tts?.setPitch(1.0f)
                    tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) { _speaking.value = true }
                        override fun onDone(utteranceId: String?) { _speaking.value = false }
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) { _speaking.value = false }
                        override fun onError(utteranceId: String?, errorCode: Int) { _speaking.value = false }
                        override fun onStop(utteranceId: String?, interrupted: Boolean) { _speaking.value = false }
                    })
                } catch (_: Exception) {}
            }
        }
    }

    /** 在系统已装的中文语音里挑音质最好的（Google 引擎优先，无需联网的优先） */
    private fun pickBestVoice() {
        try {
            val all = tts?.voices ?: return
            val zh = all.filter { v ->
                val l = v.locale ?: return@filter false
                l.language.equals("zh", true) || v.name.contains("zh", true) ||
                        v.name.contains("chi", true) || v.name.contains("cmn", true)
            }
            if (zh.isEmpty()) return
            val best = zh.filter { !it.isNetworkConnectionRequired }
                .maxByOrNull { v ->
                    (if (v.name.contains("google", true)) 4 else 0) +
                            (if (v.name.contains("cn", true) || v.name.contains("CHN", true)) 2 else 0) +
                            (if (v.name.contains("local", true)) 1 else 0)
                }
            best?.let { tts?.voice = it }
        } catch (_: Exception) {}
    }

    private fun clean(t: String): String = t
        .replace(Nlp.urlRegex, " 链接 ")
        .replace(Regex("[·\\[\\]［］#*`_｜|]"), " ")
        .replace(Regex("\\s+"), " ")

    /** 自动朗读：跟随设置开关 */
    fun say(text: String) {
        if (!Prefs.voiceEnabled || !ready) return
        speakNow(text)
    }

    fun speakNow(text: String) {
        if (!ready) return
        try {
            _speaking.value = false
            tts?.stop()
            tts?.speak(clean(text), TextToSpeech.QUEUE_FLUSH, null, "wenqu_${System.currentTimeMillis()}")
        } catch (_: Exception) {}
    }

    /** 播放中点击 = 停止；未播放 = 开始朗读 */
    fun toggle(text: String) {
        if (_speaking.value) stop() else speakNow(text)
    }

    fun stop() {
        try { tts?.stop() } catch (_: Exception) {}
        _speaking.value = false
    }

    fun shutdown() {
        try { tts?.shutdown() } catch (_: Exception) {}
        _speaking.value = false
    }
}
