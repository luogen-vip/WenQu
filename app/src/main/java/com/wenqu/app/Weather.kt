package com.wenqu.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 天气问答：内置城市坐标表（离线命中，不依赖第三方地理编码）+ Open-Meteo 开源气象接口，全程无需密钥。
 * 问"今天天气怎么样"没说地点时，由上层反问澄清（extractPlace 返回空）。
 */
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

    /** 内置中国城市坐标（lat to lon）：本地命中直接查气象，不再依赖地理编码接口 */
    private val CITIES = mapOf(
        "北京" to (39.9042 to 116.4074), "上海" to (31.2304 to 121.4737), "广州" to (23.1291 to 113.2644),
        "深圳" to (22.5431 to 114.0579), "天津" to (39.3434 to 117.3616), "重庆" to (29.5630 to 106.5516),
        "成都" to (30.5728 to 104.0668), "杭州" to (30.2741 to 120.1551), "武汉" to (30.5928 to 114.3055),
        "西安" to (34.3416 to 108.9398), "南京" to (32.0603 to 118.7969), "苏州" to (31.2989 to 120.5853),
        "长沙" to (28.2282 to 112.9388), "郑州" to (34.7466 to 113.6254), "青岛" to (36.0671 to 120.3826),
        "大连" to (38.9140 to 121.6147), "厦门" to (24.4798 to 118.0894), "福州" to (26.0745 to 119.2965),
        "合肥" to (31.8206 to 117.2272), "沈阳" to (41.8057 to 123.4315), "哈尔滨" to (45.8038 to 126.5350),
        "济南" to (36.6512 to 117.1201), "太原" to (37.8706 to 112.5489), "南昌" to (28.6820 to 115.8579),
        "昆明" to (24.8801 to 102.8329), "贵阳" to (26.6470 to 106.6302), "南宁" to (22.8170 to 108.3665),
        "桂林" to (25.2742 to 110.2902), "柳州" to (24.3255 to 109.4155), "北海" to (21.4811 to 109.1202),
        "海口" to (20.0444 to 110.1999), "三亚" to (18.2528 to 109.5119), "兰州" to (36.0611 to 103.8343),
        "西宁" to (36.6171 to 101.7782), "银川" to (38.4872 to 106.2309), "乌鲁木齐" to (43.8256 to 87.6168),
        "喀什" to (39.4704 to 75.9898), "拉萨" to (29.6520 to 91.1721), "日喀则" to (29.2690 to 88.8800),
        "呼和浩特" to (40.8414 to 111.7519), "包头" to (40.6572 to 109.8403), "鄂尔多斯" to (39.6086 to 109.7811),
        "香港" to (22.3193 to 114.1694), "澳门" to (22.1987 to 113.5439), "台北" to (25.0330 to 121.5654),
        "高雄" to (22.6273 to 120.3014),
        // 云南全省（用户区域重点覆盖）
        "保山" to (25.1120 to 99.1671), "腾冲" to (25.0304 to 98.4941), "施甸" to (24.7232 to 99.1821),
        "龙陵" to (24.5910 to 98.6900), "昌宁" to (24.8263 to 99.6131),
        "大理" to (25.6065 to 100.2676), "丽江" to (26.8721 to 100.2300), "香格里拉" to (27.8259 to 99.7066),
        "曲靖" to (25.4900 to 103.7966), "玉溪" to (24.3518 to 102.5451), "普洱" to (22.8252 to 100.9660),
        "景洪" to (22.0017 to 100.7979), "西双版纳" to (22.0017 to 100.7979), "芒市" to (24.4337 to 98.5850),
        "德宏" to (24.4337 to 98.5850), "瑞丽" to (24.0127 to 97.4978), "临沧" to (23.8770 to 100.0927),
        "昭通" to (27.3400 to 103.7250), "六库" to (25.8522 to 98.8543), "怒江" to (25.8522 to 98.8543),
        "文山" to (23.3862 to 104.2146), "蒙自" to (23.3660 to 103.3797), "红河" to (23.3660 to 103.3797),
        "楚雄" to (25.0419 to 101.5461), "宣威" to (26.2173 to 104.1025), "安宁" to (24.9212 to 102.4794),
        "个旧" to (23.3589 to 103.1583), "开远" to (23.7121 to 103.2570), "建水" to (23.6118 to 102.8265),
        "泸水" to (25.8522 to 98.8543), "弥勒" to (24.4054 to 103.4330), "澄江" to (24.6682 to 102.9201),
        "通海" to (24.1113 to 102.7550), "华宁" to (24.1926 to 102.9287), "易门" to (24.6712 to 102.1611),
        "洱源" to (26.1112 to 99.9507), "剑川" to (26.5287 to 99.9055), "鹤庆" to (26.5573 to 100.1760),
        "云龙" to (25.8854 to 99.3706), "永平" to (25.4643 to 99.5334), "漾濞" to (25.6700 to 99.9570),
        "南涧" to (25.0435 to 100.5180), "巍山" to (25.2272 to 100.3066), "宾川" to (25.8300 to 100.5800),
        "弥渡" to (25.3435 to 100.4909), "祥云" to (25.4797 to 100.5504),
        // 其他主要城市
        "石家庄" to (38.0428 to 114.5149), "唐山" to (39.6304 to 118.1802), "保定" to (38.8740 to 115.4646),
        "秦皇岛" to (39.9354 to 119.5977), "邯郸" to (36.6259 to 114.5391), "廊坊" to (39.5246 to 116.6839),
        "无锡" to (31.4912 to 120.3119), "宁波" to (29.8683 to 121.5440), "温州" to (28.0000 to 120.6720),
        "绍兴" to (30.0300 to 120.5800), "嘉兴" to (30.7465 to 120.7554), "金华" to (29.0787 to 119.6495),
        "台州" to (28.6560 to 121.4208), "常州" to (31.8105 to 119.9741), "南通" to (31.9802 to 120.8943),
        "徐州" to (34.2043 to 117.2857), "扬州" to (32.3947 to 119.4126), "镇江" to (32.1882 to 119.4252),
        "盐城" to (33.3476 to 120.1619), "泰州" to (32.4553 to 119.9230), "连云港" to (34.5969 to 119.2219),
        "淮安" to (33.6104 to 119.0153), "宿迁" to (33.9628 to 118.2757),
        "东莞" to (23.0430 to 113.7633), "佛山" to (23.0219 to 113.1219), "珠海" to (22.2707 to 113.5767),
        "中山" to (22.5159 to 113.3926), "惠州" to (23.1115 to 114.4162), "汕头" to (23.3540 to 116.6820),
        "湛江" to (21.2707 to 110.3594), "揭阳" to (23.5498 to 116.3729), "潮州" to (23.6618 to 116.6226),
        "肇庆" to (23.0470 to 112.4659), "韶关" to (24.8104 to 113.5975),
        "洛阳" to (34.6197 to 112.4540), "开封" to (34.7971 to 114.3076), "南阳" to (32.9908 to 112.5283),
        "新乡" to (35.3020 to 113.9268), "荆州" to (30.3345 to 112.2400), "宜昌" to (30.6921 to 111.2865),
        "襄阳" to (32.0090 to 112.1228), "株洲" to (27.8274 to 113.1340), "湘潭" to (27.8297 to 112.9442),
        "衡阳" to (26.8935 to 112.5722), "岳阳" to (29.3571 to 113.1290), "常德" to (29.0318 to 111.6989),
        "绵阳" to (31.4679 to 104.6860), "德阳" to (31.1270 to 104.3980), "宜宾" to (28.7518 to 104.6418),
        "泸州" to (28.8700 to 105.4425), "南充" to (30.8374 to 106.1107), "乐山" to (29.5520 to 103.7657),
        "自贡" to (29.3393 to 104.7784), "攀枝花" to (26.5823 to 101.7188),
        "芜湖" to (31.3528 to 118.4332), "蚌埠" to (32.9168 to 117.3894), "马鞍山" to (31.6705 to 118.5068),
        "泉州" to (24.8741 to 118.6757), "漳州" to (24.5128 to 117.6471), "莆田" to (25.4542 to 119.0078),
        "赣州" to (25.8309 to 114.9350), "九江" to (29.7051 to 116.9998), "遵义" to (27.7255 to 106.9273),
        "柳州北" to (24.3255 to 109.4155), "吉林" to (43.8378 to 126.5496), "长春" to (43.8171 to 125.3235),
        "齐齐哈尔" to (47.3542 to 123.9180), "大庆" to (46.5907 to 125.1037), "鞍山" to (41.1086 to 122.9956),
        "抚顺" to (41.8669 to 123.9575), "锦州" to (41.0952 to 121.1270),
        // 海外热门
        "纽约" to (40.7128 to -74.0060), "洛杉矶" to (34.0522 to -118.2437), "旧金山" to (37.7749 to -122.4194),
        "伦敦" to (51.5074 to -0.1278), "巴黎" to (48.8566 to 2.3522), "东京" to (35.6762 to 139.6503),
        "首尔" to (37.5665 to 126.9780), "曼谷" to (13.7563 to 100.5018), "新加坡" to (1.3521 to 103.8198),
        "悉尼" to (-33.8688 to 151.2093), "多伦多" to (43.6532 to -79.3832), "莫斯科" to (55.7558 to 37.6173),
        "迪拜" to (25.2048 to 55.2708), "吉隆坡" to (3.1390 to 101.6869), "东京都" to (35.6762 to 139.6503)
    )

    private val ADMIN_SUFFIX = Regex("(市|区|县|省|自治州|自治区|特别行政区)$")

    private fun codeDesc(c: Int): String = CODES[c] ?: "天气多变"
    private fun windDir(d: Double): String = DIRS[((d % 360 + 360) % 360 / 45 + 0.5).toInt() % 8]

    /** 意图路由用：是否天气类问题（非 suspend，只做判定） */
    fun looksLike(raw: String): Boolean {
        val q = raw.trim()
        if (q.length > 30) return false
        if (!Regex("(天气|气温|温度|下雨|降雨|下雪|冷不冷|热不热|多少度|几度|穿什么|降水)").containsMatchIn(q)) return false
        if (Regex("(什么是天气|天气系统|天气预报怎么|气象学|天气API|天气是如何|天气的形成|天气儿歌|天气歌曲|歌|歌词|音乐)").containsMatchIn(q)) return false
        return true
    }

    /** 从天气问句中提取地点；返回空 = 没说地点（上层应反问澄清，而不是乱搜） */
    fun extractPlace(raw: String): String {
        var place = raw.trim()
        for (w in listOf("请问", "帮我", "帮忙", "查一下", "查查", "看看", "现在", "当前", "今天", "明天", "后天", "大后天",
                "会不会", "适合", "怎么样", "怎样", "如何", "的", "天气", "气温", "温度", "下雨", "降雨", "下雪",
                "冷不冷", "热不热", "多少度", "几度", "出门", "穿什么", "吗", "呢", "啊", "呀", "告诉", "我要知道",
                "是多少", "会吗", "不")) {
            place = place.replace(w, "")
        }
        place = place.trim().trimEnd('。', '.', '？', '?', '，', ',', '！', '!')
        if (place.isEmpty() || place.length > 16) return ""
        // 剩余内容必须像地名：含 CJK 或拉丁字母，且不是"这/那/你/我"类代词
        if (Regex("^[这那][里儿边]$").containsMatchIn(place)) return ""
        if (place.contains(Regex("(什么|哪个|哪里|咋|怎么|意思)"))) return ""
        return place
    }

    /** 澄清状态下从用户输入里猜地名：内置表直接命中优先；否则接受 2-6 字纯中文 */
    fun guessPlace(raw: String): String {
        val q = raw.trim()
        if (q.isEmpty() || q.length > 20) return ""
        // 带行政后缀的多级地名：取市/州前的部分优先匹配
        for (k in CITIES.keys) {
            if (q.contains(k)) return k
        }
        val cand = Nlp.stripPolite(q).trim('的', '了', '，', '。', '？', '?', '！', '!', ' ')
        if (cand.length in 2..6 && Regex("^[\\u4e00-\\u9fff]+$").containsMatchIn(cand) &&
            !Regex("(什么|哪个|哪里|查|搜|图片|视频|新闻|意思|介绍|是谁|怎么|为什么)").containsMatchIn(cand)
        ) return cand
        return ""
    }

    /** 坐标解析：内置表 → 行政后缀剥离 → Open-Meteo geocoding（含一次重试） */
    private data class Geo(val lat: Double, val lon: Double, val display: String)

    private suspend fun geocode(place: String): Geo? = withContext(Dispatchers.IO) {
        val candidates = buildList {
            add(place)
            val stripped = place.replace(Regex("(市|区|县|省|自治州|自治区|特别行政区)"), "")
            if (stripped.length >= 2 && stripped != place) add(stripped)
            if (place.length > 3) add(place.take(3))
            if (place.length > 2) add(place.take(2))
        }.distinct()

        // 1) 内置表直接命中（支持多级地名包含匹配，如"云南保山"含"保山"）
        for (cand in candidates) {
            val hit = CITIES.entries.firstOrNull { cand.contains(it.key) || it.key.contains(cand) }
            if (hit != null) return@withContext Geo(hit.value.first, hit.value.second, hit.key)
        }
        // 2) 远程地理编码兜底
        for (cand in candidates) {
            repeat(2) { attempt ->
                val geo = Http.get(
                    "https://geocoding-api.open-meteo.com/v1/search?name=${Nlp.enc(cand)}&count=1&language=zh&format=json",
                    callTimeoutSec = 8
                ) ?: return@repeat
                val arr = try { JSONArray(geo) } catch (e: Exception) { return@repeat }
                if (arr.length() == 0) return@repeat
                val g = arr.getJSONObject(0)
                val admin1 = g.optString("admin1")
                val name = g.optString("name")
                val display = if (admin1.isEmpty() || admin1 == name) name else "$name（$admin1）"
                return@withContext Geo(g.optDouble("latitude"), g.optDouble("longitude"), display)
            }
        }
        return@withContext null
    }

    /** 天气主回答；place 为空或服务不可用返回 null（上层提示/反问） */
    suspend fun tryAnswer(raw: String): String? = withContext(Dispatchers.IO) {
        val q = raw.trim()
        if (!Regex("(天气|气温|温度|下雨|降雨|下雪|冷不冷|热不热|多少度|几度|降水)").containsMatchIn(q)) return@withContext null
        if (Regex("(什么是天气|天气系统|天气预报怎么|气象学|天气API)").containsMatchIn(q)) return@withContext null

        val place = extractPlace(q)
        if (place.isEmpty()) return@withContext null

        val geo = geocode(place) ?: return@withContext null
        fetchAndRender(geo, q)
    }

    /** 直接按地名查（澄清流程用） */
    suspend fun answerFor(place: String, tomorrow: Boolean = false): String? = withContext(Dispatchers.IO) {
        val geo = geocode(place) ?: return@withContext null
        fetchAndRender(geo, if (tomorrow) "明天天气" else "今天天气")
    }

    private suspend fun fetchAndRender(geo: Geo, q: String): String? = withContext(Dispatchers.IO) {
        val w = Http.get(
            "https://api.open-meteo.com/v1/forecast?latitude=${geo.lat}&longitude=${geo.lon}" +
                    "&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m,wind_direction_10m" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                    "&timezone=auto&forecast_days=3",
            callTimeoutSec = 12
        ) ?: Http.get(
            "https://api.open-meteo.com/v1/forecast?latitude=${geo.lat}&longitude=${geo.lon}" +
                    "&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m,wind_direction_10m" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                    "&timezone=auto&forecast_days=3",
            callTimeoutSec = 12
        ) ?: return@withContext null

        return@withContext try {
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
            sb.append("「").append(geo.display).append("」现在的天气：\n\n")
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
