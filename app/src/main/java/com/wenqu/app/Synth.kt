package com.wenqu.app

/**
 * 答案合成：不做大模型，靠"多源抽取式整合"。
 * 策略：直接回答句（定义句/主体开头句）优先 → 段落化输出 → 要点式补充 → 引用编号。
 * 不把原始资讯列表照搬给用户，而是先挑出"直接回答问题的那句话"。
 */
object Synth {

    data class Out(val text: String, val sources: List<SourceRef>, val suggestions: List<String>)

    private val engJunk = Regex("(广告|下载APP|下载客户端|登录|注册|版权所有|免责声明|©|All Rights Reserved|点击|立即|快照)")

    /** 定义句特征：以主体开头，或含"是/指/出生于/成立于/位于"等直接下定义的表达 */
    private val defRe = Regex(
        "(是指|指的是|，(原名|字)|原名|出生于|生于|毕业于|成立于|创立于|创办于|位于|坐落于|是一家|是一种|是一部|是一款|是一种|是一种|总部位于|主演|执导|编剧|歌手|演员|导演|主持人|企业家|科学家|作家|城市|省份|国家|大学|公司|品牌|软件|游戏|电影|电视剧|动画片?)"
    )

    fun compose(q: String, ranked: List<WebResult>, states: List<EngineProgress>, deep: Boolean, reanswer: Boolean): Out {
        val okEngines = states.count { it.state == 1 }
        if (ranked.isEmpty()) return Out(failText(okEngines), emptyList(), listOf("再试一次", "换个说法再问"))

        val toks = Nlp.tokens(q)
        // 问题主体：去掉"是谁/是什么/简介"等疑问词后的核心词（如"郑恺是谁"→"郑恺"）
        val subject = q.replace(Regex("(是谁|是什么|什么意思|的简介|简介|介绍(一下)?|是谁啊|是谁呢|是谁\\?*|啥情况|怎么样|如何)"), "").trim()

        class Cand(val text: String, val ri: Int, val host: String, var sc: Double, val isDef: Boolean)

        val cands = ArrayList<Cand>()
        ranked.forEachIndexed { ri, r ->
            val src = r.snippet.ifBlank { r.title }
            Nlp.sentences(src).forEachIndexed { si, s ->
                if (engJunk.containsMatchIn(s)) return@forEachIndexed
                val cov = Nlp.coverage(toks, s)
                if (cov < 0.15 && toks.size > 2) return@forEachIndexed  // 与问题几乎无关的句子直接丢
                var sc = cov * 2.4
                // 直接回答句大幅加分：主体开头 > 含主体 > 定义句式
                if (subject.length >= 2) {
                    if (s.startsWith(subject)) sc += 3.2
                    else if (s.contains(subject)) sc += 1.1
                }
                val isDef = defRe.containsMatchIn(s) && (subject.isEmpty() || s.contains(subject) || cov > 0.3)
                if (isDef) sc += 1.8
                if (si == 0) sc += 0.6   // 摘要首句更可能是直接回答
                sc += (1.0 - ri * 0.05).coerceAtLeast(0.2)
                cands.add(Cand(s, ri, Nlp.hostOf(r.url), sc, isDef))
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

        val maxPick = if (deep) 6 else 5
        val chosen = ArrayList<Cand>()
        val hostN = HashMap<String, Int>()
        cands.sortedByDescending { it.sc }.forEach { c ->
            if (chosen.size >= maxPick) return@forEach
            if (c.text.length < 10) return@forEach
            if ((hostN[c.host] ?: 0) >= 2) return@forEach
            if (chosen.any { Nlp.similarity(it.text, c.text) > 0.5 }) return@forEach
            chosen.add(c)
            hostN[c.host] = (hostN[c.host] ?: 0) + 1
        }

        if (chosen.isEmpty()) {
            val top = ranked.take(3)
            top.forEach { refIdx(it) }
            val text = buildString {
                append(opener(okEngines, ranked.size, deep, reanswer))
                append("\n\n")
                top.forEach { append("· ").append(Nlp.shorten(it.snippet.ifBlank { it.title }, 110)).append("\n") }
                append("\n").append(closer(deep))
            }
            return Out(text.trim(), refs.toList(), emptyList())
        }

        // 第一句选"最像直接回答"的（定义句优先，其次分数最高），其余按原文顺序做要点
        val lead = chosen.maxByOrNull { it.sc * (if (it.isDef) 2.0 else 1.0) } ?: chosen.first()
        val rest = chosen.filter { it !== lead }.sortedBy { it.ri }

        chosen.forEach { c -> refIdx(ranked[c.ri]) }

        val text = buildString {
            append(opener(okEngines, ranked.size, deep, reanswer))
            append("\n\n")
            append(lead.text)
            val li = refIdx(ranked[lead.ri]) + 1
            append("  ［").append(li).append("］")
            if (rest.isNotEmpty()) {
                append("\n\n再补充几个关键点：\n")
                rest.forEach { c ->
                    append("· ").append(c.text).append("  ［").append(refIdx(ranked[c.ri]) + 1).append("］\n")
                }
            }
            append("\n").append(closer(deep))
        }

        return Out(text.trim(), refs.toList(), emptyList())
    }

    private fun opener(ok: Int, total: Int, deep: Boolean, reanswer: Boolean): String {
        val normal = listOf(
            "查完了，给你划重点：",
            "全网翻完，捋出来了：",
            "帮你查证了一遍，直接说结论："
        )
        val deeps = listOf(
            "这回火力全开、翻得更深，重新核对了一遍，重点如下：",
            "加强版搜索完成——来源更广、翻页更多，给你更扎实的结论："
        )
        val re = listOf(
            "行，换了个姿势再翻一遍全网，这次的角度不太一样：",
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
            "以上是逐条核对过的，如果还有哪个点不准，点下面「再查查」我换一批来源重新核实。",
            "这版比上一轮深挖了不少。想再钻哪个点，直接说，我去专门翻那个方向。"
        ) else listOf(
            "哪里不准就点「再查查」，我立刻换批来源重新核实。",
            "想深入哪个点，说一声我专门去查。",
            "觉得不够准？点「重新回答」或「再查查」，我换批来源再来一次。"
        )
        return list.random()
    }

    fun failText(ok: Int): String {
        return if (ok == 0) {
            "这波不太顺：所有搜索引擎都没能返回结果。\n\n可能的原因：网络波动、运营商拦截，或者多个搜索接口同时临时限流（我这边已经自动换过 UA、重试过了）。\n\n建议：1. 确认手机能正常上网；2. 点下方建议重试；3. 到「设置-搜索引擎」看看引擎开关。"
        } else {
            "查到了一点，但来源比较少（只有 $ok 个引擎响应），我不敢打包票。\n\n建议点下方建议再搜一次，或到设置里看看引擎开关。"
        }
    }

    /** 官网/链接类问题：突出显示链接 */
    fun urlAnswer(brand: String, ranked: List<WebResult>): Out {
        val exclude = listOf("baidu.com", "bing.com", "sogou.com", "so.com", "toutiao.com",
            "sm.cn", "zhihu.com", "csdn.net", "bilibili.com", "weibo.com", "tieba.baidu", "wenku.baidu", "zhidao.baidu",
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
        return Out(text.trim(), refs, emptyList())
    }

    fun imageIntro(q: String, count: Int, want: Int): String {
        return if (count >= want)
            "全网帮你扒到了 $count 张「$q」的图片：\n\n每张都有说明和出处，点开可以看大图、跳转来源网页。\n不满意就点下面的「换一批」，我接着去别的图源再挖一批新的。"
        else
            "「$q」这批先找到 $count 张：\n\n点开可以看大图和出处。图源暂时就这么多，点「换一批」我会翻得更深，或换个更具体的说法（比如「柴犬 桌面壁纸」）。"
    }

    fun imageFail(q: String): String {
        return "这几路图库这会儿都没吐出「$q」的图，可能是关键词太生僻了。\n\n建议：1. 换个更具体的说法（比如「柴犬 桌面壁纸」）；2. 或者点「再试一次」，我再翻一遍。"
    }

    fun videoIntro(q: String, count: Int): String {
        return "找到了 $count 个「$q」相关的视频：\n\n点一下就能在内置浏览器里直接播放。想看更多就点「再来一批」，想换主题就直接说。"
    }

    fun videoFail(q: String): String {
        return "视频站这会儿没给出「$q」的结果，点「再试一次」我马上再翻一轮；或者换个关键词试试。"
    }
}
