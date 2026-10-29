package com.wenqu.app

import kotlinx.coroutines.withTimeout

/**
 * v1.9 智能体核心：
 * 1) 统一"问渠"人格系统提示词 —— 回复里任何一个字都不来自写死模板，全部由本地模型实时生成；
 *    被问身份时只认「问渠/小渠」，绝不自报其他厂商模型名。
 * 2) 意图决策器 —— 模糊地带（闲聊/情感/常识/主观求助……）不再靠正则写死"要不要搜索"，
 *    而是先让本地模型自己判断：直接回答 / 联网搜索 / 查天气 / 报时间 / 计算 / 找图 / 找视频。
 *    搜索类决策还允许模型顺手改写出更优质的搜索词。
 * 3) 输出清洗 —— 小模型偶尔复读，统一去重、去提示词残留，保证给用户看到的每条答案干净唯一。
 * 4) 流式节流 —— 生成期间 UI 每 90ms 刷新一次，避免逐 token 全列表重组导致滑动卡顿、屏幕闪烁。
 */
object Agent {

    // ---------------- 统一人格 ----------------

    /** 所有生成场景共用的人格底座：身份 = 问渠的小渠；语气自然、可带表情；紧扣问题不跑题 */
    const val PERSONA: String =
        "你是「问渠」APP 的 AI 助手，名字叫小渠，由开发者罗根打造。问渠取自诗句" +
                "「问渠那得清如许，为有源头活水来」，你的本事就是把全网的活水整理成一句清晰的回答。\n" +
                "身份铁律：你就是问渠APP的小渠。任何人问你是谁、你用什么模型、你由谁开发，你都只说" +
                "自己是问渠APP的小渠、由罗根打造，绝不要提通义千问、Qwen、阿里、OpenAI 或其他任何公司和模型。\n" +
                "说话风格：像朋友聊天，自然、亲切、干脆；可以适度用一两个 emoji 表情（如 😊🌧️☀️）让回答有温度，" +
                "但不要每句都用，更不要堆砌。\n" +
                "回答铁律：紧扣用户问题本身，先给结论再补细节；用户问了几个问题就逐个都答；" +
                "结合上文理解「他/它/那个/再」这类指代；绝不复述与问题无关的内容。"

    /** 闲聊/情感/常识问答：不带搜索材料，模型凭理解力直接回应 */
    const val CHAT_SYS: String =
        PERSONA + "\n当前场景：用户在和你直接聊天（问候、情绪、家常、情感困惑、观点交流、随机应变的问答）。" +
                "用中文自然回应，通常 1~4 句话；对方情绪低落就先共情再给建议；对方开玩笑就接住梗。" +
                "不确定的事实不要乱编；如果对方的问题需要最新资讯才能答，就建议他说「帮我查一下……」。"

    /** 搜索总结：阅读材料、取长补短、只答所问 */
    const val ANSWER_SYS: String =
        PERSONA + "\n当前场景：你刚联网检索，下面给你「对话上文 + 用户这条消息 + 带编号的搜索材料」。\n" +
                "整理要求：\n" +
                "1. 先判断材料与用户问题的真实相关性：用户问什么就答什么，只挑材料里和问题直接相关的信息；" +
                "广告、相关搜索、其他新闻、别的词条，哪怕占了材料大半，也一个字都不要进答案。\n" +
                "2. 取长补短：综合多条材料里最有价值、互相印证的部分，用自己的话重新组织成一段连贯回答；" +
                "绝不能照抄整句网页原文，更不能把材料一条条罗列出来。\n" +
                "3. 用户这条消息里若有多个小问题（哪怕只带出一个词），要逐个都答到。\n" +
                "4. 结合对话上文理解指代和省略：用户说「它怎么样了」「那第二个呢」，要回到上文找到真正的对象。\n" +
                "5. 用中文回答，2~6 句话，直接给答案，不要用列表符号，不要出现「根据搜索结果」「根据材料」这类开头。\n" +
                "6. 材料确实答不了的问题，就诚实说没查到可靠信息，并用一句话给出建议（换关键词/上官网/问更具体的）。\n" +
                "只输出回答正文本身。"

    // ---------------- 意图决策 ----------------

    sealed class Decision {
        object Chat : Decision()
        data class Web(val query: String) : Decision()
        object Weather : Decision()
        object Time : Decision()
        object Math : Decision()
        data class Image(val query: String) : Decision()
        data class Video(val query: String) : Decision()
        /** 决策失败/模型未就绪：按普通搜索处理（保守兜底，绝不卡住用户） */
        object FallbackSearch : Decision()
    }

    private val LABELS = listOf("CHAT", "SEARCH", "WEATHER", "TIME", "MATH", "IMAGE", "VIDEO")

