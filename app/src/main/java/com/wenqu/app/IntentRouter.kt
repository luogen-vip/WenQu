package com.wenqu.app

/** 意图路由：先判断用户到底想干嘛，再决定走闲聊、本地秒答还是全网搜索 */
sealed class Intent {
    object SmallTalk : Intent()
    data class Web(val q: String, val deep: Boolean = false, val reanswer: Boolean = false) : Intent()
    data class Image(val q: String, val more: Boolean) : Intent()
    data class Video(val q: String, val more: Boolean) : Intent()
    data class Url(val brand: String) : Intent()
    data class Math(val expr: String, val result: String) : Intent()
    object Recall : Intent()
    object GameQuit : Intent()
}

object IntentRouter {

    fun route(raw: String, conv: Conv, imageCtx: Boolean, videoCtx: Boolean, lastQuery: String): Any {
        val q = raw.trim()
        val ql = q.lowercase()

        // 1) 猜数字进行中：纯数字输入优先进游戏
        if (conv.gameState.startsWith("guess:")) {
            if (Regex("不玩了|结束|退出游戏|换个话题").containsMatchIn(ql)) return Intent.GameQuit
            val num = Regex("\\d+").find(q)?.value?.toIntOrNull()
            if (num != null && Regex("^\\s*\\d+\\s*[吗?？]?$").matches(q)) return "GUESS:$num"
        }

        // 2) 回忆类
        if (Regex("上(一)?个?问题|上一?句|我刚才问|我刚刚问|我说过什么|刚才聊|之前问").containsMatchIn(ql) && q.length <= 16) {
            return Intent.Recall
        }

        // 3) 重新回答
        if (q.length <= 14 && Regex("重新回答|再(回答|答)(一)?次|换个答案|换个说法|再说说|再讲讲").containsMatchIn(ql)) {
            return Intent.Web(lastQuery.ifEmpty { "最近热点新闻" }, reanswer = true)
        }

        // 4) 核查/深挖（针对上一个答案）
        if (q.length <= 30 && Regex("(不对|不准确|不准|错了|不对劲|是假的|假的吧|真的吗|确定吗|靠不靠谱|靠谱吗|再查查|好好查|重新查|再查一下|再仔细查|查证|核实|深入查)").containsMatchIn(ql)) {
            val topic = Nlp.stripPolite(q.replace(Regex("(不对|不准确|不准|错了|不对劲|是假的|假的吧|真的吗|确定吗|靠不靠谱|靠谱吗|再查查|好好查|重新查|再查一下|再仔细查|查证|核实|深入查|这个|答案|，|。|？|\\?)"), ""))
            return if (topic.length >= 2 && !lastQuery.contains(topic)) Intent.Web(topic, deep = true)
            else Intent.Web(lastQuery.ifEmpty { q }, deep = true)
        }

        // 5) 图片上下文：换一批
        val moreRe = Regex("(不喜欢|不好看|不太喜欢|看不上|换一批|再换一批|再来几张|再来几?张|重新找|重新来|再来一组|多来几张)")
        if (imageCtx && moreRe.containsMatchIn(ql) && q.length <= 20) {
            return Intent.Image(lastQuery, more = true)
        }
        if (videoCtx && moreRe.containsMatchIn(ql) && q.length <= 20) {
            return Intent.Video(lastQuery, more = true)
        }
        if (moreRe.containsMatchIn(ql) && q.length <= 14 && lastQuery.isNotEmpty()) {
            return Intent.Web(lastQuery, reanswer = true)
        }

        // 6) 图片请求
        val imgKeyword = Regex("(图片|照片|壁纸|头像|表情包|插图|海报|美图|长什么样|长啥样|长相)")
        if (imgKeyword.containsMatchIn(q)) {
            var sq = q
                .replace(Regex("(帮我)?(全网)?(找|搜|查|来|要|给|发|我想看|想看|我想找|看看)(几)?(张|些|个)?(好看的?)?(的)?"), "")
                .replace(imgKeyword, "")
                .replace(Regex("^(的)+|(的)+$"), "")
                .trim()
            if (sq.isEmpty()) sq = lastQuery
            if (sq.isNotEmpty()) return Intent.Image(sq, more = false)
        }

        // 7) 视频请求
        val vidKeyword = Regex("(视频|影片|预告片|直播回放|纪录片|教程视频|看一集|电影解说)")
        if (vidKeyword.containsMatchIn(q)) {
            var sq = q
                .replace(Regex("(帮我)?(全网)?(找|搜|查|来|要|给|发|我想看|想看|我想找|看看)(几)?(个|部|集)?(的)?"), "")
                .replace(vidKeyword, "")
                .replace(Regex("^(的)+|(的)+$"), "")
                .trim()
            if (sq.isEmpty()) sq = lastQuery
            if (sq.isNotEmpty()) return Intent.Video(sq, more = false)
        }

        // 8) 找网址/官网/链接
        if (Regex("(官网|网址|网站是|网站地址|网站链接|链接是多少|链接是|链接多少|官方网站|url|URL|域名是)").containsMatchIn(q)) {
            var sq = q.replace(Regex("(的)?(官网|网址|网站是|网站地址|网站链接|链接是多少|链接是|链接多少|官方网站|url|URL|域名是?)(多少)?(是)?(什么)?[?？。!！]?"), "")
                .replace(Regex("(告诉我|是多少|是多少啊|是什么)"), "").trim()
            if (sq.length < 2) sq = lastQuery
            return Intent.Url(sq.ifEmpty { q })
        }

        // 9) 口算
        MathEval.tryEval(q)?.let { return Intent.Math(it.first, it.second) }

        // 10) 闲聊
        SmallTalk.match(q)?.let { return Intent.SmallTalk }

        // 11) 常规搜索（清洗口语）
        val clean = Nlp.stripPolite(q).ifEmpty { q }
        val deep = Regex("(深入|详细|全面|仔细)").containsMatchIn(ql) || Prefs.deepDefault
        return Intent.Web(clean, deep = deep)
    }
}
