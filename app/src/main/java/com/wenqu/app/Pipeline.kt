package com.wenqu.app

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.random.Random

/**
 * 问答总调度：
 * 意图路由 -> 时间/天气/闲聊/计算/游戏（本地秒答）/ 图片 / 视频 / 链接 / 全网搜索
 * -> 多引擎并行 -> 相关性筛选 -> 本地大模型阅读理解式总结（流式生成，不走死模板）
 * -> 模型不可用时回退规则合成 -> 落库持久化（上下文记忆永不丢）
 */
object ChatRepo {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    private val _messages = MutableStateFlow<List<UiMessage>>(emptyList())
    val messages: StateFlow<List<UiMessage>> = _messages

    private val _convs = MutableStateFlow<List<Conv>>(emptyList())
    val convs: StateFlow<List<Conv>> = _convs

    var currentConvId = 0L
        private set
    var currentConvTitle = ""
        private set

    lateinit var voice: Voice
        private set

    // 媒体上下文（"换一批/重新生成"带同一主题；池化翻页避免重复与超页失败）
    private var imageQ = ""
    private var imageTurn = 0
    private var imageCtx = false
    private var imagePool = emptyList<MediaResult>()
    private val imageSeen = LinkedHashSet<String>()

    private var videoQ = ""
    private var videoTurn = 0
    private var videoCtx = false
    private var videoPool = emptyList<MediaResult>()
    private val videoSeen = LinkedHashSet<String>()

    fun init(ctx: Context) {
        AppCtx.init(ctx)
        Prefs.init(ctx)
        Db.init(ctx)
        voice = Voice(ctx)
        refreshConvs()
        openLatestOrCreate()
        Llm.boot() // 本地大模型后台加载，不阻塞 UI
    }

    fun refreshConvs() {
        _convs.value = Db.conversations()
    }

    fun newConversation() {
        currentConvId = Db.createConv("新对话")
        currentConvTitle = "新对话"
        imageCtx = false; videoCtx = false
        _messages.value = emptyList()
        refreshConvs()
    }

    fun openConv(id: Long) {
        currentConvId = id
        _messages.value = Db.messages(id)
        currentConvTitle = Db.conv(id)?.title ?: "对话"
        imageCtx = false; videoCtx = false
    }

    fun openLatestOrCreate() {
        val list = _convs.value.ifEmpty { Db.conversations() }
        if (list.isEmpty()) newConversation() else openConv(list.first().id)
    }

    fun deleteConv(id: Long) {
        Db.deleteConv(id)
        refreshConvs()
        if (id == currentConvId) openLatestOrCreate()
    }

    fun clearAll() {
        Db.clearAll()
        refreshConvs()
        newConversation()
    }

    fun deleteMessage(id: Long) {
        Db.deleteMessage(id)
        _messages.value = _messages.value.filter { it.id != id }
    }

    // ---------------- 发送 ----------------

    fun send(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        val conv = Db.conv(currentConvId) ?: return

        // 关键：取旧的 lastQuery 给本轮路由当上文，本轮输入不覆盖它（各流程完成时才写入真实 query）
        val prevQuery = conv.lastQuery
        val uid = Db.insertMessage(currentConvId, 0, text, "{}")
        val title = if (conv.title.isEmpty() || conv.title == "新对话") Nlp.shorten(text, 14) else conv.title
        Db.touchConv(currentConvId, title = title)
        currentConvTitle = title
        refreshConvs()

        val tempId = -System.nanoTime()
        _messages.value = _messages.value + listOf(
            UiMessage(uid, 0, text),
            UiMessage(tempId, 1, "", searching = true)
        )
        scope.launch { mutex.withLock { runPipeline(tempId, text, prevQuery) } }
    }