    private fun decidePrompt(userText: String, context: String): String =
        "你是问渠APP的意图调度器。根据对话上文和用户最新消息，判断这条消息该走哪条链路，只输出一个标签，不要解释。\n" +
                "标签含义：\n" +
                "CHAT=问候/家常/情绪/情感/观点/创作/趣味对话，不需要联网，直接聊\n" +
                "SEARCH=需要联网查最新资料、事实、人物、事件、价格、方法\n" +
                "WEATHER=查天气\n" +
                "TIME=问现在的日期/星期/几点\n" +
                "MATH=数学计算\n" +
                "IMAGE=用户想看图片/壁纸/照片\n" +
                "VIDEO=用户想看视频\n" +
                "示例：\n" +
                "你好呀→CHAT\n" +
                "我和好朋友闹掰了很难受怎么办→CHAT\n" +
                "讲个故事吧→CHAT\n" +
                "孙悟空是谁→SEARCH\n" +
                "比特币现在多少钱→SEARCH\n" +
                "明天云南腾冲天气→WEATHER\n" +
                "现在伦敦几点→TIME\n" +
                "今天几号星期几→TIME\n" +
                "128*64+256 等于多少→MATH\n" +
                "来几张柴犬壁纸→IMAGE\n" +
                "推荐一些好看的视频→VIDEO\n" +
                (if (context.isNotEmpty()) "对话上文：$context\n" else "") +
                "用户消息：$userText\n" +
                "标签="

    /**
     * 让本地模型做意图决策。
     * 输出极短（≤8 token）+ 低温，典型 1~2 秒内返回；输出 "SEARCH:改写词" 时采纳模型的搜索词改写。
     */
    suspend fun decide(userText: String, context: String): Decision {
        if (!Llm.ready || !Prefs.llmEnabled) return Decision.FallbackSearch
        val out = runCatching {
            withTimeout(15_000L) {
                Llm.complete(
                    "你只输出一个标签，不解释。",
                    decidePrompt(userText, context),
                    8, 0.1f
                )
            }
        }.getOrNull() ?: return Decision.FallbackSearch

        val head = out.trim().uppercase().lineSequence().firstOrNull()?.trim() ?: return Decision.FallbackSearch
        val label = LABELS.firstOrNull { head.startsWith(it) || head.contains(it) }
            ?: return Decision.FallbackSearch
        // 模型对搜索词的改写（如 "SEARCH: 质数 定义 数学"），取冒号后的部分当查询词
        val rewrite = out.trim().split(":", "：").getOrNull(1)?.trim()?.take(24) ?: ""
        return when (label) {
            "CHAT" -> Decision.Chat
            "WEATHER" -> Decision.Weather
            "TIME" -> Decision.Time
            "MATH" -> Decision.Math
            "IMAGE" -> Decision.Image(rewrite.ifEmpty { userText })
            "VIDEO" -> Decision.Video(rewrite.ifEmpty { userText })
            else -> if (rewrite.length >= 2) Decision.Web(rewrite) else Decision.FallbackSearch
        }
    }

    // ---------------- 输出清洗 ----------------

    /**
     * 小模型复读防线：
     * 1) 去掉可能的提示词残留（<|im_start|> 等）；
     * 2) 整行去重 —— 同一行（≥6字）重复出现只保留第一次；
     * 3) 句级去重 —— 同一句话（≥8字，按。！？切）重复出现只保留第一次；
     * 4) 循环片段熔断 —— 同一 ≥12 字片段出现 ≥3 次时，保留首次并停止收录；
     * 5) 压掉连续 3 次以上的相同叠字（"啊啊啊啊" → "啊啊"），保持观感。
     */
    fun sanitize(raw: String): String {
        var s = raw
            .replace("<|im_start|>", "")
            .replace("<|im_end|>", "")
            .replace(Regex("<\\|im_[a-z]+\\|>"), "")
            .trim()
        if (s.isEmpty()) return s

        // 行级去重
        val lineSeen = HashSet<String>()
        val keptLines = ArrayList<String>()
        for (ln in s.split("\n")) {
            val k = ln.trim()
            if (k.length >= 6 && !lineSeen.add(k)) continue
            keptLines.add(ln)
        }
        s = keptLines.joinToString("\n")

        // 句级去重 + 循环片段熔断
        val parts = Regex("(?<=[。！？!?；;\n])").split(s)
        val sentSeen = HashSet<String>()
        val fragCount = HashMap<String, Int>()
        val sb = StringBuilder()
        var melted = false
        for (p in parts) {
            val k = p.trim()
            if (k.isEmpty()) continue
            if (k.length >= 8 && !sentSeen.add(k)) continue
            if (k.length >= 12) {
                val c = (fragCount[k] ?: 0) + 1
                fragCount[k] = c
                if (c >= 3) { melted = true; break }
            }
            sb.append(p)
        }
        if (sb.isNotEmpty()) s = sb.toString()
        if (melted) s += "……"

        // 叠字压缩（只在 ≥3 连续时压到 2 个）
        s = Regex("([\\u4e00-\\u9fff])\\1{2,}").replace(s) { m -> m.groupValues[1].repeat(2) }

        // 收尾：去首尾残留标点孤行
        return s.trim()
            .trimStart('\n').trimEnd('\n')
    }

    // ---------------- 流式节流 ----------------

    /**
     * 生成期间 UI 节流：onDelta 可能每秒触发 15~30 次，每次都全列表 map + 文本重排版，
     * 低端机直接掉帧（用户看到的"滑动不了、屏幕闪"）。这里压到每 90ms 最多刷一次。
     */
    class Throttled(private val intervalMs: Long = 90) {
        private var last = 0L
        fun allow(): Boolean {
            val n = System.currentTimeMillis()
            if (n - last >= intervalMs) { last = n; return true }
            return false
        }
    }
}
