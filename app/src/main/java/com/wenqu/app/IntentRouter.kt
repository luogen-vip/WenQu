package com.wenqu.app

/** 意图路由：先判断用户到底想干嘛，再决定走闲聊、本地秒答还是全网搜索 */
sealed class Intent {
    object SmallTalk : Intent()
    data class Time(val q: String) : Intent()
    data class WeatherQ(val q: String) : Intent()
    data class Web(val q: String, val deep: Boolean = false, val reanswer: Boolean = false) : Intent()
    data class Image(val q: String, val more: Boolean, val count: Int = 0) : Intent()
    data class Video(val q: String, val more: Boolean, val count: Int = 0) : Intent()
    data class Url(val brand: String) : Intent()
    data class Math(val expr: String, val result: String) : Intent()
    object Recall : Intent()
    object GameQuit : Intent()
}

object IntentRouter {

    fun route(raw: String, conv: Conv, imageCtx: Boolean, videoCtx: Boolean, lastQuery: String): Any {
        val q = raw.trim()
        val ql = q.lowercase()

        // -1) 天气反问澄清中：上一轮我们问了"你想查哪里的天气"，这轮输入应该是个地名
        if (conv.gameState == "clarify:weather") {
            val place = Weather.guessPlace(q)
            if (place.isNotEmpty()) return Intent.WeatherQ("${place}的天气")
            if (!Regex("(天气|气温|温度|下雨|下雪|冷|热|度|风|雨|雪)").containsMatchIn(q)) {
                // 用户换了话题，退出澄清继续正常路由（状态由图片/搜索流程完成时统一清理）
            } else {
                return Intent.WeatherQ(lastQuery.ifEmpty { q })
            }
        }

        // 0) 猜数字进行中：纯数字输入优先进游戏
        if (conv.gameState.startsWith("guess:")) {
            if (Regex("不玩了|结束|退出游戏|换个话题").containsMatchIn(ql)) return Intent.GameQuit
            val num = Regex("\\d+").find(q)?.value?.toIntOrNull()
            if (num != null && Regex("^\\s*\\d+\\s*[吗?？]?$").matches(q)) return "GUESS:$num"
        }

        // 1) 本地时间/日期秒答（绝不能拿"几点"去搜资讯）
        TimeAnswer.route(q)?.let { return it }

        // 2) 天气（先标记，稍后本地气象接口秒答；失败才回落搜索）
        if (Weather.looksLike(q)) return Intent.WeatherQ(q)

        // 3) 闲聊/问候（"你好/在干嘛"绝不该去翻资讯）
        SmallTalk.match(q)?.let { return Intent.SmallTalk }

        // 4) 口算
        MathEval.tryEval(q)?.let { return Intent.Math(it.first, it.second) }

        // 5) 回忆类
        if (Regex("上(一)?个?问题|上一?句|我刚才问|我刚刚问|我说过什么|刚才聊|之前问").containsMatchIn(ql) && q.length <= 16) {
            return Intent.Recall
        }

        // 6) 重新回答 / 再试一次（若是天气/时间问题则重走秒答，而不是搜索）
        if (q.length <= 14 && Regex("重新回答|再(回答|答)(一)?次|再试一次|再搜一次|再试|换个答案|换个说法|再说说|再讲讲").containsMatchIn(ql)) {
            return when {
                Weather.looksLike(lastQuery) -> Intent.WeatherQ(lastQuery)
                TimeAnswer.route(lastQuery) != null -> Intent.Time(lastQuery)
                else -> Intent.Web(lastQuery.ifEmpty { "最近热点新闻" }, reanswer = true)
            }
        }

        // 7) 核查/深挖（针对上一个答案的主题，而不是把"再查查"当关键词）
        if (q.length <= 30 && Regex("(不对|不准确|不准|错了|不对劲|是假的|假的吧|真的吗|确定吗|靠不靠谱|靠谱吗|再查查|再查一查|再查一下|好好查|重新查|再仔细查|查证|核实|深入查|要更准的|再搜搜|再搜一搜|再搜一下|再查查吧|继续查|接着查)").containsMatchIn(ql)) {
            // 剥掉全部指令词/寒暄后剩下的才是新主题；剩下的是纯指令或空 → 用上一轮问题（保证上下文衔接）
            var topic = q
            for (w in listOf(
                "帮我", "帮忙", "麻烦", "请问", "你", "这个", "那个", "答案", "一下", "仔细", "好好", "深入", "继续", "接着",
                "再查查", "再查一查", "再查一下", "再搜搜", "再搜一搜", "再搜一下", "重新查", "查证", "核实", "查查", "查一下", "查",
                "不对", "不准确", "不准", "错了", "不对劲", "是假的", "假的吧", "真的吗", "确定吗", "靠不靠谱", "靠谱吗",
                "要更准的", "好吗", "好不好", "行吗", "可以吗", "吧", "啊", "呢", "呀", "？", "?", "！", "!", "，", ",", "。"
            )) topic = topic.replace(w, "")
            topic = Nlp.stripPolite(topic).trim()
            // 剩余必须像真主题（≥2字、含实义词、不是纯虚词）且不是上一轮主题的子串
            val looksReal = topic.length >= 2 &&
                    Regex("[\\u4e00-\\u9fffA-Za-z0-9]").containsMatchIn(topic) &&
                    !Regex("^(的|了|吗|是|有|和|跟|跟|还|更|再|在|我|你|他|她|它)+$").containsMatchIn(topic)
            return if (looksReal && !lastQuery.contains(topic)) Intent.Web(topic, deep = true)
            else Intent.Web(lastQuery.ifEmpty { q }, deep = true)
        }

        // 8) 图片上下文：换一批 / 重新生成
        val moreRe = Regex("(不喜欢|不好看|不太喜欢|看不上|换一批|再换一批|再来几张|再来几?张|重新找|重新生成|重新来|再来一组|再来一批|多来几张|再换|换一下|重找)")
        val vidKeyword = Regex("(视频|影片|预告片|直播回放|纪录片|教程视频|看一集|电影解说)")
        if (imageCtx && moreRe.containsMatchIn(ql) && q.length <= 20) {
            return Intent.Image(lastQuery, more = true)
        }
        if (videoCtx && moreRe.containsMatchIn(ql) && q.length <= 20) {
            return Intent.Video(lastQuery, more = true)
        }
        if (moreRe.containsMatchIn(ql) && q.length <= 14 && lastQuery.isNotEmpty()) {
            return Intent.Web(lastQuery, reanswer = true)
        }

        // 9) 图片请求（"给我找郑恺相关的图片" → 主体=郑恺；"孙悟空和熊出没的图片" → 多主体，由图片流程拆分）
        val imgKeyword = Regex("(图片|照片|壁纸|头像|表情包|插图|海报|美图|长什么样|长啥样|长相)")
        if (imgKeyword.containsMatchIn(q)) {
            val sq = Nlp.extractTopic(q).ifEmpty { lastQuery }
            if (sq.isNotEmpty()) return Intent.Image(sq, more = false, count = Nlp.mediaCount(q))
        }

        // 9.5) 无显式"图片"词但指令是"找/来/给我 N 张 …"：也是找图（"给我找几张美女的"）
        if (Regex("(找|来|给我|搜).{0,8}(几张|多张|\\d+张|[一二两三四五六七八九十]+张)").containsMatchIn(q) &&
            !imgKeyword.containsMatchIn(q) && !vidKeyword.containsMatchIn(q)
        ) {
            val sq = Nlp.extractTopic(q).ifEmpty { lastQuery }
            if (sq.isNotEmpty()) return Intent.Image(sq, more = false, count = Nlp.mediaCount(q).coerceAtLeast(4))
        }

        // 10) 视频请求
        if (vidKeyword.containsMatchIn(q)) {
            val sq = Nlp.extractTopic(q).ifEmpty { lastQuery }
            if (sq.isNotEmpty()) return Intent.Video(sq, more = false, count = Nlp.mediaCount(q))
        }

        // 11) 找网址/官网/链接
        if (Regex("(官网|网址|网站是|网站地址|网站链接|链接是多少|链接是|链接多少|官方网站|url|URL|域名是)").containsMatchIn(q)) {
            var sq = q.replace(Regex("(的)?(官网|网址|网站是|网站地址|网站链接|链接是多少|链接是|链接多少|官方网站|url|URL|域名是?)(多少)?(是)?(什么)?[?？。!！]?"), "")
                .replace(Regex("(告诉我|是多少|是多少啊|是什么)"), "").trim()
            if (sq.length < 2) sq = lastQuery
            return Intent.Url(sq.ifEmpty { q })
        }

        // 12) 常规搜索（清洗口语）
        val clean = Nlp.stripPolite(q).ifEmpty { q }
        val deep = Regex("(深入|详细|全面|仔细)").containsMatchIn(ql) || Prefs.deepDefault
        return Intent.Web(clean, deep = deep)
    }
}