    fun reanswer(msgId: Long) {
        val list = _messages.value
        val idx = list.indexOfFirst { it.id == msgId }
        if (idx < 0) return
        val userText = Db.lastUserTextBefore(currentConvId, msgId)
        if (userText.isEmpty()) return
        val prevQuery = Db.conv(currentConvId)?.lastQuery ?: userText
        _messages.value = list.toMutableList().apply {
            this[idx] = this[idx].copy(
                text = "", streaming = false, searching = true,
                engineStates = emptyList(), sources = emptyList(),
                images = emptyList(), videos = emptyList(), suggestions = emptyList()
            )
        }
        scope.launch { mutex.withLock { runPipeline(msgId, userText, prevQuery) } }
    }

    // ---------------- 管线 ----------------

    private suspend fun runPipeline(msgId: Long, userText: String, lastQuery: String) {
        try {
            val conv = Db.conv(currentConvId)
            if (conv == null) {
                finishText(msgId, "会话好像丢了，请新建一个对话。")
                return
            }
            val route = IntentRouter.route(userText, conv, imageCtx, videoCtx, lastQuery)
            when (route) {
                is Intent.SmallTalk -> {
                    if (SmallTalk.lastReply == "__GAME_START__") {
                        startGuessGame(msgId)
                    } else {
                        // 本地大模型实时生成个性化回复（结合最近几轮对话），失败回退模板
                        val ctx = recentChatContext()
                        val llmReply = if (Llm.ready && Prefs.llmEnabled)
                            runCatching {
                                withTimeout(60_000L) {
                                    Llm.complete(
                                        "你是「问渠」APP 的助手小渠，作者罗根。你平时帮用户查全网资料、找图找视频、报天气报时间。现在用户在和你闲聊，请用中文自然回应：1-3句话，活泼友好、口语化，可以带点小幽默，不要用列表，不要自我介绍一长串。结合上下文回复。",
                                        (if (ctx.isNotEmpty()) "最近对话：\n$ctx\n\n" else "") + "用户说：$userText",
                                        120, 0.9f
                                    )
                                }
                            }.getOrNull()
                        else null
                        finishText(msgId, llmReply ?: SmallTalk.lastReply)
                    }
                }
                is Intent.Time -> {
                    // 本地秒答优先；地点未知等无法本地回答时回落搜索
                    val t = TimeAnswer.tryAnswer(route.q)
                    if (t != null) finishText(msgId, t)
                    else webFlow(msgId, Intent.Web(Nlp.stripPolite(route.q).ifEmpty { route.q }))
                }
                is Intent.WeatherQ -> {
                    // 先判断是否说了地点：没说地点 → 反问澄清（这是正确行为，绝不乱搜）
                    val place = Weather.extractPlace(route.q)
                    if (place.isEmpty() && !route.q.contains(Regex("[的]\\s*(今天|明天)"))) {
                        Db.touchConv(currentConvId, gameState = "clarify:weather")
                        finishText(
                            msgId,
                            "你想知道哪个地方的天气呀？告诉我地名就行，比如「保山」「上海」或「云南腾冲」，我马上帮你查实时天气。"
                        )
                        return
                    }
                    // 气象接口（Open-Meteo → wttr.in 多源级联）秒答；全部失败也不卡死：
                    // 把"XX 天气"交给全网搜索+模型总结，答案里带时效提示，绝不再弹"接口没连上"干等
                    val w = runCatching { Weather.tryAnswer(route.q) }.getOrNull()
                    if (w != null) {
                        Db.touchConv(currentConvId, gameState = "")
                        finishText(msgId, w)
                    } else {
                        Db.touchConv(currentConvId, gameState = "")
                        val placeGuess = place.ifEmpty { Nlp.extractTopic(route.q).ifEmpty { route.q } }
                        webFlow(msgId, Intent.Web("$placeGuess 天气 今天 实时", deep = false))
                    }
                }
                is String -> if (route.startsWith("GUESS:")) guessStep(msgId, route.removePrefix("GUESS:").toIntOrNull() ?: 50, conv)
                is Intent.GameQuit -> {
                    Db.touchConv(currentConvId, gameState = "")
                    finishText(msgId, "好，游戏告一段落。想查什么随时说，我的全网雷达一直开着。")
                }
                is Intent.Recall -> {
                    val prev = Db.prevUserText(currentConvId)
                    finishText(
                        msgId,
                        if (prev.isEmpty())
                            "这是我们这通对话的第一句哦，你的「上一个问题」还没诞生呢。要不现在来一个？"
                        else
                            "你上一句问的是：「${prev}」\n\n想让我继续深挖这个话题就说「继续查」，或者直接抛新问题给我。"
                    )
                }
                is Intent.Math -> mathFlow(msgId, userText, route)
                is Intent.Image -> imageFlow(msgId, route.q, route.more, route.count)
                is Intent.Video -> videoFlow(msgId, route.q, route.more, route.count, route.popular)
                is Intent.Url -> urlFlow(msgId, route.brand)
                is Intent.Web -> webFlow(msgId, route)
            }
        } catch (e: Exception) {
            finishText(msgId, "哎呀，小渠刚才翻车了（${e.message ?: "未知错误"}）。\n\n别急，再问一次试试，或者换个说法——我翻车的姿势千奇百怪，但从不躺平。")
        }
    }

