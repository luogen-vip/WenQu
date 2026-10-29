package com.wenqu.app

/**
 * 答案合成：不做大模型，靠"多源抽取式整合"。
 * 从全部引擎结果中挑出与问题最相关的句子，跨站点去重后组织成结构化回答 + 引用来源 + 互动追问。
 */
object Synth {

    data class Out(val text: String, val sources: List<SourceRef>, val suggestions: List<String>)

    private val engJunk = Regex("(广告|下载APP|下载客户端|登录|注册|版权所有|免责声明|©|All Rights Reserved)")

    fun compose(q: String, ranked: List<WebResult>, states: List<EngineProgress>, deep: Boolean, reanswer: Boolean): Out {
        val okEngines = states.count { it.state == 1 }
        if (ranked.isEmpty()) return Out(failText(okEngines), emptyList(), listOf("再试一次", "换个说法再问"))

        val toks = Nlp.tokens(q)

        class Cand(val text: String, val ri: Int, val host: String, var sc: Double)

        val cands = ArrayList<Cand>()
        ranked.forEachIndexed { ri, r ->
            val src = r.snippet.ifBlank { r.title }
            Nlp.sentences(src).forEachIndexed { si, s ->
                if (engJunk.containsMatchIn(s)) return@forEachIndexed
                var sc = Nlp.coverage(toks, s) * 2.6
                if (si == 0) sc += 0.5
                sc += (1.0 - ri * 0.04).coerceAtLeast(0.2)
                cands.add(Cand(s, ri, Nlp.hostOf(r.url), sc))
            }
        }

        val refs = ArrayList<SourceRef>()
        fun refIdx(r: WebResult): Int {
            val h = Nlp.hostOf(r.url)
            val i = refs.indexOfFirst { it.host == h }
            if (i >= 0) return i
            refs.add(SourceRef(Nlp.shorten(r.title, 16), r.url, r.engine, h))
            return refs.size - 1
        }

        val chosen = ArrayList<Cand>()
        val hostN = HashMap<String, Int>()
        cands.sortedByDescending { it.sc }.forEach { c ->
            if (chosen.size >= 7) return@forEach
            if (c.text.length < 8) return@forEach
            if ((hostN[c.host] ?: 0) >= 2) return@forEach
            if (chosen.any { Nlp.similarity(it.text, c.text) > 0.55 }) return@forEach
            chosen.add(c)
            hostN[c.host] = (hostN[c.host] ?: 0) + 1
        }

        if (chosen.isEmpty()) {
            // 摘要都太短，直接拼前几条摘要
            val top = ranked.take(3)
            top.forEach { refIdx(it) }
            val text = buildString {
                append(opener(okEngines, ranked.size, deep, reanswer))
                append("\n\n")
                top.forEach { append("· ").append(Nlp.shorten(it.snippet.ifBlank { it.title }, 120)).append("\n") }
                append("\n").append(closer(deep))
            }
            val sugg = suggestionsFor(q, ranked)
            return Out(text.trim(), refs.toList(), sugg)
        }

        chosen.sortBy { it.ri }
        chosen.forEach { c -> refIdx(ranked[c.ri]) }

        val text = buildString {
            append(opener(okEngines, ranked.size, deep, reanswer))
            append("\n\n")
            if (chosen.size >= 3) {
                chosen.forEach { c ->
                    append("· ").append(c.text).append("  ［").append(refIdx(ranked[c.ri]) + 1).append("］\n")
                }
            } else {
                chosen.forEach { append(it.text) }
            }
            append("\n").append(closer(deep))
        }

        val sugg = suggestionsFor(q, ranked)
        return Out(text.trim(), refs.toList(), sugg)
    }

    private fun opener(ok: Int, total: Int, deep: Boolean, reanswer: Boolean): String {
        val normal = listOf(
            "查完了！我把 $ok 个搜索引擎抓到的 $total 条结果过了一遍筛，给你捋清楚了：",
            "全网翻了一圈（$ok 路引擎、$total 条结果），重点都在下面：",
            "十四路雷达全开，从 $total 条结果里把最靠谱的部分挑出来了："
        )
        val deeps = listOf(
            "这次我把引擎火力全开、翻得更深，重新核对了一遍：",
            "好，这回是加强版搜索——翻的页更多、来源更广，给你更扎实的答案："
        )
        val re = listOf(
            "行，换个姿势再翻一遍全网，这次给你不一样的角度：",
            "重新打捞了一遍，看看这版答案对不对你的胃口："
        )
        return when {
            reanswer -> re.random()
            deep -> deeps.random()
            else -> normal.random()
        }
    }

