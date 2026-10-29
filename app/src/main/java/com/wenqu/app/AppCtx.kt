package com.wenqu.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** 全局上下文与偏好设置 */
object Prefs {
    private lateinit var sp: SharedPreferences
    private val failCount = HashMap<String, Int>()
    private val cooldown = HashMap<String, Long>()

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("wenqu_prefs", Context.MODE_PRIVATE)
    }

    var voiceEnabled: Boolean
        get() = sp.getBoolean("voice", false)
        set(v) = sp.edit().putBoolean("voice", v).apply()

    var deepDefault: Boolean
        get() = sp.getBoolean("deep", false)
        set(v) = sp.edit().putBoolean("deep", v).apply()

    /** 0跟随系统 1浅色 2深色 */
    var themeMode: Int
        get() = sp.getInt("theme", 0)
        set(v) = sp.edit().putInt("theme", v).apply()

    /** 图片自动附加（回答涉及人物/地点时顺带配图） */
    var autoImages: Boolean
        get() = sp.getBoolean("autoimg", true)
        set(v) = sp.edit().putBoolean("autoimg", v).apply()

    /** 本地大模型总结回答（默认开；关闭后回答走内置规则合成） */
    var llmEnabled: Boolean
        get() = sp.getBoolean("llm", true)
        set(v) = sp.edit().putBoolean("llm", v).apply()

    fun enabledEngineIds(): MutableSet<String> {
        val all = Engines.allIds().toSet()
        return LinkedHashSet(sp.getStringSet("engines", all) ?: all)
    }

    fun setEngineEnabled(id: String, on: Boolean) {
        val s = enabledEngineIds()
        if (on) s.add(id) else s.remove(id)
        sp.edit().putStringSet("engines", s).apply()
    }

    /** 自定义引擎 [{n:名称,u:模板,模板里用{q}占位}] */
    fun customEngines(): MutableList<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        try {
            val arr = JSONArray(sp.getString("customs", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(o.optString("n") to o.optString("u"))
            }
        } catch (_: Exception) {}
        return out
    }

    fun saveCustoms(list: List<Pair<String, String>>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("n", it.first).put("u", it.second)) }
        sp.edit().putString("customs", arr.toString()).apply()
    }

    // ---- 引擎健康度（内存） ----
    fun recordFail(id: String) {
        val f = (failCount[id] ?: 0) + 1
        failCount[id] = f
        if (f >= 3) {
            cooldown[id] = System.currentTimeMillis() + 10 * 60 * 1000L
            failCount[id] = 0
        }
    }

    fun recordOk(id: String) {
        failCount[id] = 0
        cooldown.remove(id)
    }

    fun cooldownUntil(id: String): Long = cooldown[id] ?: 0L

    fun healthNote(id: String): String {
        val c = cooldown[id] ?: 0L
        return if (c > System.currentTimeMillis()) "临时限流冷却中"
        else {
            val f = failCount[id] ?: 0
            if (f > 0) "连续失败 $f 次" else "正常"
        }
    }
}

/** 应用上下文持有 */
object AppCtx {
    lateinit var appContext: Context
        private set
    fun init(ctx: Context) { appContext = ctx.applicationContext }
}
