@file:OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)

package com.wenqu.app.ui

import android.widget.Toast
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.wenqu.app.EngineProgress
import com.wenqu.app.MediaResult
import com.wenqu.app.R
import com.wenqu.app.UiMessage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun relTime(t: Long): String {
    val diff = System.currentTimeMillis() - t
    return when {
        diff < 60_000L -> "刚刚"
        diff < 3_600_000L -> "${diff / 60_000} 分钟前"
        diff < 86_400_000L -> "${diff / 3_600_000} 小时前"
        else -> SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(t))
    }
}

fun copyText(ctx: android.content.Context, text: String) {
    try {
        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("wenqu", text))
        Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show()
    } catch (_: Exception) {}
}

@Composable
fun AssistantAvatar(size: Int) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(BrandGradient),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size((size * 0.72f).dp)
        )
    }
}

/** 实时搜索进度：每个引擎一颗芯片，进行中转圈、成功绿条数、失败红叉、冷却 */
@Composable
fun EngineProgressView(
    states: List<EngineProgress>,
    phase: String,
    onEngineTap: (EngineProgress) -> Unit
) {
    Column {
        if (phase.isNotEmpty()) {
            Text(phase, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
        }
        if (states.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                states.forEach { p ->
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (p.state == 1) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                        else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.combinedClickableBridge { onEngineTap(p) }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                        ) {
                            when (p.state) {
                                0 -> CircularProgressIndicator(
                                    modifier = Modifier.size(11.dp),
                                    strokeWidth = 1.5.dp
                                )
                                1 -> {
                                    Text("${p.count}", fontSize = 11.sp, color = Color(0xFF16A34A), fontWeight = FontWeight.Bold)
                                    Text("条", fontSize = 10.sp, color = Color(0xFF16A34A))
                                }
                                2 -> Text("✗", fontSize = 12.sp, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                                else -> Text("冷却", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(p.name, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
            Spacer(Modifier.height(7.dp))
            val ok = states.count { it.state == 1 }
            val total = states.sumOf { it.count }
            Text(
                "已获取 $total 条结果 · 完成 $ok/${states.size} 路 · 点引擎名看它搜到了什么",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 单引擎详情：它搜到了哪些网页，点击直达 */
@Composable
fun EngineDetailDialog(p: EngineProgress, onDismiss: () -> Unit, onOpenLink: (String) -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.widthIn(max = 340.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.name, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    when (p.state) {
                        1 -> Text("搜到 ${p.count} 条", fontSize = 12.sp, color = Color(0xFF16A34A))
                        2 -> Text("本次没有结果", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                        3 -> Text("限流冷却中", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else -> Text("搜索中…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(10.dp))
                if (p.results.isEmpty()) {
                    Text(
                        if (p.state == 1) "该引擎的结果都已并入下方总答案。" else "暂时没有可展示的详情。",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 19.sp
                    )
                } else {
                    Column(
                        Modifier
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        p.results.forEach { r ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .combinedClickableBridge {
                                        if (r.url.startsWith("http")) {
                                            onOpenLink(r.url); onDismiss()
                                        }
                                    }
                            ) {
                                Text(r.title, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(NlpHost.safeHost(r.url), fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Text("点任意条目可直接打开网页", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("关闭") }
            }
        }
    }
}

private object NlpHost {
    fun safeHost(url: String): String = com.wenqu.app.Nlp.hostOf(url).ifEmpty { url.take(40) }
}

@Composable
fun MessageItem(
    m: UiMessage,
    speaking: Boolean,
    onOpenLink: (String) -> Unit,
    onDelete: (Long) -> Unit,
    onReanswer: (Long) -> Unit,
    onSpeakToggle: (String) -> Unit,
    onSend: (String) -> Unit,
    onImageTap: (MediaResult, List<MediaResult>) -> Unit,
    onEngineTap: (EngineProgress) -> Unit
) {
    if (m.role == 0) UserBubble(m, onDelete)
    else AiBubble(m, speaking, onOpenLink, onDelete, onReanswer, onSpeakToggle, onSend, onImageTap, onEngineTap)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserBubble(m: UiMessage, onDelete: (Long) -> Unit) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 56.dp),
        horizontalArrangement = Arrangement.End
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primary,
            shape = RoundedCornerShape(18.dp, 4.dp, 18.dp, 18.dp),
            shadowElevation = 1.dp
        ) {
            Column(
                Modifier
                    .widthIn(max = 300.dp)
                    .combinedClickable(onClick = {}, onLongClick = { menu = true })
                    .padding(horizontal = 13.dp, vertical = 9.dp)
            ) {
                Text(m.text, fontSize = 15.sp, color = MaterialTheme.colorScheme.onPrimary)
                Text(
                    relTime(m.time), fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.65f),
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("复制") },
                leadingIcon = { Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(17.dp)) },
                onClick = { menu = false; copyText(ctx, m.text) }
            )
            DropdownMenuItem(
                text = { Text("删除") },
                leadingIcon = { Icon(Icons.Default.Delete, null, modifier = Modifier.size(17.dp)) },
                onClick = { menu = false; onDelete(m.id) }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AiBubble(
    m: UiMessage,
    speaking: Boolean,
    onOpenLink: (String) -> Unit,
    onDelete: (Long) -> Unit,
    onReanswer: (Long) -> Unit,
    onSpeakToggle: (String) -> Unit,
    onSend: (String) -> Unit,
    onImageTap: (MediaResult, List<MediaResult>) -> Unit,
    onEngineTap: (EngineProgress) -> Unit
) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(end = 44.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            AssistantAvatar(26)
            Text("小渠", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Text(relTime(m.time), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(6.dp))
        Box {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp),
                shadowElevation = 1.dp,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
            ) {
                Column(
                    Modifier
                        .widthIn(max = 340.dp)
                        .combinedClickable(onClick = {}, onLongClick = { menu = true })
                        .padding(horizontal = 13.dp, vertical = 11.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (m.searching) {
                        EngineProgressView(m.engineStates, m.text.ifEmpty { "正在为你翻阅全网，实时进度：" }, onEngineTap)
                    } else {
                        if (m.text.isNotEmpty()) {
                            LinkText(m.text, fontSize = 15.sp, lineHeight = 22.sp, onOpenLink = onOpenLink)
                        }
                        if (m.streaming) BlinkCursor()
                        if (m.images.isNotEmpty()) ImageRow(m.images, onImageTap, onOpenLink)
                        if (m.videos.isNotEmpty()) VideoList(m.videos, onOpenLink)
                        if (m.sources.isNotEmpty()) SourceChips(m.sources, onOpenLink)
                        if (m.suggestions.isNotEmpty() && !m.streaming) SuggestionChips(m.suggestions, onSend)
                    }
                }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("复制全文") },
                    leadingIcon = { Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(17.dp)) },
                    onClick = { menu = false; copyText(ctx, m.text) })
                DropdownMenuItem(text = { Text("重新回答") },
                    leadingIcon = { Icon(Icons.Default.Refresh, null, modifier = Modifier.size(17.dp)) },
                    onClick = { menu = false; onReanswer(m.id) })
                DropdownMenuItem(text = { Text(if (speaking) "停止朗读" else "朗读") },
                    leadingIcon = {
                        Icon(if (speaking) Icons.Default.VolumeOff else Icons.Default.VolumeUp, null, modifier = Modifier.size(17.dp))
                    },
                    onClick = { menu = false; onSpeakToggle(m.text) })
                DropdownMenuItem(text = { Text("删除") },
                    leadingIcon = { Icon(Icons.Default.Delete, null, modifier = Modifier.size(17.dp)) },
                    onClick = { menu = false; onDelete(m.id) })
            }
        }
        if (!m.searching && !m.streaming && m.text.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(0.dp),
                verticalArrangement = Arrangement.spacedBy((-4).dp)
            ) {
                ActionBtn(Icons.Default.Refresh, "重新回答") { onReanswer(m.id) }
                if (m.query.isNotEmpty()) {
                    ActionBtn(Icons.Default.Search, "再查查") { onSend("再查查") }
                    ActionBtn(Icons.Default.Image, "找图片") { onSend("找${m.query}相关图片") }
                }
                // 单条消息语音开关：播放中显示"停止朗读"，再点即停
                ActionBtn(
                    if (speaking) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                    if (speaking) "停止朗读" else "朗读",
                    highlight = speaking
                ) { onSpeakToggle(m.text) }
                ActionBtn(Icons.Default.ContentCopy, "复制") { copyText(ctx, m.text) }
                ActionBtn(Icons.Default.Delete, "删除") { onDelete(m.id) }
            }
        }
    }
}

@Composable
private fun ActionBtn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    highlight: Boolean = false,
    onClick: () -> Unit
) {
    TextButton(
        onClick = onClick,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 7.dp, vertical = 0.dp)
    ) {
        Icon(
            icon, null, modifier = Modifier.size(13.dp),
            tint = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.size(3.dp))
        Text(
            label, fontSize = 12.sp,
            color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun BlinkCursor() {
    val transition = rememberInfiniteTransition(label = "cursor")
    val alpha by transition.animateFloat(
        initialValue = 0.15f, targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            tween(500),
            RepeatMode.Reverse
        ), label = "cursorAlpha"
    )
    Text("▍", color = MaterialTheme.colorScheme.primary.copy(alpha = alpha), fontSize = 15.sp)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourceChips(sources: List<com.wenqu.app.SourceRef>, onOpenLink: (String) -> Unit) {
    var showAll by remember { mutableStateOf(false) }
    Column {
        Text("参考来源（点击直达网页）", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(5.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val list = if (showAll) sources else sources.take(4)
            list.forEachIndexed { i, s ->
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                ) {
                    Text(
                        "${i + 1} · ${s.title}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .combinedClickableBridge { onOpenLink(s.url) }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
            if (!showAll && sources.size > 4) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        "+${sources.size - 4} 更多",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .combinedClickableBridge { showAll = true }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

/** clickable 桥（避免每处都写 @OptIn） */
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableBridge(onClick: () -> Unit): Modifier =
    this.combinedClickable(onClick = onClick)

@Composable
private fun SuggestionChips(list: List<String>, onSuggestion: (String) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        list.forEach { s ->
            Surface(
                shape = RoundedCornerShape(50),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
            ) {
                Text(
                    s, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .combinedClickableBridge { onSuggestion(s) }
                        .padding(horizontal = 11.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun ImageRow(images: List<MediaResult>, onImageTap: (MediaResult, List<MediaResult>) -> Unit, onOpenLink: (String) -> Unit) {
    Column {
        Text("相关图片（点击放大）", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(images) { img ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.combinedClickableBridge { onImageTap(img, images) }
                ) {
                    Column {
                        AsyncImage(
                            model = img.image,
                            contentDescription = img.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(width = 132.dp, height = 108.dp)
                        )
                        Text(
                            buildString {
                                if (img.extra.isNotEmpty()) { append(img.extra); append(" · ") }
                                append(img.source)
                                if (img.title.isNotEmpty()) { append(" · "); append(img.title.take(10)) }
                            },
                            fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ImageDialog(img: MediaResult, onDismiss: () -> Unit, onOpenLink: (String) -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(14.dp)) {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    AsyncImage(
                        model = img.image,
                        contentDescription = img.title,
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 380.dp)
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(img.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text("图片来源：${img.source}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (img.page.startsWith("http")) {
                    Spacer(Modifier.height(8.dp))
                    Row {
                        TextButton(onClick = { onDismiss(); onOpenLink(img.page) }) {
                            Text("查看来源网页", fontSize = 13.sp)
                        }
                        Spacer(Modifier.size(8.dp))
                        TextButton(onClick = onDismiss) { Text("关闭", fontSize = 13.sp) }
                    }
                } else {
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onDismiss) { Text("关闭", fontSize = 13.sp) }
                }
            }
        }
    }
}

@Composable
private fun VideoList(videos: List<MediaResult>, onOpenLink: (String) -> Unit) {
    Column {
        Text("相关视频（点击播放）", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            videos.forEach { v ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickableBridge { onOpenLink(v.page) }
                ) {
                    Surface(shape = RoundedCornerShape(9.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                        if (v.image.startsWith("http")) {
                            AsyncImage(
                                model = v.image, contentDescription = v.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(width = 92.dp, height = 58.dp)
                            )
                        } else {
                            Box(
                                Modifier.size(width = 92.dp, height = 58.dp),
                                contentAlignment = Alignment.Center
                            ) { Icon(Icons.Default.PlayArrow, null, tint = MaterialTheme.colorScheme.primary) }
                        }
                    }
                    Spacer(Modifier.size(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text(v.title, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(v.extra.ifEmpty { null }, v.source).joinToString(" · "),
                            fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    Icon(Icons.Default.PlayArrow, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}