    /** 最近几轮对话（供闲聊 LLM 结合上下文） */
    private fun recentChatContext(): String {
        return try {
            Db.messages(currentConvId).takeLast(6)
                .filter { it.text.isNotBlank() }
                .joinToString("\n") { m -> (if (m.role == 0) "用户：" else "小渠：") + m.text.take(80) }
        } catch (_: Exception) { "" }
    }

    /** 口算：本地精确计算保底；若用户附加了语气/风格条件，由本地大模型按要求润色（数值绝不改动） */
    private suspend fun mathFlow(msgId: Long, userText: String, route: Intent.Math) {
        val base = MathEval.answerText(route.expr, route.result)
        val style = Nlp.styleReq(userText)
        if (style != null && Llm.ready) {
            val t = runCatching {
                withTimeout(60_000L) {
                    Llm.complete(
                        "你是「问渠」APP 的助手小渠。用户问了一个计算题并指定了回答风格。题目已经过本地精确计算，结果是唯一正确答案：" +
                                "${route.expr} = ${route.result}。请严格用「$style」的语气风格，用1-3句话自然地告诉用户答案。" +
                                "铁律：数值必须等于 $route.result，绝不能改变数值，也不要发明其他数字。",
                        "用户题目：$userText",
                        130, 0.8f
                    )
                }
            }.getOrNull()
            if (t != null) {
                finishText(msgId, "$t\n\n（本地精确计算：${route.expr} = ${route.result}）")
                return
            }
        }
        finishText(msgId, base)
    }

    // ---------------- 全网搜索（本地大模型总结） ----------------

