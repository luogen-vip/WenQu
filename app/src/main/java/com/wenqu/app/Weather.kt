package com.wenqu.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** 天气问答：OpenStreetMap Nominatim 地理编码 + Open-Meteo 开源气象接口，全程无需密钥 */
object Weather {

    private val CODES = mapOf(
        0 to "晴", 1 to "大致晴朗", 2 to "多云", 3 to "阴",
        45 to "有雾", 48 to "雾凇",
        51 to "毛毛雨", 53 to "毛毛雨", 55 to "较密毛毛雨",
        56 to "冻毛毛雨", 57 to "冻毛毛雨",
        61 to "小雨", 63 to "中雨", 65 to "大雨",
        66 to "冻雨", 67 to "冻雨",
        71 to "小雪", 73 to "中雪", 75 to "大雪", 77 to "雪粒",
        80 to "阵雨", 81 to "阵雨", 82 to "强阵雨",
        85 to "阵雪", 86 to "阵雪",
        95 to "雷阵雨", 96 to "雷阵雨伴冰雹", 99 to "强雷雨伴冰雹"
    )

    private val DIRS = arrayOf("北风", "东北风", "东风", "东南风", "南风", "西南风", "西风", "西北风")

    private fun codeDesc(c: Int): String = CODES[c] ?: "天气多变"
    private fun windDir(d: Double): String = DIRS[((d % 360 + 360) % 360 / 45 + 0.5).toInt() % 8]

    /** 意图路由用：是否天气类问题（非 suspend，只做判定） */
    fun looksLike(raw: String): Boolean {
        val q = raw.trim()
        if (q.length > 30) return false
        if (!Regex("(天气|气温|温度|下雨|降雨|下雪|冷不冷|热不热|多少度|几度|穿什么)").containsMatchIn(q)) return false
        if (Regex("(什么是天气|天气系统|天气预报怎么|气象学|天气API|天气是如何|天气的形成)").containsMatchIn(q)) return false
        return true
    }

    suspend fun tryAnswer(raw: String): String? = withContext(Dispatchers.IO) {
        val q = raw.trim()
        if (!Regex("(天气|气温|温度|下雨|降雨|下雪|冷不冷|热不热|多少度|几度)").containsMatchIn(q)) return@withContext null
        if (Regex("(什么是天气|天气系统|天气预报怎么|气象学|天气API)").containsMatchIn(q)) return@withContext null

        var place = q
        for (w in listOf("请问", "帮我", "查一下", "查查", "看看", "现在", "当前", "今天", "明天", "后天", "大后天",
                "会不会", "适合", "怎么样", "怎样", "如何", "的", "天气", "气温", "温度", "下雨", "降雨", "下雪",
                "冷不冷", "热不热", "多少度", "几度", "出门", "穿什么", "吗", "呢", "啊", "呀", "告诉", "我要知道")) {
            place = place.replace(w, "")
        }
        place = place.trim().trimEnd('。', '.', '？', '?', '，', ',')
        if (place.isEmpty() || place.length > 16) return@withContext null

        // 地名多级降级尝试：「云南省保山市隆阳区」→ 全名 → 去行政后缀 → 末级 → 上级市/省
        val candidates = buildList {
            add(place)
            val stripped = place.replace("自治州", "").replace("自治区", "").replace("省", "").replace("市", "").replace("区", "").replace("县", "")
            if (stripped.length >= 2 && stripped != place) add(stripped)
            if (place.contains("市")) add(place.substringAfterLast("市").ifEmpty { place.substringBeforeLast("市") })
            if (place.contains("省")) add(place.substringAfterLast("省"))
            if (place.length >= 3) add(place.take(3))
            if (place.length >= 2) add(place.take(2))
        }.map { it.trim() }.filter { it.length in 2..16 }.distinct()

        // 地理编码用 Open-Meteo 自带服务（与气象接口同域，国内可达性一致）；逐个候选尝试直到命中
        var lat = ""
        var lon = ""
        var display = ""
        for (cand in candidates) {
            val geo = Http.get(
                "https://geocoding-api.open-meteo.com/v1/search?name=${Nlp.enc(cand)}&count=1&language=zh&format=json",
                callTimeoutSec = 9
            ) ?: continue
            val arr = try { JSONArray(geo) } catch (e: Exception) { continue }
            if (arr.length() == 0) continue
            val g = arr.getJSONObject(0)
            val la = g.optString("latitude")
            val lo = g.optString("longitude")
            if (la.isEmpty() || lo.isEmpty()) continue
            lat = la; lon = lo
            val admin1 = g.optString("admin1")
            display = g.optString("name") + if (admin1.isEmpty() || admin1 == g.optString("name")) "" else "（$admin1）"
            break
        }
        if (lat.isEmpty() || lon.isEmpty()) return@withContext null

        val w = Http.get(
            "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m,wind_direction_10m" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                    "&timezone=auto&forecast_days=3"
        ) ?: return@withContext null

        try {
            val o = JSONObject(w)
            val cur = o.getJSONObject("current")
            val daily = o.getJSONObject("daily")
            val t = cur.getDouble("temperature_2m")
            val feels = cur.getDouble("apparent_temperature")
            val hum = cur.getDouble("relative_humidity_2m")
            val code = cur.getInt("weather_code")
            val ws = cur.getDouble("wind_speed_10m")
            val wd = cur.getDouble("wind_direction_10m")

            val sb = StringBuilder()
            sb.append("「").append(display).append("」现在的天气：\n\n")
            sb.append(codeDesc(code)).append("，").append(String.format("%.1f", t)).append("°C")
            sb.append("（体感 ").append(String.format("%.1f", feels)).append("°C）\n")
            sb.append(windDir(wd)).append(" ").append(String.format("%.0f", ws)).append(" km/h · 湿度 ")
                .append(String.format("%.0f", hum)).append("%\n\n")

            val dates = daily.optJSONArray("time")
            val maxs = daily.optJSONArray("temperature_2m_max")
            val mins = daily.optJSONArray("temperature_2m_min")
            val codes = daily.optJSONArray("weather_code")
            val probs = daily.optJSONArray("precipitation_probability_max")

            if (dates != null && maxs != null && mins != null) {
                val tomorrow = Regex("明天").containsMatchIn(q)
                val labels = listOf("今天", "明天", "后天")
                val startIdx = if (tomorrow && dates.length() > 1) 1 else 0
                val n = minOf(2, dates.length() - startIdx)
                for (i in 0 until n) {
                    val idx = startIdx + i
                    if (i == 0) sb.append(labels[idx]).append("：")
                    else sb.append(labels.getOrNull(idx) ?: "").append("：")
                    sb.append(String.format("%.0f", mins.getDouble(idx))).append("~")
                        .append(String.format("%.0f", maxs.getDouble(idx))).append("°C")
                    if (codes != null) sb.append("，").append(codeDesc(codes.getInt(idx)))
                    if (probs != null) sb.append("，降水概率 ").append(probs.optInt(idx)).append("%")
                    sb.append("\n")
                }
            }
            sb.append("\n数据来自 Open-Meteo（开源气象接口），实时准确。要不要我顺便找找当地的实时资讯？")
            sb.toString()
        } catch (e: Exception) {
            null
        }
    }
}
