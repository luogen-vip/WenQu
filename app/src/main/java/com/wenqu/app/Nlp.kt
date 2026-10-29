package com.wenqu.app

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** 中文文本处理：分词（CJK二元组+英文单词）、句子切分、相似度、链接识别、口语清洗 */
object Nlp {

    val urlRegex: Regex =
        Regex("https?://[^\\s，。、！？；：（）()【】《》\\[\\]“”‘’\"'<>«»…]+")

    private val cjkRegex = Regex("[\\u4e00-\\u9fff]+")
    private val latinRegex = Regex("[A-Za-z0-9]+")

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun hostOf(url: String): String = try {
        (URI(url).host ?: "").lowercase().removePrefix("www.")
    } catch (e: Exception) {
        try { Regex("""https?://([^/?#]+)""").find(url)?.groupValues?.get(1)?.lowercase()?.removePrefix("www.") ?: "" }
        catch (e2: Exception) { "" }
    }

    /** 分词：CJK 按二元组、英文按单词 */
    fun tokens(q: String): List<String> {
        val out = LinkedHashSet<String>()
        latinRegex.findAll(q).forEach { m ->
            val t = m.value.lowercase()
            if (t.length >= 2) out.add(t)
        }
        cjkRegex.findAll(q).forEach { run ->
            val s = run.value
            if (s.length == 1) out.add(s)
            else for (i in 0 until s.length - 1) out.add(s.substring(i, i + 2))
        }
        return out.toList()
    }

    fun coverage(tokens: List<String>, text: String): Double {
        if (tokens.isEmpty() || text.isEmpty()) return 0.0
        val lower = text.lowercase()
        var hit = 0
        for (t in tokens) if (lower.contains(t)) hit++
        return hit.toDouble() / tokens.size
    }

    fun sentences(text: String): List<String> =
        text.split(Regex("(?<=[。！？；;!?])|\\n"))
            .map { it.trim().trimStart('·', '-', '•', ' ') }
            .filter { it.length in 6..170 }

    /** 字符二元组 Jaccard 相似度（去重句子用） */
    fun similarity(a: String, b: String): Double {
        val sa = bigrams(a); val sb = bigrams(b)
        if (sa.isEmpty() || sb.isEmpty()) return 0.0
        val inter = sa.intersect(sb).size
        return inter.toDouble() / (sa.size + sb.size - inter)
    }

    private fun bigrams(s: String): Set<String> {
        val t = s.replace(Regex("\\s"), "")
        if (t.length < 2) return setOf(t)
        return (0 until t.length - 1).map { t.substring(it, it + 2) }.toSet()
    }

    /** 清洗口语前缀后缀，得到更"可搜索"的查询词 */
    fun stripPolite(q: String): String {
        var s = q.trim()
        val prefixes = listOf(
            "请问", "帮我", "帮忙", "麻烦", "我想知道", "我想问", "问一下", "问下", "告诉我",
            "帮我查", "帮我搜", "查询", "搜一下", "搜搜", "查一下", "查查", "搜索一下", "帮忙查",
            "你知道", "你说", "你好我想问", "我想了解", "了解下", "看看"
        )
        var changed = true
        while (changed) {
            changed = false
            for (p in prefixes) {
                if (s.startsWith(p)) { s = s.removePrefix(p).trimStart('，', ',', ' ', '：', ':'); changed = true }
            }
        }
        val suffixes = listOf("吗", "呢", "啊", "呀", "呗", "么", "好不好", "行吗", "可以吗", "谢谢", "3q")
        changed = true
        while (changed && s.length > 4) {
            changed = false
            for (p in suffixes) {
                if (s.endsWith(p) && s.length - p.length >= 4) { s = s.removeSuffix(p).trimEnd('？', '?', '。', '.', '，', ','); changed = true }
            }
        }
        return s.trim()
    }

    fun unwrapRedirect(u: String): String {
        return try {
            if (u.contains("duckduckgo.com/l/")) {
                val query = URI(u).rawQuery ?: ""
                Regex("uddg=([^&]+)").find(query)?.let { URLDecoder.decode(it.groupValues[1], "UTF-8") } ?: u
            } else if (u.contains("bing.com/ck/")) {
                // 必应跳转链 u=a1<base64url>，还原真实网址
                Regex("[?&]u=a1([A-Za-z0-9_-]+)").find(u)?.let { m ->
                    val b64 = m.groupValues[1]
                    val pad = "=".repeat((4 - b64.length % 4) % 4)
                    val dec = String(java.util.Base64.getUrlDecoder().decode(b64 + pad))
                    if (dec.startsWith("http")) dec else u
                } ?: u
            } else u
        } catch (e: Exception) { u }
    }