    /** 从排序结果中提炼候选句（强相关性过滤：联想词条/问句碎片/低覆盖全丢），给 LLM 做材料 */
    private fun buildMaterials(q: String, ranked: List<WebResult>): Pair<String, List<SourceRef>> {
        val toks = Nlp.tokens(q)
        val subject = q.replace(Regex("(是谁|是什么|什么意思|的简介|简介|介绍(一下)?|是谁啊|是谁呢|是谁\\?*|啥情况|怎么样|如何|什么|哪些)"), "").trim()
        val junkRe = Regex("(相关搜索|相关推荐|大家还在搜|还在搜|还在看|热门推荐|为你推荐|更多结果|查看更多|点击进入|立即查看|展开全部|收起|下一页|上一页|百度首页|必应首页|百度百科欢迎|版权所有|免责声明|京ICP|ICP备|登录|注册|下载APP|客户端)")
        val refs = ArrayList<SourceRef>()
        val blocks = ArrayList<String>()
        val seenHost = HashMap<String, Int>()

        for (r in ranked) {
            if (blocks.size >= 8) break
            val h = Nlp.hostOf(r.url)
            if ((seenHost[h] ?: 0) >= 2) continue
            val src = r.snippet.ifBlank { r.title }
            val sents = Nlp.sentences(src).filter { s ->
                s.length in 12..110 &&
                        !junkRe.containsMatchIn(s) &&
                        // 联想问句碎片："孙悟空三打白骨精吗？"这种又短又带问的
                        !(s.length <= 16 && Regex("[吗呢]").containsMatchIn(s)) &&
                        (subject.length < 2 || s.contains(subject) || Nlp.coverage(toks, s) >= 0.45)
            }
            if (sents.isEmpty()) continue
            val idx = refs.indexOfFirst { it.host == h }.let { if (it >= 0) it else refs.size }
            if (idx == refs.size) refs.add(SourceRef(Nlp.shorten(r.title, 16), r.url, r.engine, h))
            blocks.add("[${idx + 1}]（${h}）" + sents.take(2).joinToString(""))
            seenHost[h] = (seenHost[h] ?: 0) + 1
        }
        // v1.8 关键修复：材料总长硬预算（≈1150字，Qwen 分词后 ≤1200 token）。
        // 旧版不预算，长摘要会让 prompt 超出模型上下文，native 层静默返回失败 → 全部回退到
        // 老规则合成 —— 用户看到的"一条条不相关结果"很大程度就是这么来的。
        var budget = 1150
        val limited = ArrayList<String>()
        for (b in blocks) {
            if (budget <= 0) break
            val piece = if (b.length > budget) b.take(budget) else b
            limited.add(piece)
            budget -= piece.length
        }
        return limited.joinToString("\n\n") to refs
    }

