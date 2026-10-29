package com.wenqu.app

import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 本地精准时间/日期问答：不用搜，直接用系统时区库，比搜索结果卡片更准 */
object TimeAnswer {

    private val ZONES = linkedMapOf(
        "北京" to "Asia/Shanghai", "上海" to "Asia/Shanghai", "广州" to "Asia/Shanghai",
        "深圳" to "Asia/Shanghai", "成都" to "Asia/Shanghai", "重庆" to "Asia/Shanghai",
        "杭州" to "Asia/Shanghai", "武汉" to "Asia/Shanghai", "西安" to "Asia/Shanghai",
        "昆明" to "Asia/Shanghai", "保山" to "Asia/Shanghai", "香港" to "Asia/Hong_Kong",
        "澳门" to "Asia/Macau", "台北" to "Asia/Taipei",
        "中国" to "Asia/Shanghai", "国内" to "Asia/Shanghai",
        "纽约" to "America/New_York", "华盛顿" to "America/New_York", "波士顿" to "America/New_York",
        "洛杉矶" to "America/Los_Angeles", "旧金山" to "America/Los_Angeles", "西雅图" to "America/Los_Angeles",
        "芝加哥" to "America/Chicago", "休斯顿" to "America/Chicago", "美国" to "America/New_York",
        "夏威夷" to "Pacific/Honolulu", "阿拉斯加" to "America/Anchorage",
        "伦敦" to "Europe/London", "曼彻斯特" to "Europe/London", "英国" to "Europe/London",
        "巴黎" to "Europe/Paris", "法国" to "Europe/Paris", "柏林" to "Europe/Berlin", "德国" to "Europe/Berlin",
        "罗马" to "Europe/Rome", "米兰" to "Europe/Rome", "意大利" to "Europe/Rome",
        "马德里" to "Europe/Madrid", "西班牙" to "Europe/Madrid", "里斯本" to "Europe/Lisbon", "葡萄牙" to "Europe/Lisbon",
        "莫斯科" to "Europe/Moscow", "俄罗斯" to "Europe/Moscow", "圣彼得堡" to "Europe/Moscow",
        "苏黎世" to "Europe/Zurich", "瑞士" to "Europe/Zurich", "阿姆斯特丹" to "Europe/Amsterdam", "荷兰" to "Europe/Amsterdam",
        "伊斯坦布尔" to "Europe/Istanbul", "土耳其" to "Europe/Istanbul", "雅典" to "Europe/Athens",
        "东京" to "Asia/Tokyo", "日本" to "Asia/Tokyo", "大阪" to "Asia/Tokyo",
        "首尔" to "Asia/Seoul", "韩国" to "Asia/Seoul",
        "新加坡" to "Asia/Singapore", "吉隆坡" to "Asia/Kuala_Lumpur", "马来西亚" to "Asia/Kuala_Lumpur",
        "曼谷" to "Asia/Bangkok", "泰国" to "Asia/Bangkok", "雅加达" to "Asia/Jakarta", "印尼" to "Asia/Jakarta",
        "河内" to "Asia/Ho_Chi_Minh", "越南" to "Asia/Ho_Chi_Minh", "胡志明市" to "Asia/Ho_Chi_Minh",
        "新德里" to "Asia/Kolkata", "孟买" to "Asia/Kolkata", "印度" to "Asia/Kolkata",
        "迪拜" to "Asia/Dubai", "阿联酋" to "Asia/Dubai", "利雅得" to "Asia/Riyadh", "沙特" to "Asia/Riyadh",
        "悉尼" to "Australia/Sydney", "墨尔本" to "Australia/Melbourne", "澳大利亚" to "Australia/Sydney",
        "奥克兰" to "Pacific/Auckland", "新西兰" to "Pacific/Auckland",
        "多伦多" to "America/Toronto", "温哥华" to "America/Vancouver", "加拿大" to "America/Toronto",
        "墨西哥城" to "America/Mexico_City", "墨西哥" to "America/Mexico_City",
        "圣保罗" to "America/Sao_Paulo", "里约热内卢" to "America/Sao_Paulo", "巴西" to "America/Sao_Paulo",
        "布宜诺斯艾利斯" to "America/Argentina/Buenos_Aires", "阿根廷" to "America/Argentina/Buenos_Aires",
        "圣地亚哥" to "America/Santiago", "智利" to "America/Santiago", "利马" to "America/Lima", "秘鲁" to "America/Lima",
        "开罗" to "Africa/Cairo", "埃及" to "Africa/Cairo", "约翰内斯堡" to "Africa/Johannesburg",
        "南非" to "Africa/Johannesburg", "内罗毕" to "Africa/Nairobi", "肯尼亚" to "Africa/Nairobi"
    )

    private val weekCn = arrayOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")

    fun tryAnswer(raw: String): String? {
        val q = raw.trim()
        val isTime = Regex("(几点|点了吗|现在时间|什么时间|时间是多少|当前时间)").containsMatchIn(q)
        val isDate = Regex("(几号|几日|日期|星期几|礼拜几|几月几|多少号)").containsMatchIn(q)
        if (!isTime && !isDate) return null
        // 问的是"什么时候/多久"这类关于别的事件的，不是问当前时间
        if (Regex("(什么时候|哪一天|多久|多长时间|时间线|历史|的时候)").containsMatchIn(q)) return null

        // 提取地点：去掉命令词
        var place = q
        for (w in listOf("请问", "现在", "当前", "今天", "今天", "几点", "了", "吗", "呢", "什么时间", "时间", "日期",
                "星期几", "礼拜几", "几号", "几日", "多少号", "几月几号", "几月", "是多少", "是", "的", "一下", "查查", "告诉me")) {
            place = place.replace(w, "")
        }
        place = place.trim()
        if (place.length > 8) return null // 太长说明不是简单时间问题

        var zoneId: ZoneId? = null
        var placeName = "北京时间"
        if (place.isNotEmpty()) {
            val hit = ZONES.entries.firstOrNull { place.contains(it.key) }
            if (hit == null) return null // 未知地点，交给搜索引擎
            zoneId = ZoneId.of(hit.value)
            placeName = "${hit.key}时间"
        }

        val zh = zoneId ?: ZoneId.systemDefault()
        val t = ZonedDateTime.now(zh)
        val local = LocalDateTime.now()
        val diffH = Duration.between(ZonedDateTime.now(ZoneId.systemDefault()), t).toHours()

        val dateStr = t.format(DateTimeFormatter.ofPattern("yyyy年M月d日"))
        val timeStr = t.format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        val week = weekCn[t.dayOfWeek.value - 1]

        return if (zoneId == null) {
            buildString {
                append("现在是 $dateStr $timeStr，$week（${placeName}）。\n\n")
                if (isDate && !isTime) append("今天这个日子记好了，别错过什么事。需要我帮你查点别的吗？")
                else append("小渠的表走得很准，用的你手机系统时间。还想问什么？")
            }
        } else {
            val diffText = when {
                diffH == 0L -> "和你这里同步"
                diffH > 0 -> "比你的当地时间快 $diffH 小时"
                else -> "比你的当地时间慢 ${-diffH} 小时"
            }
            "「${place.removeSuffix("现在")}」当地现在是：\n\n$dateStr $timeStr，$week\n\n（$placeName，$diffText）\n\n跨时区约会、抢首发、给朋友打电话，都可以照这个来。"
        }
    }
}
