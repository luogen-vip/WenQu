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

/** 引擎实时进度 state: 0进行中 1成功 2失败 3冷却 */
data class EngineProgress(
    val id: String,
    val name: String,
    val state: Int,
    val count: Int
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

/** 聊天界面消息 role: 0用户 1AI */
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
    val time: Long = System.currentTimeMillis()
)