    private suspend fun webFlow(msgId: Long, intent: Intent.Web) {
        val q = intent.q
        val engines = Prefs.enabledEngineIds().mapNotNull { Engines.byId(it) }.ifEmpty { Engines.cached() }
        val per = if (intent.deep) 18 else 10

        // 立即点亮所有引擎芯片（转圈），用户零等待感知
        update(msgId) {
            it.copy(
                text = "正在并行检索 ${engines.size} 路引擎，点引擎名可看它搜到了什么：",
                engineStates = engines.map { e -> EngineProgress(e.id, e.name, 0, 0) }
            )
        }
        val agg = Aggregator { p ->
            update(msgId) { m -> m.copy(engineStates = mergeStates(m.engineStates, p), text = "") }
        }
        val (ranked, states) = agg.searchWeb(q, engines, per, intent.deep)

        // 主动配图：涉及人物/地点/事物的介绍类问题，顺带附图帮助理解
        var imgs = emptyList<MediaResult>()
        if (Prefs.autoImages && q.length <= 25 &&
            Regex("(是谁|简介|介绍|长什么样|什么意思|城市|景点|明星|演员|歌手|公司|大学|建筑|纪念碑|山|河|湖|海岛)").containsMatchIn(q)
        ) {
            imgs = runCatching { ImageSearcher.search(q, 0, 6) }.getOrDefault(emptyList())
        }
        // 主动附视频：操作/教程类问题
        var vids = emptyList<MediaResult>()
        if (q.length <= 25 && Regex("(怎么做|如何|教程|安装|步骤|评测|开箱|操作)").containsMatchIn(q)) {
            vids = runCatching { VideoSearcher.search(q, 1, 4) }.getOrDefault(emptyList())
        }

        imageCtx = false; videoCtx = false
        if (imgs.isNotEmpty()) {
            imageCtx = true; imageQ = q; imageTurn = 0
            imagePool = emptyList(); imageSeen.clear()
            imgs.forEach { imageSeen.add(it.image) }
        }
        if (vids.isNotEmpty()) {
            videoCtx = true; videoQ = q; videoTurn = 0
            videoPool = emptyList(); videoSeen.clear()
            vids.forEach { videoSeen.add(it.page) }
        }

        if (ranked.isEmpty()) {
            finishText(
                msgId, Synth.failText(states.count { it.state == 1 }),
                suggestions = listOf("再试一次", "换个说法再问"),
                query = q
            )
            Db.touchConv(currentConvId, lastQuery = q)
            return
        }

        // ---- 本地大模型：阅读理解式总结（流式） ----
        if (Llm.ready && Prefs.llmEnabled) {
            val (materials, refs) = buildMaterials(q, ranked)
            if (materials.isNotEmpty()) {
                update(msgId) {
                    it.copy(
                        text = "",
                        searching = false,
                        streaming = true,
                        engineStates = emptyList()
                    )
                }
                val sys = "你是「问渠」APP 的回答整理引擎，叫小渠。用户给你一个问题和若干带编号的搜索材料。" +
                        "要求：\n" +
                        "1. 只回答问题本身：先直接给出答案（定义/结论/数字），再补充必要细节；\n" +
                        "2. 材料里与问题无关的内容（广告、相关推荐、其他新闻、别的词条）一律忽略，绝不能混进答案；\n" +
                        "3. 用中文回答，2-5句话，连贯自然像人说话，不要罗列要点符号，不要用「根据搜索结果」这类开头；\n" +
                        "4. 材料里没有的信息绝不能编造；\n" +
                        "5. 如果材料确实答不了这个问题，用一两句话诚实说明材料里有什么、缺什么。\n" +
                        "只输出回答正文。"
                val usr = "问题：$q\n\n搜索材料：\n$materials"
                // 双保险：即便极端情况下材料仍偏长，也在送模型前截断，绝不越上下文
                val usrSafe = if (usr.length > 1400) usr.take(1400) + "…（材料过长已截断）" else usr
                val t = runCatching {
                    withTimeout(120_000L) {
                        Llm.complete(sys, usrSafe, 320, 0.7f) { partial ->
                            update(msgId) { it.copy(text = partial) }
                        }
                    }
                }.getOrNull()
                if (!t.isNullOrBlank()) {
                    val sugg = buildList {
                        add("再查查")
                        if (imgs.isNotEmpty()) add("换一批") else add("换个说法再问")
                    }
                    finishRaw(msgId, t.trim(), refs, imgs, vids, q, suggestions = sugg)
                    Db.touchConv(currentConvId, lastQuery = q)
                    return
                }
                // 模型失败 → 走下面的规则合成兜底
            }
        }

        // ---- 规则合成兜底（模型不可用/失败时，仍保证"总结一个答案"而不是罗列网页） ----
        val out = Synth.compose(q, ranked, states, intent.deep, intent.reanswer)
        finishText(msgId, out.text, out.sources, out.suggestions, imgs, vids, q)
        Db.touchConv(currentConvId, lastQuery = q)
    }

    // ---------------- 图片（多主体支持） ----------------

    /** 从池里取未展示过的 n 张（换一批不重复、不超页打叉） */
    private fun freshImages(n: Int): List<MediaResult> {
        val out = ArrayList<MediaResult>()
        val remain = ArrayList<MediaResult>()
        for (m in imagePool) {
            if (out.size < n && m.image.isNotEmpty() && imageSeen.add(m.image)) out.add(m)
            else remain.add(m)
        }
        imagePool = remain
        return out
    }

