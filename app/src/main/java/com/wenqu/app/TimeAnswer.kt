package com.wenqu.app

import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** 本地精准时间/日期问答：不用搜，直接用系统时区库，比搜索结果卡片更准 */
object TimeAnswer {

    private val ZONES = linkedMapOf(
        "北京" to "Asia/Shanghai", "上海" to "Asia/Shanghai", "广州" to "Asia/Shanghai",
        "深圳" to "Asia/Shanghai", "成都" to "Asia/Shanghai", "重庆" to "Asia/Shanghai",
        "杭州" to "Asia/Shanghai", "武汉" to "Asia/Shanghai", "西安" to "Asia/Shanghai",
        "昆明" to "Asia/Shanghai", "保山" to "Asia/Shanghai", "大理" to "Asia/Shanghai",
        "丽江" to "Asia/Shanghai", "南京" to "Asia/Shanghai", "天津" to "Asia/Shanghai",
        "苏州" to "Asia/Shanghai", "长沙" to "Asia/Shanghai", "郑州" to "Asia/Shanghai",
        "青岛" to "Asia/Shanghai", "厦门" to "Asia/Shanghai", "福州" to "Asia/Shanghai",
        "合肥" to "Asia/Shanghai", "沈阳" to "Asia/Shanghai", "哈尔滨" to "Asia/Shanghai",
        "大连" to "Asia/Shanghai", "济南" to "Asia/Shanghai", "太原" to "Asia/Shanghai",
        "南昌" to "Asia/Shanghai", "贵阳" to "Asia/Shanghai", "南宁" to "Asia/Shanghai",
        "兰州" to "Asia/Shanghai", "西宁" to "Asia/Shanghai", "银川" to "Asia/Shanghai",
        "乌鲁木齐" to "Asia/Urumqi", "拉萨" to "Asia/Shanghai", "呼和浩特" to "Asia/Shanghai",
        "香港" to "Asia/Hong_Kong", "澳门" to "Asia/Macau", "台北" to "Asia/Taipei",
        "中国" to "Asia/Shanghai", "国内" to "Asia/Shanghai",
        "纽约" to "America/New_York", "华盛顿" to "America/New_York", "波士顿" to "America/New_York",
        "洛杉矶" to "America/Los_Angeles", "旧金山" to "America/Los_Angeles", "西雅图" to "America/Los_Angeles",
        "芝加哥" to "America/Chicago", "休斯顿" to "America/Chicago", "休斯敦" to "America/Chicago",
        "美国" to "America/New_York", "夏威夷" to "Pacific/Honolulu", "阿拉斯加" to "America/Anchorage",
        "拉斯维加斯" to "America/Los_Angeles", "费城" to "America/New_York", "迈阿密" to "America/New_York",
        "伦敦" to "Europe/London", "曼彻斯特" to "Europe/London", "英国" to "Europe/London",
        "巴黎" to "Europe/Paris", "法国" to "Europe/Paris", "柏林" to "Europe/Berlin", "德国" to "Europe/Berlin",
        "罗马" to "Europe/Rome", "米兰" to "Europe/Rome", "意大利" to "Europe/Rome",
        "马德里" to "Europe/Madrid", "西班牙" to "Europe/Madrid", "里斯本" to "Europe/Lisbon", "葡萄牙" to "Europe/Lisbon",
        "莫斯科" to "Europe/Moscow", "俄罗斯" to "Europe/Moscow", "圣彼得堡" to "Europe/Moscow",
        "苏黎世" to "Europe/Zurich", "瑞士" to "Europe/Zurich", "阿姆斯特丹" to "Europe/Amsterdam", "荷兰" to "Europe/Amsterdam",
        "伊斯坦布尔" to "Europe/Istanbul", "土耳其" to "Europe/Istanbul", "雅典" to "Europe/Athens",
        "维也纳" to "Europe/Vienna", "奥地利" to "Europe/Vienna", "布拉格" to "Europe/Prague", "捷克" to "Europe/Prague",
        "斯德哥尔摩" to "Europe/Stockholm", "瑞典" to "Europe/Stockholm", "奥斯陆" to "Europe/Oslo", "挪威" to "Europe/Oslo",
        "赫尔辛基" to "Europe/Helsinki", "芬兰" to "Europe/Helsinki", "华沙" to "Europe/Warsaw", "波兰" to "Europe/Warsaw",
        "东京" to "Asia/Tokyo", "日本" to "Asia/Tokyo", "大阪" to "Asia/Tokyo", "京都" to "Asia/Tokyo",
        "首尔" to "Asia/Seoul", "韩国" to "Asia/Seoul", "釜山" to "Asia/Seoul",
        "新加坡" to "Asia/Singapore", "吉隆坡" to "Asia/Kuala_Lumpur", "马来西亚" to "Asia/Kuala_Lumpur",
        "曼谷" to "Asia/Bangkok", "泰国" to "Asia/Bangkok", "雅加达" to "Asia/Jakarta", "印尼" to "Asia/Jakarta",
        "河内" to "Asia/Ho_Chi_Minh", "越南" to "Asia/Ho_Chi_Minh", "胡志明市" to "Asia/Ho_Chi_Minh",
        "马尼拉" to "Asia/Manila", "菲律宾" to "Asia/Manila", "仰光" to "Asia/Yangon", "缅甸" to "Asia/Yangon",
        "金边" to "Asia/Phnom_Penh", "柬埔寨" to "Asia/Phnom_Penh",
        "新德里" to "Asia/Kolkata", "孟买" to "Asia/Kolkata", "印度" to "Asia/Kolkata",
        "迪拜" to "Asia/Dubai", "阿联酋" to "Asia/Dubai", "利雅得" to "Asia/Riyadh", "沙特" to "Asia/Riyadh",
        "多哈" to "Asia/Qatar", "卡塔尔" to "Asia/Qatar",
        "悉尼" to "Australia/Sydney", "墨尔本" to "Australia/Melbourne", "澳大利亚" to "Australia/Sydney",
        "布里斯班" to "Australia/Brisbane", "珀斯" to "Australia/Perth",
        "奥克兰" to "Pacific/Auckland", "新西兰" to "Pacific/Auckland",
        "多伦多" to "America/Toronto", "温哥华" to "America/Vancouver", "加拿大" to "America/Toronto",
        "墨西哥城" to "America/Mexico_City", "墨西哥" to "America/Mexico_City",
        "圣保罗" to "America/Sao_Paulo", "里约热内卢" to "America/Sao_Paulo", "巴西" to "America/Sao_Paulo",
        "布宜诺斯艾利斯" to "America/Argentina/Buenos_Aires", "阿根廷" to "America/Argentina/Buenos_Aires",
        "圣地亚哥" to "America/Santiago", "智利" to "America/Santiago", "利马" to "America/Lima", "秘鲁" to "America/Lima",
        "开罗" to "Africa/Cairo", "埃及" to "Africa/Cairo", "约翰内斯堡" to "Africa/Johannesburg",
        "南非" to "Africa/Johannesburg", "内罗毕" to "Africa/Nairobi", "肯尼亚" to "Africa/Nairobi",
        "拉各斯" to "Africa/Lagos", "尼日利亚" to "Africa/Lagos"
    )

