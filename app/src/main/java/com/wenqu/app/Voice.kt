package com.wenqu.app

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** 语音回答：系统 TTS，可在设置里开关；每条回答也有播放按钮 */
class Voice(private val ctx: Context) {
    private var tts: TextToSpeech? = null
    private var ready = false

    init {
        tts = TextToSpeech(ctx) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                try {
                    tts?.language = Locale.CHINA
                    tts?.setSpeechRate(1.05f)
                } catch (_: Exception) {}
            }
        }
    }

    private fun clean(t: String): String = t
        .replace(Nlp.urlRegex, " 链接 ")
        .replace(Regex("[·\\[\\]［］#*`_｜|]"), " ")
        .replace(Regex("\\s+"), " ")

    fun say(text: String) {
        if (!Prefs.voiceEnabled || !ready) return
        speakNow(text)
    }

    fun speakNow(text: String) {
        if (!ready) return
        try {
            tts?.stop()
            tts?.speak(clean(text), TextToSpeech.QUEUE_FLUSH, null, "wenqu_${System.currentTimeMillis()}")
        } catch (_: Exception) {}
    }

    fun stop() {
        try { tts?.stop() } catch (_: Exception) {}
    }

    fun shutdown() {
        try { tts?.shutdown() } catch (_: Exception) {}
    }
}