    /**
     * 多主体图片："孙悟空和熊出没的图片" → 两个主体并行搜、交错展示、每张标注主体。
     * 单主体行为不变；数量均分到各主体（每主体至少2张）。
     */
    private suspend fun imageFlow(msgId: Long, q: String, more: Boolean, count: Int) {
        val query = (if (more && imageQ.isNotEmpty()) imageQ else q).ifEmpty { "风景壁纸" }
        val want = if (count in 1..12) count else 6
        val subjects = if (more) imageSubjects.ifEmpty { Nlp.splitSubjects(query).ifEmpty { listOf(query) } }
                       else Nlp.splitSubjects(query).ifEmpty { listOf(query) }
        if (!more || query != imageQ) {
            imageQ = query; imageTurn = 0
            imageSeen.clear(); imagePool = emptyList()
        }
        imageSubjects = subjects
        imageCtx = true
        videoCtx = false

        var shown = freshImages(want)

        var round = 0
        while (shown.size < want && round < 3) {
            round++
            val offset = imageTurn * 30
            imageTurn++

            val sources = listOf("bingimg" to "必应图片", "baiduimg" to "百度图片", "sogouimg" to "搜狗图片", "soimg" to "360图片")
            val states = mutableListOf<EngineProgress>()
            sources.forEach { (id, name) -> states.add(EngineProgress(id, name, 0, 0)) }
            val label = subjects.joinToString("、")
            update(msgId) { it.copy(engineStates = states.toList(), text = "正在全网找「$label」的图片…") }

            coroutineScope {
                class JobRef(val subj: String, val eid: String)
                val jobs = ArrayList<Pair<JobRef, kotlinx.coroutines.Deferred<List<MediaResult>>>>()
                for (s in subjects) {
                    jobs.add(JobRef(s, "bingimg") to async { runCatching { ImageSearcher.fromBing(s, offset, 30) }.getOrDefault(emptyList()) })
                    jobs.add(JobRef(s, "baiduimg") to async { runCatching { ImageSearcher.fromBaidu(s, offset, 30) }.getOrDefault(emptyList()) })
                    jobs.add(JobRef(s, "sogouimg") to async { runCatching { ImageSearcher.fromSogou(s, offset, 30) }.getOrDefault(emptyList()) })
                    jobs.add(JobRef(s, "soimg") to async { runCatching { ImageSearcher.fromSo360(s, offset, 30) }.getOrDefault(emptyList()) })
                }
                // 收集：按主体分组 → 主体间 round-robin 交错 → 保证"孙悟空、熊出没"各占一半而非只出一种
                val bySubject = LinkedHashMap<String, MutableList<MediaResult>>()
                jobs.forEach { (ref, job) ->
                    val list = job.await()
                    setImgState(msgId, states, ref.eid, list.size)
                    bySubject.getOrPut(ref.subj) { mutableListOf() }.addAll(list)
                }
                val iters = bySubject.entries.map { (subj, l) ->
                    val seenS = HashSet<String>()
                    subj to l.filter { it.image.isNotEmpty() && seenS.add(it.image) }.iterator()
                }
                var added = true
                while (added) {
                    added = false
                    for ((subj, itp) in iters) {
                        if (itp.hasNext()) {
                            imagePool = imagePool + itp.next().copy(extra = subj)
                            added = true
                        }
                    }
                }
            }
            shown = shown + freshImages(want - shown.size)
        }

        val show = shown.take(want)
        if (show.isEmpty()) {
            finishText(msgId, Synth.imageFail(labelOf(subjects)), suggestions = listOf("再试一次", "换个关键词找图"), query = query)
        } else {
            finishText(msgId, Synth.imageIntro(labelOf(subjects), show.size, want), images = show, query = query)
            Db.touchConv(currentConvId, lastQuery = query)
        }
    }

    private var imageSubjects = listOf<String>()

    private fun labelOf(subjects: List<String>): String = subjects.joinToString(" & ")

    private fun setImgState(msgId: Long, states: MutableList<EngineProgress>, id: String, count: Int) {
        synchronized(states) {
            val i = states.indexOfFirst { it.id == id }
            if (i >= 0) {
                val total = (states[i].count) + count
                states[i] = states[i].copy(state = if (total > 0) 1 else 2, count = total)
                val snapshot = states.toList()
                update(msgId) { it.copy(engineStates = snapshot) }
            }
        }
    }

    // ---------------- 视频 ----------------

    private fun freshVideos(n: Int): List<MediaResult> {
        val out = ArrayList<MediaResult>()
        val remain = ArrayList<MediaResult>()
        for (m in videoPool) {
            if (out.size < n && m.page.isNotEmpty() && videoSeen.add(m.page)) out.add(m)
            else remain.add(m)
        }
        videoPool = remain
        return out
    }

