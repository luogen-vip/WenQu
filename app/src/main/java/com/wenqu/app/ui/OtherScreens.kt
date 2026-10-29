package com.wenqu.app.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.wenqu.app.Engines
import com.wenqu.app.Nlp
import com.wenqu.app.Prefs

private data class QuickEngine(val name: String, val template: String)

private val builtinQuick = listOf(
    QuickEngine("必应中国", "https://cn.bing.com/search?q={q}"),
    QuickEngine("必应", "https://www.bing.com/search?q={q}"),
    QuickEngine("百度", "https://m.baidu.com/s?word={q}"),
    QuickEngine("搜狗", "https://www.sogou.com/web?query={q}"),
    QuickEngine("360", "https://www.so.com/s?q={q}"),
    QuickEngine("神马", "https://m.sm.cn/s?q={q}"),
    QuickEngine("头条", "https://so.toutiao.com/search?keyword={q}")
)

/** 快速搜索：选引擎直达该搜索引擎页面（完整网页版，不受抓取限制） */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun QuickSearchScreen(onBack: () -> Unit, onOpenLink: (String) -> Unit) {
    // 系统返回键 = 回到上一级（聊天页），不退出应用
    BackHandler { onBack() }
    var text by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(0) }
    var customs by remember { mutableStateOf(Prefs.customEngines()) }
    val all = builtinQuick + customs.map { QuickEngine(it.first, it.second) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = { Text("快速搜索", fontSize = 17.sp, fontWeight = FontWeight.Bold) },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
        )
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("输入要搜的内容", fontSize = 14.sp) },
                    shape = RoundedCornerShape(14.dp),
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 15.sp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        if (text.isNotBlank()) onOpenLink(all[selected].template.replace("{q}", Nlp.enc(text.trim())))
                    })
                )
                Spacer(Modifier.size(8.dp))
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primary
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clickable {
                                if (text.isNotBlank())
                                    onOpenLink(all[selected].template.replace("{q}", Nlp.enc(text.trim())))
                            }
                            .padding(horizontal = 16.dp, vertical = 14.dp)
                    ) {
                        Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(4.dp))
                        Text("搜索", color = MaterialTheme.colorScheme.onPrimary, fontSize = 14.sp)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("选择搜索引擎", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                all.forEachIndexed { i, e ->
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (i == selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            e.name,
                            fontSize = 13.sp,
                            color = if (i == selected) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clickable { selected = i }
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(
                "提示：快速搜索直接打开所选引擎的完整网页，什么都能搜；而和「小渠」聊天则由她调动多路引擎替你搜完、整理好答案再给你。",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 18.sp
            )
        }
    }
}

