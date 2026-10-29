package com.wenqu.app

/** 单条搜索结果 */
data class WebResult(
    val title: String,
    val snippet: String,
    val url: String,
    val engine: String,
    var score: Double = 0.0
)

/** 图片/视频结果 */
data class MediaResult(
    val title: String,
    val image: String,
    val page: String,
    val source: String,
    val extra: String = ""
)

/**
 * 引擎实时进度 state: 0进行中 1成功 2失败 3冷却
 * results：该引擎抓到的条目（截断保存），供点击芯片查看"它搜到了什么"
 */
data class EngineProgress(
    val id: String,
    val name: String,
    val state: Int,
    val count: Int,
    val results: List<WebResult> = emptyList()
)

/** 引用来源 */
data class SourceRef(
    val title: String,
    val url: String,
    val engine: String,
    val host: String
)

/** 会话 */
data class Conv(
    val id: Long,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val lastQuery: String,
    val gameState: String
)

/** 聊天界面消息 role: 0用户 1AI；query 记录本条回答对应的问题，供"再查查/找图片"按钮带上下文 */
data class UiMessage(
    val id: Long,
    val role: Int,
    val text: String,
    val streaming: Boolean = false,
    val searching: Boolean = false,
    val engineStates: List<EngineProgress> = emptyList(),
    val sources: List<SourceRef> = emptyList(),
    val images: List<MediaResult> = emptyList(),
    val videos: List<MediaResult> = emptyList(),
    val suggestions: List<String> = emptyList(),
    val query: String = "",
    val time: Long = System.currentTimeMillis()
)