    private suspend fun videoFlow(msgId: Long, q: String, more: Boolean, count: Int, popular: Boolean = false) {
        val query = (if (more && videoQ.isNotEmpty()) videoQ else q).ifEmpty { "热门视频" }
        val want = if (count in 1..12) count else 6
        // popular = 用户没给具体题材，只说"好看的/精彩的/有趣的视频"：
        // 此时绝不拿"好看"当搜索词（会搜出标题碰巧带"好看"的视频），直接翻 B站热门榜（真·受欢迎）
        val hotMode = popular || query.isEmpty() ||
                Regex("^(好看|精彩|有趣|搞笑|热门|经典|优质|高分|耐看|上头|推荐)+$").containsMatchIn(query)
        if (!more || query != videoQ) {
            videoQ = query; videoTurn = 0
            videoSeen.clear(); videoPool = emptyList()
        }
        videoCtx = true
        imageCtx = false

        var shown = freshVideos(want)

        var round = 0
        while (shown.size < want && round < 3) {
            round++
            val page = videoTurn + 1
            videoTurn++

            val states = mutableListOf(
                EngineProgress("bili", "哔哩哔哩", 0, 0),
                EngineProgress("bingvid", "必应视频", 0, 0)
            )
            val label = if (hotMode) "全网口碑热门" else query
            update(msgId) { it.copy(engineStates = states.toList(), text = "正在全网找「$label」的视频…") }

            coroutineScope {
                val j1 = async {
                    runCatching {
                        if (hotMode) VideoSearcher.fromBilibiliPopular(page, 12)
                        else VideoSearcher.fromBilibili(query, page, 10)
                    }.getOrDefault(emptyList())
                }
                val j2 = async {
                    runCatching {
                        if (hotMode) VideoSearcher.fromBing("热门视频 榜单 高分", 12)
                        else VideoSearcher.fromBing(query, 12)
                    }.getOrDefault(emptyList())
                }
                val l1 = j1.await(); val l2 = j2.await()
                setVidState(msgId, states, "bili", l1.size)
                setVidState(msgId, states, "bingvid", l2.size)
                videoPool = videoPool + l1 + l2
            }
            shown = shown + freshVideos(want - shown.size)
        }

        val show = shown.take(want)
        if (show.isEmpty()) {
            finishText(msgId, Synth.videoFail(query), suggestions = listOf("再试一次", "换个关键词找视频"), query = query)
        } else {
            finishText(msgId, Synth.videoIntro(if (hotMode) "热门好片" else query, show.size), videos = show, query = query)
            Db.touchConv(currentConvId, lastQuery = query)
        }
    }

    private fun setVidState(msgId: Long, states: MutableList<EngineProgress>, id: String, count: Int) {
        synchronized(states) {
            val i = states.indexOfFirst { it.id == id }
            if (i >= 0) {
                states[i] = states[i].copy(state = if (count > 0) 1 else 2, count = states[i].count + count)
                val snapshot = states.toList()
                update(msgId) { it.copy(engineStates = snapshot) }
            }
        }
    }

    // ---------------- 官网/链接 ----------------

    private suspend fun urlFlow(msgId: Long, brand: String) {
        val q = brand.ifEmpty { "官方网站" }
        val engines = Prefs.enabledEngineIds().mapNotNull { Engines.byId(it) }.ifEmpty { Engines.cached() }
        update(msgId) {
            it.copy(
                text = "正在全网核实「$q」的官方网址……",
                engineStates = engines.map { e -> EngineProgress(e.id, e.name, 0, 0) }
            )
        }
        val agg = Aggregator { p ->
            update(msgId) { m -> m.copy(engineStates = mergeStates(m.engineStates, p), text = "") }
        }
        val (ranked, _) = agg.searchWeb("$q 官网", engines, 12, false)
        if (ranked.isEmpty()) {
            finishText(msgId, Synth.failText(0), suggestions = listOf("再试一次"), query = q)
            return
        }
        val out = Synth.urlAnswer(q, ranked)
        finishText(msgId, out.text, out.sources, out.suggestions, query = q)
        Db.touchConv(currentConvId, lastQuery = q)
    }