/** 设置：语音/深度/主题/引擎开关/自定义引擎/清空数据/关于 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onThemeChanged: (Int) -> Unit) {
    // 系统返回键 = 回到上一级
    BackHandler { onBack() }
    var voice by remember { mutableStateOf(Prefs.voiceEnabled) }
    var deep by remember { mutableStateOf(Prefs.deepDefault) }
    var autoImg by remember { mutableStateOf(Prefs.autoImages) }
    var theme by remember { mutableIntStateOf(Prefs.themeMode) }
    var customs by remember { mutableStateOf(Prefs.customEngines()) }
    var engineIds by remember { mutableStateOf(Prefs.enabledEngineIds()) }
    var addDialog by remember { mutableStateOf(false) }
    var clearDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newUrl by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
    ) {
        TopAppBar(
            title = { Text("设置", fontSize = 17.sp, fontWeight = FontWeight.Bold) },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
        )

        SettingCard("语音与朗读") {
            SettingRow("语音朗读回答", "开启后每条回答自动读出来（系统 TTS）；关闭立即静音") {
                Switch(checked = voice, onCheckedChange = {
                    voice = it; Prefs.voiceEnabled = it
                    // 关闭开关立即停止正在播放的语音
                    if (!it) ChatRepoStopVoice()
                })
            }
            Text(
                "播放中的回答，点消息下方的喇叭按钮即可停止；再次点击重新播放。想要更自然的音色（类似豆包），可在系统「设置-文字转语音 TTS」安装第三方语音引擎（如讯飞），小渠会自动跟随系统引擎。",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 17.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        SettingCard("搜索策略") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text("默认深度搜索", fontSize = 14.sp)
                    Text("每个引擎抓取更多结果，慢一点但更全面", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = deep, onCheckedChange = { deep = it; Prefs.deepDefault = it })
            }
            SettingRow("智能配图配视频", "回答人物/地点/教程类问题时自动附上相关图片视频") {
                Switch(checked = autoImg, onCheckedChange = { autoImg = it; Prefs.autoImages = it })
            }
        }

        SettingCard("主题外观") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
            ) {
                listOf("跟随系统" to 0, "浅色" to 1, "深色" to 2).forEach { (label, v) ->
                    RadioButton(selected = theme == v, onClick = { theme = v; Prefs.themeMode = v; onThemeChanged(v) })
                    Text(label, fontSize = 13.sp)
                    Spacer(Modifier.width(10.dp))
                }
            }
        }

        SettingCard("搜索引擎（国内直连可用；连续失败自动冷却，恢复后重新加入）") {
            Engines.cached().forEach { e ->
                val on = e.id in engineIds
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(e.name, fontSize = 14.sp)
                            if (e.experimental) {
                                Spacer(Modifier.width(6.dp))
                                Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f)) {
                                    Text("实验", fontSize = 9.sp, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                }
                            }
                        }
                        Text(Prefs.healthNote(e.id), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = on, onCheckedChange = { onNow ->
                        Prefs.setEngineEnabled(e.id, onNow)
                        engineIds = Prefs.enabledEngineIds()
                    })
                }
            }
        }

        SettingCard("自定义引擎（用于快速搜索，网址里用 {q} 代表关键词）") {
            customs.forEach { c ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(c.first, fontSize = 14.sp)
                        Text(c.second, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = {
                        Prefs.saveCustoms(customs.filterNot { it == c })
                        customs = Prefs.customEngines()
                    }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Delete, null, modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
            TextButton(onClick = { addDialog = true }) {
                Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(4.dp))
                Text("添加自定义引擎", fontSize = 13.sp)
            }
        }

        SettingCard("数据") {
            TextButton(onClick = { clearDialog = true }) {
                Text("清空全部聊天记录", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
            }
        }

        SettingCard("关于") {
            Text("问渠 WenQu v1.1", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text("作者：罗根", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Text(
                "完全开源（MIT License），不含闭源组件。",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 18.sp
            )
            Text(
                "开源组件：Jetpack Compose、Material 3、OkHttp、Jsoup、Coil、Kotlin Coroutines。",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 18.sp
            )
            Text(
                "搜索数据实时来自各引擎公开网页接口。",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 18.sp
            )
        }
        Spacer(Modifier.height(30.dp))
    }

    if (addDialog) {
        AlertDialog(
            onDismissRequest = { addDialog = false },
            title = { Text("添加自定义引擎") },
            text = {
                Column {
                    OutlinedTextField(
                        value = newName, onValueChange = { newName = it },
                        label = { Text("名称，如：我站") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newUrl, onValueChange = { newUrl = it },
                        label = { Text("网址模板，含 {q}，如 https://x.com/s?q={q}") },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newName.isNotBlank() && newUrl.contains("{q}")) {
                        Prefs.saveCustoms(customs + (newName.trim() to newUrl.trim()))
                        customs = Prefs.customEngines()
                    }
                    newName = ""; newUrl = ""; addDialog = false
                }) { Text("添加") }
            },
            dismissButton = { TextButton(onClick = { addDialog = false }) { Text("取消") } }
        )
    }
    if (clearDialog) {
        AlertDialog(
            onDismissRequest = { clearDialog = false },
            title = { Text("确认清空？") },
            text = { Text("将删除全部对话与消息，不可恢复。") },
            confirmButton = {
                TextButton(onClick = { ChatRepoClear(); clearDialog = false }) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { clearDialog = false }) { Text("取消") } }
        )
    }
}

private fun ChatRepoClear() {
    com.wenqu.app.ChatRepo.clearAll()
}

private fun ChatRepoStopVoice() {
    try { com.wenqu.app.ChatRepo.voice.stop() } catch (_: Exception) {}
}

@Composable
private fun SettingCard(title: String, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

@Composable
private fun SettingRow(title: String, sub: String, trailing: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp)
            Text(sub, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing()
    }
}

private const val DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

/** 内置浏览器：系统 WebView，直接打开任何高亮链接 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewScreen(url: String, onClose: () -> Unit) {
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var current by remember { mutableStateOf(url) }
    var desktop by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf(url) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
        ) {
            IconButton(onClick = {
                val w = webView
                if (w != null && w.canGoBack()) w.goBack() else onClose()
            }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
                textStyle = TextStyle(fontSize = 12.sp),
                placeholder = { Text("网址", fontSize = 12.sp) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = {
                    val target = if (input.startsWith("http")) input else "https://" + input
                    webView?.loadUrl(target)
                })
            )
            IconButton(onClick = {
                desktop = !desktop
                webView?.settings?.userAgentString = if (desktop) DESKTOP_UA else null
                webView?.reload()
            }) {
                Icon(
                    if (desktop) Icons.Default.Smartphone else Icons.Default.Computer,
                    null, modifier = Modifier.size(20.dp)
                )
            }
            IconButton(onClick = { webView?.reload() }) {
                Icon(Icons.Default.Refresh, null, modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(current)))
                } catch (_: Exception) {}
            }) {
                Icon(Icons.Default.OpenInNew, null, modifier = Modifier.size(19.dp))
            }
        }
        if (progress in 1..99) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                trackColor = Color.Transparent
            )
        }
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            current = request.url.toString()
                            input = current
                            return false
                        }
                        override fun onPageFinished(view: WebView, u: String?) {
                            current = u ?: current
                            input = current
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            progress = newProgress
                        }
                    }
                    loadUrl(url)
                    webView = this
                }
            },
            modifier = Modifier.weight(1f)
        )
    }
    BackHandler {
        val w = webView
        if (w != null && w.canGoBack()) w.goBack() else onClose()
    }
}
