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
import kotlin.random.Random

/**
 * 问答总调度：
 * 意图路由 -> 时间/天气/闲聊/计算/游戏（本地秒答） / 图片 / 视频 / 链接 / 全网搜索
 * -> 多引擎并行 -> 抽取合成 -> 打字机流式输出 -> 落库持久化（上下文记忆永不丢）
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
                    val reply = SmallTalk.lastReply
                    if (reply == "__GAME_START__") startGuessGame(msgId) else finishText(msgId, reply)
                }
                is Intent.Time -> {
                    // 本地秒答优先；地点未知等无法本地回答时回落搜索
                    val t = TimeAnswer.tryAnswer(route.q)
                    if (t != null) finishText(msgId, t)
                    else webFlow(msgId, Intent.Web(Nlp.stripPolite(route.q).ifEmpty { route.q }))
                }
                is Intent.WeatherQ -> {
                    // 开源气象接口秒答；失败绝不拿"XX天气"去搜资讯（会跑题），诚实重试
                    val w = runCatching { Weather.tryAnswer(route.q) }.getOrNull()
                    if (w != null) finishText(msgId, w)
                    else finishText(
                        msgId,
                        "天气服务这会儿没连上（开源气象接口临时无响应），小渠不瞎编天气。\n\n点下面「再试一次」我立刻重连气象接口；或者过几分钟再来问我。",
                        suggestions = listOf("再试一次")
                    )
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
                is Intent.Math -> finishText(msgId, MathEval.answerText(route.expr, route.result))
                is Intent.Image -> imageFlow(msgId, route.q, route.more, route.count)
                is Intent.Video -> videoFlow(msgId, route.q, route.more, route.count)
                is Intent.Url -> urlFlow(msgId, route.brand)
                is Intent.Web -> webFlow(msgId, route)
            }
        } catch (e: Exception) {
            finishText(msgId, "哎呀，小渠刚才翻车了（${e.message ?: "未知错误"}）。\n\n别急，再问一次试试，或者换个说法——我翻车的姿势千奇百怪，但从不躺平。")
        }
    }

    // ---------------- 全网搜索 ----------------

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

        if (ranked.isEmpty()) {
            finishText(
                msgId, Synth.failText(states.count { it.state == 1 }),
                suggestions = listOf("再试一次", "换个说法再问"),
                query = q
            )
            return
        }

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

        val out = Synth.compose(q, ranked, states, intent.deep, intent.reanswer)

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

        finishText(msgId, out.text, out.sources, out.suggestions, imgs, vids, q)
        Db.touchConv(currentConvId, lastQuery = q)
    }

    // ---------------- 图片 ----------------

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

    private suspend fun imageFlow(msgId: Long, q: String, more: Boolean, count: Int) {
        val query = (if (more && imageQ.isNotEmpty()) imageQ else q).ifEmpty { "风景壁纸" }
        val want = if (count in 1..12) count else 6
        if (!more || query != imageQ) {
            imageQ = query; imageTurn = 0
            imageSeen.clear(); imagePool = emptyList()
        }
        imageCtx = true
        videoCtx = false

        var shown = freshImages(want)

        var round = 0
        while (shown.size < want && round < 3) {
            round++
            val offset = imageTurn * 30
            imageTurn++

            val states = mutableListOf(
                EngineProgress("bingimg", "必应图片", 0, 0),
                EngineProgress("baiduimg", "百度图片", 0, 0),
                EngineProgress("sogouimg", "搜狗图片", 0, 0),
                EngineProgress("soimg", "360图片", 0, 0)
            )
            update(msgId) { it.copy(engineStates = states.toList(), text = "正在全网找「$query」的图片…") }

            coroutineScope {
                val j1 = async { runCatching { ImageSearcher.fromBing(query, offset, 30) }.getOrDefault(emptyList()) }
                val j2 = async { runCatching { ImageSearcher.fromBaidu(query, offset, 30) }.getOrDefault(emptyList()) }
                val j3 = async { runCatching { ImageSearcher.fromSogou(query, offset, 30) }.getOrDefault(emptyList()) }
                val j4 = async { runCatching { ImageSearcher.fromSo360(query, offset, 30) }.getOrDefault(emptyList()) }
                val l1 = j1.await(); val l2 = j2.await(); val l3 = j3.await(); val l4 = j4.await()
                setImgState(msgId, states, "bingimg", l1.size)
                setImgState(msgId, states, "baiduimg", l2.size)
                setImgState(msgId, states, "sogouimg", l3.size)
                setImgState(msgId, states, "soimg", l4.size)
                imagePool = imagePool + l1 + l2 + l3 + l4
            }
            shown = shown + freshImages(want - shown.size)
        }

        val show = shown.take(want)
        if (show.isEmpty()) {
            finishText(msgId, Synth.imageFail(query), suggestions = listOf("再试一次", "换个关键词找图"), query = query)
        } else {
            finishText(msgId, Synth.imageIntro(query, show.size, want), images = show, query = query)
            Db.touchConv(currentConvId, lastQuery = query)
        }
    }

    private fun setImgState(msgId: Long, states: MutableList<EngineProgress>, id: String, count: Int) {
        synchronized(states) {
            val i = states.indexOfFirst { it.id == id }
            if (i >= 0) {
                states[i] = states[i].copy(state = if (count > 0) 1 else 2, count = count)
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

    private suspend fun videoFlow(msgId: Long, q: String, more: Boolean, count: Int) {
        val query = (if (more && videoQ.isNotEmpty()) videoQ else q).ifEmpty { "热门视频" }
        val want = if (count in 1..12) count else 6
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
            update(msgId) { it.copy(engineStates = states.toList(), text = "正在全网找「$query」的视频…") }

            coroutineScope {
                val j1 = async { runCatching { VideoSearcher.fromBilibili(query, page, 10) }.getOrDefault(emptyList()) }
                val j2 = async { runCatching { VideoSearcher.fromBing(query, 12) }.getOrDefault(emptyList()) }
                val l1 = j1.await(); val l2 = j2.await()
                setImgState(msgId, states, "bili", l1.size)
                setImgState(msgId, states, "bingvid", l2.size)
                videoPool = videoPool + l1 + l2
            }
            shown = shown + freshVideos(want - shown.size)
        }

        val show = shown.take(want)
        if (show.isEmpty()) {
            finishText(msgId, Synth.videoFail(query), suggestions = listOf("再试一次", "换个关键词找视频"), query = query)
        } else {
            finishText(msgId, Synth.videoIntro(query, show.size), videos = show, query = query)
            Db.touchConv(currentConvId, lastQuery = query)
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