    private val weekCn = arrayOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")

    // 问的是"别的事件的什么时候"，不是问当前时间 → 交给搜索
    private val notNowRe = Regex(
        "(什么时候|哪一天|哪年|哪一年|多久|多长时间|时间线|历史|的时候|时间管理|几点上班|几点下班|几点开门|几点关门|几点开始|几点结束|营业时间|上映|播出|开播|放假|开学|比赛|发布会|航天|发射|意义|由来|故事)"
    )

    private fun isTimeQ(q: String): Boolean =
        Regex("(几点|几点了|几点钟|多少点|现在时间|当前时间|什么时间|时间是几点|时间是多少|报个时|报时)").containsMatchIn(q)

    private fun isDateQ(q: String): Boolean =
        Regex("(几号|几日|日期|星期几|礼拜几|周几|几月几|多少号|今天多少|啥日子|什么日子|哪天|今天是什么).{0,6}(日|号)?").containsMatchIn(q) ||
                Regex("^(今天|今天了|今日)[是]?什么日子").containsMatchIn(q)

    /** 路由判定：是不是"问现在几点/今天几号"这种本地秒答型问题（意图路由最优先调用） */
    fun route(q: String): Intent.Time? {
        val s = q.trim()
        if (s.length > 16) return null
        if (!isTimeQ(s) && !isDateQ(s)) return null
        if (notNowRe.containsMatchIn(s)) return null
        return Intent.Time(s)
    }

