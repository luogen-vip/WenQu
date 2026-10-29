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
            } else u
        } catch (e: Exception) { u }
    }

    fun shorten(t: String, n: Int): String {
        val s = t.trim()
        return if (s.length <= n) s else s.take(n) + "…"
    }
}
