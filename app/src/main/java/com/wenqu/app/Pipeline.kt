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
                        // v1.9：闲聊交给本地模型自由发挥（统一问渠人格），失败才用模板兜底
                        chatFlow(msgId, userText, recentChatContext())
                    }
                }
                is Intent.Ambiguous -> {
                    // v1.9 核心：模糊地带由本地模型自己决定"直接聊还是联网搜"，不写死
                    ambiguousFlow(msgId, userText, route.q, lastQuery)
                }
                is Intent.Time -> {
                    // v1.9：本地算出时间事实 → 模型组织成自然回答（支持一句话多问：附天气事实）
                    timeFlow(msgId, route.q, userText, lastQuery)
                }
                is Intent.WeatherQ -> {
                    // v1.9：气象双源拉事实 → 模型组织回答（多问题全答、地点可沿用上一轮、反问也由模型生成）
                    weatherFlow(msgId, route.q, lastQuery)
                }
                is String -> if (route.startsWith("GUESS:")) guessStep(msgId, route.removePrefix("GUESS:").toIntOrNull() ?: 50, conv)
                is Intent.GameQuit -> {
                    Db.touchConv(currentConvId, gameState = "")
                    finishText(msgId, "好，游戏告一段落。想查什么随时说，我的全网雷达一直开着。")
                }
                is Intent.Recall -> {
                    // v1.9：上句是事实，表述交给模型生成
                    val prev = Db.prevUserText(currentConvId)
                    val fact = if (prev.isEmpty())
                        "【上下文事实】这是我们这通对话的第一句，用户之前没有问过任何问题。"
                    else
                        "【上下文事实】用户上一句问的是：「${prev}」"
                    if (!factAnswer(msgId, userText, listOf(fact), "", 90)) {
                        finishText(
                            msgId,
                            if (prev.isEmpty())
                                "这是我们这通对话的第一句哦，你的「上一个问题」还没诞生呢。要不现在来一个？"
                            else
                                "你上一句问的是：「${prev}」\n\n想让我继续深挖这个话题就说「继续查」，或者直接抛新问题给我。"
                        )
                    }
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

    /**
     * v1.9 口算：本地精确计算结果作为「事实材料」交给模型组织回答（数值不可改动），
     * 用户要求的风格（幽默/严肃/方言腔…）一并交给模型发挥；模型不可用才落到规则文案。
     */
    private suspend fun mathFlow(msgId: Long, userText: String, route: Intent.Math) {
        val style = Nlp.styleReq(userText)
        val mat = buildString {
            append("【计算事实（本地精确计算，结果唯一正确，绝不可改动数值）】")
            append(route.expr).append(" = ").append(route.result)
            if (style != null) append("\n用户指定的回答风格：").append(style)
        }
        if (factAnswer(msgId, userText, listOf(mat), recentChatContext(), 130)) return
        finishText(msgId, MathEval.answerText(route.expr, route.result))
    }

    // ---------------- v1.9：闲聊 / 模糊决策 / 事实型回答 ----------------

    /**
     * 闲聊/情感/常识：不带任何搜索材料，本地模型结合上文自由发挥。
     * 模型不可用或失败 → SmallTalk 规则兜底（保证永远有回应）。
     */
    private suspend fun chatFlow(msgId: Long, userText: String, ctx: String) {
        if (Llm.ready && Prefs.llmEnabled) {
            val usr = (if (ctx.isNotEmpty()) "最近对话：\n$ctx\n\n" else "") + "用户说：" + userText
            update(msgId) { it.copy(text = "", searching = false, streaming = true) }
            val th = Agent.Throttled(90)
            val t = runCatching {
                withTimeout(90_000L) {
                    Llm.complete(Agent.CHAT_SYS, usr, 160, 0.85f) { partial ->
                        if (th.allow()) update(msgId) { it.copy(text = partial) }
                    }
                }
            }.getOrNull()
            if (!t.isNullOrBlank()) {
                val clean = Agent.sanitize(t)
                if (clean.length >= 2) {
                    finishRaw(msgId, clean)
                    Db.touchConv(currentConvId)
                    return
                }
            }
        }
        SmallTalk.match(userText)?.let { m ->
            if (m == "__GAME_START__") { startGuessGame(msgId); return }
            finishText(msgId, m)
            return
        }
        finishText(msgId, "我在呢！刚才思路卡了一下，你再说一遍或者换个说法，我马上接住～")
    }

    /**
     * 模糊地带：由本地模型先决策（CHAT/SEARCH/WEATHER/TIME/MATH/IMAGE/VIDEO），
     * 决策结果再回落到对应流程做二次校验（决策说 TIME 但解析不出时间 → 落搜索），稳而不死。
     */
    private suspend fun ambiguousFlow(msgId: Long, userText: String, clean: String, lastQuery: String) {
        val ctx = recentChatContext()
        update(msgId) { it.copy(text = "小渠正在思考怎么回答最合适……", searching = true, engineStates = emptyList()) }
        when (val d = Agent.decide(userText, ctx)) {
            is Agent.Decision.Chat -> chatFlow(msgId, userText, ctx)
            is Agent.Decision.Weather -> {
                val place = Weather.extractPlace(userText).ifEmpty { Weather.extractPlace(lastQuery) }
                if (place.isNotEmpty()) weatherAnswer(msgId, userText, place, ctx)
                else webFlow(msgId, Intent.Web(clean))
            }
            is Agent.Decision.Time -> {
                if (TimeAnswer.fact(userText) != null) timeFlow(msgId, userText, userText, lastQuery)
                else webFlow(msgId, Intent.Web(clean))
            }
            is Agent.Decision.Math -> {
                val m = MathEval.tryEval(userText)
                if (m != null) mathFlow(msgId, userText, Intent.Math(m.first, m.second))
                else webFlow(msgId, Intent.Web(clean))
            }
            is Agent.Decision.Image -> imageFlow(
                msgId, Nlp.extractTopic(d.query).ifEmpty { d.query }, false, Nlp.mediaCount(userText)
            )
            is Agent.Decision.Video -> {
                val vq = Nlp.videoQuery(d.query)
                videoFlow(msgId, vq.first, false, Nlp.mediaCount(userText), vq.second)
            }
            is Agent.Decision.Web -> webFlow(msgId, Intent.Web(d.query))
            Agent.Decision.FallbackSearch -> webFlow(msgId, Intent.Web(clean))
        }
    }

    /** 时间/日期：本地时钟事实 → 模型组织；一句话里还夹了天气问题就把天气事实一并附上（多问全答） */
    private suspend fun timeFlow(msgId: Long, q: String, userText: String, lastQuery: String) {
        val fact = TimeAnswer.fact(q) ?: TimeAnswer.fact(userText)
        if (fact == null) {
            webFlow(msgId, Intent.Web(Nlp.stripPolite(q).ifEmpty { q }))
            return
        }
        val mats = ArrayList<String>()
        mats.add(fact)
        if (Regex("(天气|气温|温度|多少度|几度|下雨|下雪|冷不冷|热不热)").containsMatchIn(userText)) {
            val place = Weather.extractPlace(userText).ifEmpty { Weather.extractPlace(lastQuery) }
            if (place.isNotEmpty()) {
                runCatching { Weather.fetchFact(place) }.getOrNull()?.let { mats.add(Weather.factToMaterial(it)) }
            }
        }
        if (!factAnswer(msgId, userText, mats, recentChatContext(), 140)) {
            finishText(msgId, TimeAnswer.tryAnswer(q) ?: "现在是 $fact。")
        }
    }

    /** 天气流程：地点解析 → 上下文继承 → 双源事实 → 模型组织（多问题全答）；反问也由模型生成 */
    private suspend fun weatherFlow(msgId: Long, q: String, lastQuery: String) {
        var place = Weather.extractPlace(q)
        val multi = q.count { it == '？' || it == '?' } >= 2 ||
                Regex("(几号|几日|星期几|周几|几点|几月|日期)").containsMatchIn(q)

        if (place.isEmpty()) {
            // 短追问（"明天呢"/"后天怎么"）沿用上一轮天气话题的地点
            if (lastQuery.isNotEmpty() && q.length <= 12 &&
                Regex("(呢|那|再|明天|后天|大后天|周末|接下来|然后|换)").containsMatchIn(q)
            ) {
                place = Weather.extractPlace(lastQuery)
            }
        }

        if (place.isEmpty() && !multi) {
            // 纯天气问题但没说地点：让模型自然反问（不是模板）；同时进入澄清状态
            val mat = "【上下文事实】用户想查天气，但没有说想查哪个地方的天气。" +
                    (if (lastQuery.isNotEmpty()) "上一轮话题：$lastQuery。" else "") +
                    "请用一两句话自然地问清用户想查哪里的天气（可举两个地名例子），不要查任何数据。"
            Db.touchConv(currentConvId, gameState = "clarify:weather")
            if (!factAnswer(msgId, q, listOf(mat), recentChatContext(), 90)) {
                finishText(
                    msgId,
                    "你想知道哪个地方的天气呀？告诉我地名就行，比如「保山」「上海」或「云南腾冲」，我马上帮你查实时天气。"
                )
            }
            return
        }
        weatherAnswer(msgId, q, place, recentChatContext())
    }

    /** 天气回答主体：双源事实 → 模型组织；多问题时附时间事实；全挂走搜索兜底 */
    private suspend fun weatherAnswer(msgId: Long, userText: String, place: String, ctx: String) {
        Db.touchConv(currentConvId, gameState = "")
        val mats = ArrayList<String>()
        if (place.isNotEmpty()) {
            val f = runCatching { Weather.fetchFact(place) }.getOrNull()
            if (f != null) mats.add(Weather.factToMaterial(f))
        }
        if (mats.isEmpty()) {
            val placeGuess = place.ifEmpty { Nlp.extractTopic(userText).ifEmpty { userText } }
            webFlow(msgId, Intent.Web("$placeGuess 天气 今天 实时", deep = false))
            return
        }
        // 多问题：同一条消息里还问了日期/星期/几点 → 附时间事实，问了几个就答几个
        if (Regex("(几号|几日|星期几|周几|几点|几月|日期)").containsMatchIn(userText)) {
            TimeAnswer.fact(userText)?.let { mats.add(it) }
        }
        if (!factAnswer(msgId, userText, mats, ctx, 200)) {
            val w = runCatching { Weather.tryAnswer(userText) }.getOrNull()
            if (w != null) finishText(msgId, w)
            else webFlow(msgId, Intent.Web("$place 天气 今天 实时", deep = false))
        }
    }

    /**
     * v1.9 通用「事实 → 模型组织」管线（时间/天气/计算/回忆共用）：
     * - 材料只含可靠事实（系统时钟/精确计算/实时气象/上文记录），不编造；
     * - 回复全文由模型实时生成，支持一句多问全答、可带 emoji；
     * - 流式 90ms 节流；失败返回 false，调用方走各自兜底。
     */
    private suspend fun factAnswer(
        msgId: Long,
        userText: String,
        materials: List<String>,
        ctx: String,
        maxTokens: Int
    ): Boolean {
        if (!Llm.ready || !Prefs.llmEnabled) return false
        update(msgId) { it.copy(text = "", searching = false, streaming = true) }
        val sys = Agent.PERSONA +
                "\n当前场景：你刚用工具拿到了可靠事实（系统时钟/本地精确计算/实时气象接口/对话记录），" +
                "下面给你「对话上文 + 用户这条消息 + 事实数据」。\n" +
                "要求：\n" +
                "1. 用事实数据组织成自然、亲切的中文回答，数值必须原样引用，绝不能改动或编造；\n" +
                "2. 用户这条消息里问了几个问题（哪怕只带出一个词）就逐个都答到；\n" +
                "3. 事实里没有的信息不要编；天气类可以给一句贴心提醒（穿衣/带伞），不要长篇大论；\n" +
                "4. 2~5 句话说完，不要列表符号，可以带一两个 emoji。只输出回答正文。"
        val usr = (if (ctx.isNotEmpty()) "对话上文：$ctx\n\n" else "") +
                "用户消息：$userText\n\n事实数据：\n" + materials.joinToString("\n\n")
        val usrSafe = if (usr.length > 1200) usr.take(1200) + "…（已截断）" else usr
        val th = Agent.Throttled(90)
        val t = runCatching {
            withTimeout(90_000L) {
                Llm.complete(sys, usrSafe, maxTokens, 0.7f) { partial ->
                    if (th.allow()) update(msgId) { it.copy(text = partial) }
                }
            }
        }.getOrNull()
        if (t.isNullOrBlank()) return false
        val clean = Agent.sanitize(t)
        if (clean.length < 2) return false
        finishRaw(msgId, clean)
        Db.touchConv(currentConvId)
        return true
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
        // v1.8/v1.9 关键：材料总长硬预算（≈800字，Qwen 分词后 ≤850 token）。
        // 材料越短 prefill 越快（用户要求"回复要快"），也绝不超出模型上下文导致静默回退。
        var budget = 800
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
                val ctx = recentChatContext()
                val sys = Agent.ANSWER_SYS
                val usr = (if (ctx.isNotEmpty()) "对话上文：$ctx\n\n" else "") +
                        "用户这条消息：$q\n\n搜索材料：\n$materials"
                // 双保险：即便极端情况下材料仍偏长，也在送模型前截断，绝不越上下文
                val usrSafe = if (usr.length > 1250) usr.take(1250) + "…（材料过长已截断）" else usr
                val th = Agent.Throttled(90)
                val t = runCatching {
                    withTimeout(120_000L) {
                        Llm.complete(sys, usrSafe, 240, 0.65f) { partial ->
                            if (th.allow()) update(msgId) { it.copy(text = partial) }
                        }
                    }
                }.getOrNull()
                if (!t.isNullOrBlank()) {
                    val clean = Agent.sanitize(t)
                    if (clean.length >= 2) {
                        val sugg = buildList {
                            add("再查查")
                            if (imgs.isNotEmpty()) add("换一批") else add("换个说法再问")
                        }
                        finishRaw(msgId, clean, refs, imgs, vids, q, suggestions = sugg)
                        Db.touchConv(currentConvId, lastQuery = q)
                        return
                    }
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
        sources: List<SourceRef> = emptyList(),
        images: List<MediaResult> = emptyList(),
        videos: List<MediaResult> = emptyList(),
        query: String = "",
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
            i = minOf(text.length, i + 6)
            update(msgId) { it.copy(text = text.substring(0, i)) }
            delay(35)
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