    /**
     * v1.9：返回纯事实材料（不含任何固定文案），交给本地模型组织成自然回答。
     * 解析不出（地点未知/不像时间问题）返回 null。
     */
    fun fact(raw: String): String? {
        val q = raw.trim()
        if (!isTimeQ(q) && !isDateQ(q)) return null
        if (notNowRe.containsMatchIn(q)) return null

        var place = q
        for (w in listOf(
            "请问", "问一下", "告诉me", "告诉我", "现在", "当前", "今天", "今日",
            "几点了", "几点钟", "几点", "多少点", "什么时间", "时间是多少", "时间是", "时间",
            "日期", "星期几", "礼拜几", "周几", "几号", "几日", "几月几号", "几月几日", "几月",
            "多少号", "是多少", "是", "的", "了", "吗", "呢", "啊", "呀", "一下", "查查", "查下", "看看"
        )) {
            place = place.replace(w, "")
        }
        place = place.trim('，', '。', '？', '?', '！', '!', ' ', '，')
        if (place.length > 8) return null

        var zoneId: ZoneId? = null
        var placeName = "本地"
        if (place.isNotEmpty()) {
            val hit = ZONES.entries.firstOrNull { place.contains(it.key) }
            if (hit == null) return null
            zoneId = ZoneId.of(hit.value)
            placeName = hit.key
        }

        val t = ZonedDateTime.now(zoneId ?: ZoneId.systemDefault())
        val diffH = Duration.between(ZonedDateTime.now(ZoneId.systemDefault()), t).toHours()
        val dateStr = t.format(DateTimeFormatter.ofPattern("yyyy年M月d日"))
        val timeStr = t.format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        val week = weekCn[t.dayOfWeek.value - 1]

        return buildString {
            append("【时间事实（取自手机系统时钟，秒级准确）】")
            append("$placeName 现在：$dateStr $timeStr，$week。")
            if (zoneId != null) {
                append("时区 ${zoneId.id}，")
                append(when {
                    diffH == 0L -> "与用户所在地时区相同"
                    diffH > 0 -> "比用户所在地快 $diffH 小时"
                    else -> "比用户所在地慢 ${-diffH} 小时"
                }).append("。")
            }
        }
    }

    /** 生成秒答；地点未知等无法本地回答时返回 null（由调用方回落到全网搜索） */
    fun tryAnswer(raw: String): String? {
        val q = raw.trim()
        if (!isTimeQ(q) && !isDateQ(q)) return null
        if (notNowRe.containsMatchIn(q)) return null

        // 提取地点：去掉命令词与疑问词
        var place = q
        for (w in listOf(
            "请问", "问一下", "告诉me", "告诉我", "现在", "当前", "今天", "今日",
            "几点了", "几点钟", "几点", "多少点", "什么时间", "时间是多少", "时间是", "时间",
            "日期", "星期几", "礼拜几", "周几", "几号", "几日", "几月几号", "几月几日", "几月",
            "多少号", "是多少", "是", "的", "了", "吗", "呢", "啊", "呀", "一下", "查查", "查下", "看看"
        )) {
            place = place.replace(w, "")
        }
        place = place.trim('，', '。', '？', '?', '！', '!', ' ', '，')
        if (place.length > 8) return null

        var zoneId: ZoneId? = null
        var placeName = "北京时间"
        if (place.isNotEmpty()) {
            val hit = ZONES.entries.firstOrNull { place.contains(it.key) }
            if (hit == null) return null
            zoneId = ZoneId.of(hit.value)
            placeName = "${hit.key}时间"
        }

        val zh = zoneId ?: ZoneId.systemDefault()
        val t = ZonedDateTime.now(zh)
        val diffH = Duration.between(ZonedDateTime.now(ZoneId.systemDefault()), t).toHours()

        val dateStr = t.format(DateTimeFormatter.ofPattern("yyyy年M月d日"))
        val timeStr = t.format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        val week = weekCn[t.dayOfWeek.value - 1]

        return if (zoneId == null) {
            buildString {
                append("现在是 ")
                append("$dateStr $timeStr，$week。")
                append("\n\n直接读给你：")
                if (isTimeQ(q) && !isDateQ(q)) append("现在 ${t.format(DateTimeFormatter.ofPattern("HH:mm"))}。")
                else append("今天是 $dateStr，$week。")
                append("\n\n这个时间取自你手机的系统时钟，秒级准确。还要查点别的吗？")
            }
        } else {
            val diffText = when {
                diffH == 0L -> "和你这里时区相同"
                diffH > 0 -> "比你的当地时间快 $diffH 小时"
                else -> "比你的当地时间慢 ${-diffH} 小时"
            }
            "「${place}」当地现在是：\n\n$dateStr $timeStr，$week\n\n（$placeName，$diffText）\n\n跨时区约会、抢首发、给朋友打电话，都可以照这个来。"
        }
    }
}