    // ---------------- 猜数字 ----------------

    private suspend fun startGuessGame(msgId: Long) {
        val target = Random.nextInt(1, 101)
        Db.touchConv(currentConvId, gameState = "guess:$target:0")
        finishText(
            msgId,
            "来！小渠心里已经想好一个 1 到 100 的整数。\n\n规则：你直接报数字（比如「50」），我告诉你大了还是小了，看几次能锁定。退出就说「不玩了」。\n\n先手让你，猜吧。"
        )
    }

    private suspend fun guessStep(msgId: Long, guess: Int, conv: Conv) {
        val target = conv.gameState.removePrefix("guess:").substringBefore(':').toIntOrNull() ?: Random.nextInt(1, 101)
        val tries = (conv.gameState.substringAfterLast(':').toIntOrNull() ?: 0) + 1
        val reply = SmallTalk.guessReply(target, guess, tries)
        if (reply.startsWith("__GUESS_WIN__")) {
            Db.touchConv(currentConvId, gameState = "")
            finishText(
                msgId,
                String.format(SmallTalk.gameWin, tries) + "\n\n想再来一局说「再来一局」，要查资料随时切换回搜索模式。"
            )
        } else {
            Db.touchConv(currentConvId, gameState = "guess:$target:$tries")
            finishText(msgId, reply)
        }
    }

    // ---------------- 输出 ----------------

    private fun update(msgId: Long, f: (UiMessage) -> UiMessage) {
        _messages.update { list -> list.map { if (it.id == msgId) f(it) else it } }
    }

    private fun mergeStates(old: List<EngineProgress>, p: EngineProgress): List<EngineProgress> {
        val i = old.indexOfFirst { it.id == p.id }
        return if (i >= 0) old.toMutableList().apply { this[i] = p } else old + p
    }

    /** 直接落库完成（LLM 已流式输出过，不再重复打字机） */
    private suspend fun finishRaw(
        msgId: Long,
        text: String,
        sources: List<SourceRef>,
        images: List<MediaResult>,
        videos: List<MediaResult>,
        query: String,
        suggestions: List<String> = emptyList()
    ) {
        update(msgId) {
            it.copy(
                text = text, streaming = false, searching = false,
                sources = sources, images = images, videos = videos,
                suggestions = suggestions,
                query = query
            )
        }
        val meta = Db.metaOf(
            UiMessage(0, 1, text, suggestions = suggestions, sources = sources, images = images, videos = videos, query = query)
        )
        if (msgId < 0) {
            val real = Db.insertMessage(currentConvId, 1, text, meta)
            update(msgId) { it.copy(id = real) }
        } else {
            Db.updateMessage(msgId, text, meta)
        }
        Db.touchConv(currentConvId)
        voice.say(text)
    }

    private suspend fun finishText(
        msgId: Long,
        text: String,
        sources: List<SourceRef> = emptyList(),
        suggestions: List<String> = emptyList(),
        images: List<MediaResult> = emptyList(),
        videos: List<MediaResult> = emptyList(),
        query: String = ""
    ) {
        update(msgId) { it.copy(text = "", searching = false, streaming = true, sources = sources) }
        var i = 0
        while (i < text.length) {
            i = minOf(text.length, i + 3)
            update(msgId) { it.copy(text = text.substring(0, i)) }
            delay(15)
        }
        update(msgId) {
            it.copy(text = text, streaming = false, images = images, videos = videos, suggestions = suggestions, query = query)
        }

        val meta = Db.metaOf(
            UiMessage(0, 1, text, suggestions = suggestions, sources = sources, images = images, videos = videos, query = query)
        )
        if (msgId < 0) {
            val real = Db.insertMessage(currentConvId, 1, text, meta)
            update(msgId) { it.copy(id = real) }
        } else {
            Db.updateMessage(msgId, text, meta)
        }
        Db.touchConv(currentConvId)
        voice.say(text)
    }
}