    /**
     * 从"给我找找郑恺相关的图片"这类指令里提取真正的主体「郑恺」。
     * 提取为空时调用方回退到上一轮话题（lastQuery），保证上下文衔接。
     */
    fun extractTopic(q: String): String {
        var s = q
        s = s.replace(Regex("\\d+\\s*张|[一二两三四五六七八九十]+\\s*张|几张|多张"), "")
        val stops = listOf(
            "帮我", "帮忙", "麻烦", "我想看看", "我想看", "想看看", "想看", "我想找找", "我想找", "我想",
            "给我找找", "给我找", "帮我找找", "帮我找", "给我搜", "给我", "找找", "找一找", "找些", "找点",
            "搜一下", "搜搜", "搜个", "搜下", "查一下", "查查", "查个", "查下",
            "看看", "看一下", "来点", "来几张", "来个", "来几张", "发我", "发几张",
            "你觉得", "我觉得", "你认为", "大家觉得",
            "全网", "相关的", "有关联的", "有关联", "关联的", "相关", "有关的", "有关", "关联",
            "一些", "一点点", "一点", "点点",
            "的图片", "的图", "的相片", "的照片", "图片", "相片", "照片", "图",
            "壁纸", "头像", "海报", "美图", "插图", "表情包", "资料",
            "的视频", "视频", "影片", "预告片", "纪录片", "解说",
            "几个", "一下", "什么"
        )
        var changed = true
        while (changed) {
            changed = false
            for (w in stops) {
                val idx = s.indexOf(w)
                if (idx >= 0) {
                    s = s.removeRange(idx, idx + w.length)
                    changed = true
                }
            }
        }
        return s.trim().trim('的', '了', '，', ',', '。', '？', '?', '！', '!', '、', ' ').trim()
    }

    /** 解析“5张/五张/几张”中的数量，返回 0 表示未指定 */
    fun mediaCount(q: String): Int {
        Regex("(\\d+)\\s*张").find(q)?.let { return (it.groupValues[1].toIntOrNull() ?: 0).coerceIn(1, 12) }
        Regex("([一二两三四五六七八九十]+)\\s*张").find(q)?.let { return chineseNum(it.groupValues[1]).coerceIn(1, 12) }
        return 0
    }

    /**
     * 多主体拆分："孙悟空和熊出没的图片" / "熊出没的、光头强的" / "找孙悟空还有光头强的图"
     * → ["孙悟空", "熊出没"] / ["熊出没", "光头强"]
     * 先提取去指令，再按连接词切分；最多 4 个主体，每个至少 1 字。
     */
    fun splitSubjects(q: String): List<String> {
        val core = extractTopic(q)
        if (core.isEmpty()) return emptyList()
        val parts = core.split(Regex("和|还有|还有个|跟|与|以及|、|，|,|/|＋|\\+"))
            .map { it.trim('的', '了', '，', ' ', '、', '/', ' ') }
            .filter { it.isNotEmpty() && it.length <= 20 }
            .distinct()
        // "孙悟空和熊出没"只有一段被和字切开；若切出来只剩一段则退回整体
        if (parts.isEmpty()) return listOf(core)
        return parts.take(4)
    }

    /**
     * 识别用户在问题里附加的语气/风格条件：
     * "用幽默的语句回答 1+1 等于多少" → "幽默"
     * 返回 null 表示没有附加风格要求。
     */
    fun styleReq(q: String): String? {
        val m = Regex("(幽默|幽黙|搞笑|风趣|诙谐|俏皮|逗比|正经|严肃|认真|简短|简单|详细|通俗易懂|大白话|文言|文言文|古风|诗意|浪漫|文艺|专业|正式|高冷|温柔|可爱|童趣|儿童|像小孩|小学生|一句话|长一点|短一点)").find(q) ?: return null
        val w = m.value
        return when {
            w.contains("文言") -> "文言文"
            w.contains("古风") -> "古风"
            w.contains("诗意") || w.contains("浪漫") || w.contains("文艺") -> "诗意浪漫"
            w.contains("通俗") || w.contains("大白话") -> "通俗易懂"
            w.contains("童趣") || w.contains("儿童") || w.contains("小孩") || w.contains("小学生") -> "面向小学生的活泼口吻"
            w.contains("简短") || w.contains("短一点") || w.contains("一句话") -> "非常简短"
            w.contains("详细") || w.contains("长一点") -> "详细一些"
            else -> w
        }
    }

    fun chineseNum(s: String): Int {
        val digits = mapOf(
            '零' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
            '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9
        )
        return when {
            s.isEmpty() -> 0
            s == "十" -> 10
            s.startsWith("十") -> 10 + (digits[s.last()] ?: 0)
            s.contains("十") -> {
                val parts = s.split("十")
                (digits[parts[0].firstOrNull()] ?: 1) * 10 +
                        (if (parts.size > 1 && parts[1].isNotEmpty()) digits[parts[1].first()] ?: 0 else 0)
            }
            else -> digits[s.firstOrNull()] ?: 0
        }
    }

    fun shorten(t: String, n: Int): String {
        val s = t.trim()
        return if (s.length <= n) s else s.take(n) + "…"
    }
}
