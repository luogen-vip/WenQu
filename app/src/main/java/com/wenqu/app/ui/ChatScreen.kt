package com.wenqu.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wenqu.app.ChatRepo
import com.wenqu.app.EngineProgress
import com.wenqu.app.MediaResult
import kotlinx.coroutines.launch

private val sampleQuestions = listOf(
    "今天天气怎么样",
    "现在纽约几点",
    "孙悟空是谁",
    "B站的官网是多少",
    "来几张柴犬壁纸",
    "找个关于火星探测的视频",
    "128*64+256 等于多少",
    "最近有什么大新闻"
)

/** 输入框提示语：多样化、不固定地名，4 秒轮换 */
private val inputHints = listOf(
    "你可以问我：今天的天气怎么样",
    "你可以问我：现在纽约几点",
    "你可以问我：随便哪位名人是谁",
    "你可以让我：找几张好看的壁纸",
    "你可以问我：128*64+256 等于多少",
    "你可以让我：找几个视频看看",
    "你可以问我：某个网站的官网是多少",
    "你可以问我：最近有什么大新闻"
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    onOpenQuick: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLink: (String) -> Unit
) {
    val msgs by ChatRepo.messages.collectAsState()
    val convs by ChatRepo.convs.collectAsState()
    val speaking by ChatRepo.voice.speaking.collectAsState()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var viewImage by remember { mutableStateOf<MediaResult?>(null) }
    var about by remember { mutableStateOf(false) }
    var engineDetail by remember { mutableStateOf<EngineProgress?>(null) }
    var hintIdx by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(4000)
            hintIdx = (hintIdx + 1) % inputHints.size
        }
    }

    // 抽屉打开时，系统返回键 = 关闭抽屉（而不是退出应用）
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    // 自动滚动：新消息到达贴底；流式增长时若用户仍贴底则继续跟随
    LaunchedEffect(msgs.size) {
        if (msgs.isNotEmpty()) listState.animateScrollToItem(msgs.size - 1)
    }
    val lastLen = msgs.lastOrNull()?.text?.length ?: 0
    LaunchedEffect(lastLen) {
        if (msgs.isNotEmpty()) {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            if (lastVisible >= info.totalItemsCount - 2) listState.animateScrollToItem(msgs.size - 1)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AssistantAvatar(40)
                        Spacer(Modifier.size(10.dp))
                        Column {
                            Text("问渠", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                            Text("作者罗根 · 为有源头活水来", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                HorizontalDivider()
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Add, null) },
                    label = { Text("开启新对话") },
                    selected = false,
                    onClick = {
                        ChatRepo.newConversation()
                        scope.launch { drawerState.close() }
                    }
                )
                Text(
                    "历史对话（点击继续聊，上下文都在）",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
                LazyColumn(Modifier.weight(1f)) {
                    items(convs, key = { it.id }) { c ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (c.id == ChatRepo.currentConvId)
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                    else MaterialTheme.colorScheme.surface
                                )
                                .clickable {
                                    ChatRepo.openConv(c.id)
                                    scope.launch { drawerState.close() }
                                }
                        ) {
                            Column(
                                Modifier
                                    .weight(1f)
                                    .padding(horizontal = 12.dp, vertical = 11.dp)
                            ) {
                                Text(c.title, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${relTime(c.updatedAt)} · ${if (c.lastQuery.isEmpty()) "空对话" else "上下文都在"}",
                                    fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { ChatRepo.deleteConv(c.id) }, modifier = Modifier.size(34.dp)) {
                                Icon(
                                    Icons.Default.Delete, null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                HorizontalDivider()
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Search, null) },
                    label = { Text("快速搜索（直达引擎）") },
                    selected = false,
                    onClick = { scope.launch { drawerState.close() }; onOpenQuick() }
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Settings, null) },
                    label = { Text("设置") },
                    selected = false,
                    onClick = { scope.launch { drawerState.close() }; onOpenSettings() }
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Info, null) },
                    label = { Text("关于与开源") },
                    selected = false,
                    onClick = { scope.launch { drawerState.close() }; about = true }
                )
                Spacer(Modifier.navigationBarsPadding())
            }
        }
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("问渠", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            Text(
                                ChatRepo.currentConvTitle, fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, null)
                        }
                    },
                    actions = {
                        IconButton(onClick = { ChatRepo.newConversation() }) { Icon(Icons.Default.Add, "新对话") }
                        IconButton(onClick = onOpenQuick) { Icon(Icons.Default.Search, "快速搜索") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
                )
            },
            bottomBar = { InputBar(inputHints[hintIdx % inputHints.size]) { ChatRepo.send(it) } }
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                if (msgs.isEmpty()) {
                    EmptyState { ChatRepo.send(it) }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(msgs, key = { it.id }) { m ->
                            MessageItem(
                                m = m,
                                speaking = speaking,
                                onOpenLink = onOpenLink,
                                onDelete = { ChatRepo.deleteMessage(it) },
                                onReanswer = { ChatRepo.reanswer(it) },
                                onSpeakToggle = { ChatRepo.voice.toggle(it) },
                                onSend = { ChatRepo.send(it) },
                                onImageTap = { img, _ -> viewImage = img },
                                onEngineTap = { p -> engineDetail = p }
                            )
                        }
                    }
                }
            }
        }
    }

    viewImage?.let {
        ImageDialog(img = it, onDismiss = { viewImage = null }, onOpenLink = onOpenLink)
    }
    engineDetail?.let { p ->
        EngineDetailDialog(p = p, onDismiss = { engineDetail = null }, onOpenLink = onOpenLink)
    }
    if (about) {
        AlertDialog(
            onDismissRequest = { about = false },
            title = { Text("关于问渠") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "问渠 WenQu v1.1\n作者：罗根\n\n" +
                                "完全开源的全网聚合搜索问答助手：\n\n" +
                                "· 必应/必应中国/百度/搜狗/360/神马/头条 多路引擎并行抓取，谁抽风换谁上\n" +
                                "· 图片四路、视频两路全网匹配\n" +
                                "· 天气/时间/计算本地秒答，精准不出错\n" +
                                "· 上下文记忆持久保存，重启不丢\n" +
                                "· 语音朗读、流式输出、来源可溯源\n\n" +
                                "本应用 100% 开源（MIT License），不含闭源组件与追踪 SDK。\n" +
                                "开源组件：Jetpack Compose、Material 3、OkHttp、Jsoup、Coil、Kotlin Coroutines，均遵循其各自开源协议。",
                        fontSize = 13.sp, lineHeight = 20.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { about = false }) { Text("关闭") }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyState(onPick: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        AssistantAvatar(76)
        Spacer(Modifier.height(16.dp))
        Text("你好，我是小渠", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "多路搜索引擎 + 实时联网\n把全网的答案整理成一句人话给你\n问时间、问天气，秒答不用搜",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 20.sp
        )
        Spacer(Modifier.height(22.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.widthIn(max = 340.dp)
        ) {
            sampleQuestions.forEach { q ->
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        q, fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clickable { onPick(q) }
                            .padding(horizontal = 13.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun InputBar(hint: String, onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    fun submit() {
        val t = text.trim()
        if (t.isEmpty()) return
        text = ""
        onSend(t)
    }
    Surface(tonalElevation = 3.dp, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.navigationBarsPadding().imePadding()) {
            Row(
                Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp, max = 120.dp),
                    placeholder = { Text(hint, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    shape = RoundedCornerShape(24.dp),
                    maxLines = 4,
                    textStyle = TextStyle(fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submit() })
                )
                Spacer(Modifier.size(8.dp))
                Box(
                    Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable { submit() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send, "发送",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(21.dp)
                    )
                }
            }
        }
    }
}