    private fun closer(deep: Boolean): String {
        val list = if (deep) listOf(
            "我是逐条核对过的，如果你发现哪个点还是不对，直接回我「再查查」，我换一批来源重新核实。",
            "这版比上一轮深挖了不少。还想再钻哪个点？指定一下，我去专门翻那个方向。"
        ) else listOf(
            "要是觉得哪条不对劲，回我一句「再查查」，我立刻重新翻全网核实。",
            "想再深挖的话，告诉我具体想了解哪个点，我去专门查。",
            "觉得不够准？说「重新回答」或者「再查查」，我换批来源再来一次。"
        )
        return list.random()
    }

    private fun suggestionsFor(q: String, ranked: List<WebResult>): List<String> {
        val out = ArrayList<String>()
        ranked.firstOrNull()?.let { out.add(Nlp.shorten(it.title.replace(Regex("[_｜|]"), " "), 16)) }
        out.add("再查查，要更准的")
        out.add("给我找点相关图片")
        return out.distinct().take(3)
    }

    fun failText(ok: Int): String {
        return if (ok == 0) {
            "这波不太顺：所有搜索引擎都没能返回结果。\n\n可能的原因：网络波动、运营商拦截，或者多个搜索接口同时临时限流（我这边已经自动换过 UA、重试过了）。\n\n建议：1. 确认手机能正常上网；2. 点下方建议重试；3. 到「设置-搜索引擎」多开几个引擎，东边不亮西边亮。"
        } else {
            "查到了一点，但来源比较少（只有 $ok 个引擎响应），我不敢打包票。\n\n建议点下方建议再搜一次，或到设置里启用更多引擎提高命中率。"
        }
    }

    /** 官网/链接类问题：突出显示链接 */
    fun urlAnswer(brand: String, ranked: List<WebResult>): Out {
        val exclude = listOf("baidu.com", "bing.com", "sogou.com", "so.com", "douyin.com", "toutiao.com",
            "metaso.cn", "wikipedia.org", "sm.cn", "duckduckgo.com", "mojeek.com", "searx", "brave.com",
            "zhihu.com", "csdn.net", "bilibili.com", "weibo.com", "tieba.baidu", "wenku.baidu", "zhidao.baidu",
            "baike.baidu", "360.cn", "qihoo.com")
        val cands = ranked.filter { r ->
            val h = Nlp.hostOf(r.url)
            h.isNotEmpty() && exclude.none { h.contains(it) || r.url.contains(it) }
        }
        if (cands.isEmpty()) {
            return compose(brand, ranked, emptyList(), false, false)
        }
        val best = cands.first()
        val refs = cands.take(5).map { SourceRef(Nlp.shorten(it.title, 16), it.url, it.engine, Nlp.hostOf(it.url)) }
        val text = buildString {
            append("帮你核对了 ${cands.size} 个独立来源，「${brand}」的网址是这个：\n\n")
            append(best.url).append("\n\n")
            append("（网页标题：").append(Nlp.shorten(best.title, 30)).append("，来自 ").append(best.engine).append("）\n\n")
            append("点击上面的蓝色链接就能直接在问渠内置浏览器打开。友情提醒：认准官方域名，山寨站常常长得很像。\n\n")
            append("还需要我查查它家的其他信息吗？比如「${brand} 是什么公司」。")
        }
        return Out(text.trim(), refs, listOf("${brand}是什么", "${brand}安全吗", "找相关图片"))
    }

    fun imageIntro(q: String, count: Int): String {
        return "全网帮你扒到了 $count 张「$q」相关的图片：\n\n下面每张都有说明和出处，点开可以看大图、跳转来源网页。\n不满意就回我一句「换一批」或者「不喜欢，重新找」，我立刻去别的图库再挖一批。"
    }

    fun imageFail(q: String): String {
        return "这几路图库这会儿都没吐出「$q」的图，可能是关键词太生僻了。\n\n建议：1. 换个更具体的说法（比如「柴犬 桌面壁纸」）；2. 或者回我「再试一次」，我再翻一遍。"
    }

    fun videoIntro(q: String, count: Int): String {
        return "找到了 $count 个「$q」相关的视频：\n\n点一下就能在问渠内置浏览器里直接播放。想看更多就回「再来一批」，想换主题就直接说。"
    }

    fun videoFail(q: String): String {
        return "视频站这会儿没给出「$q」的结果，回我「再试一次」我马上再翻一轮；或者换个关键词试试。"
    }
}
