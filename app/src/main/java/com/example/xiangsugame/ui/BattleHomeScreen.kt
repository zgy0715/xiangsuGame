package com.example.xiangsugame.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.xiangsugame.AppGraph
import com.example.xiangsugame.api.ApiClient
import com.example.xiangsugame.api.dto.toModel
import com.example.xiangsugame.auth.AuthManager
import com.example.xiangsugame.battle.BattlePhase
import com.example.xiangsugame.battle.BattleProtocol
import com.example.xiangsugame.battle.BattleRole
import com.example.xiangsugame.battle.BattleSession
import com.example.xiangsugame.battle.NetworkBattleTransport
import com.example.xiangsugame.data.AccountStore
import com.example.xiangsugame.data.Dates
import com.example.xiangsugame.data.PuzzleRepository
import com.example.xiangsugame.model.GameMode
import com.example.xiangsugame.model.Level
import com.example.xiangsugame.model.Levels
import com.example.xiangsugame.ui.theme.Amber
import com.example.xiangsugame.ui.theme.Cocoa
import com.example.xiangsugame.ui.theme.Coral
import com.example.xiangsugame.ui.theme.Cyan
import com.example.xiangsugame.ui.theme.Ink
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class BattleView { MENU, NETWORK_SETUP, ROOM, BLUETOOTH }

/**
 * 对战大厅 —— 网络对战(服务器中转 2~4 人同题竞速)已接通;
 * 蓝牙双机直连在 P3 模块接入。整个对战流程由本页内部状态驱动,不污染主路由。
 */
@Composable
fun BattleHomeScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var view by remember { mutableStateOf(BattleView.MENU) }
    var session by remember { mutableStateOf<BattleSession?>(null) }
    val scope = rememberCoroutineScope()
    val myId = AuthManager.session?.userId ?: -1
    val account = if (myId > 0) AppGraph.account(myId) else null

    fun teardown() {
        session?.close()
        session = null
        view = BattleView.MENU
    }

    /** 断线重进:同一房号按原身份重建会话(服务器保留席位/进度,竞速中会补发题目)。 */
    fun reconnect() {
        val s = session ?: return
        if (!s.canReconnect) return
        val code = s.roomTag
        val role = s.role
        session?.close()
        val ns = BattleSession(NetworkBattleTransport(code), role, roomTag = code, canReconnect = true)
        session = ns
        ns.open()
    }
    DisposableEffect(Unit) { onDispose { session?.close() } }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding(),
    ) {
        when (view) {
            BattleView.MENU -> BattleMenu(
                onBack = onBack,
                onNetwork = { view = BattleView.NETWORK_SETUP },
            )

            BattleView.NETWORK_SETUP -> NetworkSetup(
                accountReady = session == null,
                onCreated = { code ->
                    val s = BattleSession(NetworkBattleTransport(code), BattleRole.HOST, roomTag = code, canReconnect = true)
                    session = s
                    s.open()
                    view = BattleView.ROOM
                },
                onJoined = { code ->
                    val s = BattleSession(NetworkBattleTransport(code), BattleRole.GUEST, roomTag = code, canReconnect = true)
                    session = s
                    s.open()
                    view = BattleView.ROOM
                },
                onBack = { view = BattleView.MENU },
            )

            BattleView.ROOM -> {
                val s = session
                if (s == null) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    BattleRoomContent(session = s, account = account, onExit = { teardown() }, onReconnect = { reconnect() })
                }
            }

            BattleView.BLUETOOTH -> BluetoothBattleRoot(
                account = account,
                onExit = { view = BattleView.MENU },
            )
        }
    }
}

// ---------------- 入口菜单 ----------------

@Composable
private fun BattleMenu(onBack: () -> Unit, onNetwork: () -> Unit) {
    ScreenTopBar(title = "对战大厅", subtitle = "同题竞速 · 先完成全对者胜", onBack = onBack)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
    ) {
        Text(
            "同题竞速:所有人拿到同一道题,实时可见彼此进度与用时;\n" +
                "第一个完整还原图案的人获胜 —— 网络对战由服务器校验计时,蓝牙对战双机对称校验。",
            fontSize = 12.sp, color = Cocoa, lineHeight = 18.sp,
            modifier = Modifier.padding(bottom = 16.dp),
        )
        EntryCard(
            emoji = "🌐", title = "网络对战", subtitle = "服务器建房/加房 · 2~4 人",
            accent = Coral, actionText = "进入 ▶", onClick = onNetwork,
        )
        Spacer(modifier = Modifier.height(12.dp))
        EntryCard(
            emoji = "📶", title = "蓝牙对战", subtitle = "双机近场直连 · 需两台真机",
            accent = Cyan, actionText = "需真机", onClick = null,
        )
        Spacer(modifier = Modifier.height(12.dp))
        // 说明为什么蓝牙在这里进不去:模拟器没有真实蓝牙硬件,RFCOMM 无法建连
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp)),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    "ℹ️ 蓝牙对战为什么点不了?",
                    fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Ink,
                )
                Text(
                    "蓝牙对战走 Classic RFCOMM 直连,必须有两台带真实蓝牙硬件的手机。\n" +
                        "模拟器(雷电 / Android Studio AVD)没有蓝牙适配器,拿不到 BluetoothAdapter,起不了监听套接字," +
                        "因此无法建连 —— 这是模拟器的限制,不是功能缺失。\n" +
                        "演示蓝牙请用两台真机近场配对;模拟器上请用「网络对战」(同一台服务器即可)。",
                    fontSize = 11.sp, color = Cocoa, lineHeight = 17.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            "提示:参与网络对战的所有设备需连到同一台服务器\n" +
                "· 可先在「设置」确认服务器地址",
            fontSize = 11.sp, color = Cocoa, textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun EntryCard(
    emoji: String, title: String, subtitle: String, accent: Color,
    actionText: String, onClick: (() -> Unit)?,
) {
    val enabled = onClick != null
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(enabled = enabled, onClick = onClick ?: {}),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, accent.copy(alpha = if (enabled) 0.4f else 0.15f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .background(accent.copy(alpha = 0.14f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) { Text(emoji, fontSize = 24.sp) }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = Ink)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Cocoa,
                    modifier = Modifier.padding(top = 3.dp))
            }
            Text(
                actionText,
                fontSize = 12.sp, fontWeight = FontWeight.Bold,
                color = if (enabled) accent else Cocoa,
                modifier = Modifier
                    .background(
                        if (enabled) accent.copy(alpha = 0.12f) else Color(0x14FFFFFF),
                        RoundedCornerShape(8.dp),
                    )
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }
}

// ---------------- 建房 / 加房 ----------------

private enum class SetupMode { CREATE, JOIN }

@Composable
private fun NetworkSetup(
    accountReady: Boolean,
    onCreated: (String) -> Unit,
    onJoined: (String) -> Unit,
    onBack: () -> Unit,
) {
    var mode by remember { mutableStateOf(SetupMode.CREATE) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    ScreenTopBar(title = "网络对战", subtitle = "房主创建房间,好友输房号加入", onBack = onBack)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp),
    ) {
        SegmentedControl(
            items = listOf(SetupMode.CREATE to "🏠 创建房间", SetupMode.JOIN to "🚪 加入房间"),
            selected = mode,
            onSelect = { mode = it; error = null },
        )
        Spacer(modifier = Modifier.height(18.dp))

        when (mode) {
            SetupMode.CREATE -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🏠", fontSize = 34.sp)
                        Text(
                            "创建后获得 4 位房号,分享给好友加入(最多 4 人)",
                            fontSize = 12.sp, color = Cocoa,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        if (busy) return@Button
                        busy = true
                        error = null
                        scope.launch {
                            ApiClient.safe { ApiClient.api().createRoom() }
                                .onSuccess { onCreated(it.roomId) }
                                .onFailure { error = it.message ?: "创建失败" }
                            busy = false
                        }
                    },
                    enabled = !busy && accountReady,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Coral),
                ) {
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                    } else {
                        Text("生成房间号并开房", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
            }

            SetupMode.JOIN -> {
                OutlinedTextField(
                    value = code,
                    onValueChange = {
                        if (it.length <= 4) { code = it.uppercase(); error = null }
                    },
                    singleLine = true,
                    label = { Text("房间号(4 位)") },
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        val c = code.trim()
                        when {
                            c.length != 4 -> error = "房间号是 4 位,如 XK9P"
                            else -> {
                                busy = true
                                error = null
                                scope.launch {
                                    // WS 直接连:不存在/已结束的房间会由传输层回调 fatal
                                    onJoined(c)
                                    busy = false
                                }
                            }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Coral),
                ) { Text("加入房间", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
            }
        }

        error?.let {
            Text(
                "⚠ $it",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            "服务器:${ApiClient.serverHost()} · 连不上请到「设置」改地址",
            fontSize = 11.sp, color = Cocoa,
        )
    }
}

// ---------------- 房间内 ----------------

@Composable
private fun BattleRoomContent(
    session: BattleSession,
    account: AccountStore?,
    onExit: () -> Unit,
    onReconnect: () -> Unit,
) {
    var showPickDialog by remember { mutableStateOf(false) }

    // 致命错误(房间解散/连接失败) → 弹窗;网络房可选「重新连接」(房主离线宽限期内/网络抖动)
    session.fatal?.let { fatalMsg ->
        AlertDialog(
            onDismissRequest = onExit,
            title = { Text("对局中断") },
            text = { Text(fatalMsg, color = Cocoa) },
            confirmButton = {
                if (session.canReconnect) {
                    TextButton(onClick = onReconnect) { Text("重新连接") }
                    TextButton(onClick = onExit) { Text("返回大厅") }
                } else {
                    TextButton(onClick = onExit) { Text("返回大厅") }
                }
            },
        )
        return
    }

    when (session.phase) {
        BattlePhase.LOBBY -> Column(modifier = Modifier.fillMaxSize()) {
            ScreenTopBar(
                title = "对战房间",
                subtitle = if (session.isHost) {
                    session.puzzleName?.let { "已选题:$it" }
                        ?: "房号 ${session.roomTag} · 分享给好友加入"
                } else {
                    session.puzzleName?.let { "题目已定:$it" } ?: "正在连接房间…"
                },
                onBack = onExit,
                action = {
                    GlassChip(if (session.isHost) "👑 房主" else "房客")
                },
            )
            if (session.players.isEmpty()) {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                        Text("等待房间状态同步…", fontSize = 12.sp, color = Cocoa,
                            modifier = Modifier.padding(top = 8.dp))
                    }
                }
            } else {
                LobbyPlayers(session)
                Spacer(modifier = Modifier.weight(1f))
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                if (session.isHost) {
                    OutlinedButton(
                        onClick = { showPickDialog = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp),
                    ) { Text(session.puzzleName?.let { "换题 · $it" } ?: "🎯 选择对战关卡") }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { session.requestStart() },
                        enabled = session.puzzleName != null &&
                            session.players.count { it.connected } >= 2,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Coral),
                    ) { Text("🚦 开始对战", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
                    Text(
                        when {
                            session.players.count { it.connected } < 2 ->
                                "等待第二位玩家加入…(已到 ${session.players.count { it.connected }} 人)"
                            session.puzzleName == null -> "选好题目即可发车"
                            else -> "人数就绪,点开始全员同题开跑"
                        },
                        fontSize = 11.sp, color = Cocoa, textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(88.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surface),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text(
                                if (session.puzzleName == null) "等待房主选题…" else "题目已定,等待房主发车…",
                                fontSize = 12.sp, color = Cocoa, modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                TextButton(onClick = onExit, modifier = Modifier.fillMaxWidth()) {
                    Text("退出房间", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        BattlePhase.RACING, BattlePhase.SETTLED -> {
            val level = session.raceLevel
            if (level == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                RaceView(session = session, level = level, account = account, onExit = onExit)
            }
        }

        BattlePhase.CLOSED -> {
            // 传输层断开(无致命错误,如服务器主动关闭)→ 网络房展示「重新连接」面板
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("📡", fontSize = 40.sp)
                Text("连接已断开", fontSize = 18.sp, fontWeight = FontWeight.Black, color = Ink)
                Text(
                    "房号 ${session.roomTag} · 席位为您保留,重连后可继续本局",
                    fontSize = 12.sp, color = Cocoa,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Spacer(modifier = Modifier.height(16.dp))
                if (session.canReconnect) {
                    Button(
                        onClick = onReconnect,
                        colors = ButtonDefaults.buttonColors(containerColor = Coral),
                    ) { Text("🔄 重新连接", fontWeight = FontWeight.Bold) }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                TextButton(onClick = onExit) { Text("退出房间", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }

    session.notice?.let { msg ->
        AlertDialog(
            onDismissRequest = { session.consumeNotice() },
            title = { Text("提示") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { session.consumeNotice() }) { Text("知道了") } },
        )
    }

    if (showPickDialog) {
        PickPuzzleDialog(
            onPick = { level ->
                showPickDialog = false
                session.choosePuzzle(level)
            },
            onDismiss = { showPickDialog = false },
        )
    }
}

@Composable
private fun LobbyPlayers(session: BattleSession) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
    ) {
        Text("房内玩家", style = MaterialTheme.typography.titleMedium, color = Ink)
        Spacer(modifier = Modifier.height(6.dp))
        session.players.forEach { p ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, Color(0x1FFFFFFF)),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (p.userId == session.hostId) "👑" else "👤", fontSize = 16.sp)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        p.nickname + if (p.userId == session.myUserId) "(你)" else "",
                        fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Ink,
                        modifier = Modifier.weight(1f),
                    )
                    if (p.userId == session.hostId) {
                        Text("房主 · 选题", fontSize = 11.sp, color = Amber, fontWeight = FontWeight.Bold)
                    } else {
                        Text("✓ 已就位", fontSize = 12.sp, color = Color(0xFF66BB6A))
                    }
                }
            }
        }
    }
}

/** 可选题项:内置关/每日一题/服务器随机题,loader 按需取完整关卡。 */
private data class PickOption(
    val label: String,
    val detail: String,
    val loader: suspend () -> Level?,
)

/** 选题对话框:内置关 + 服务器题目(每日一题 / 随机简单题);蓝牙对战也复用。 */
@Composable
fun PickPuzzleDialog(onPick: (Level) -> Unit, onDismiss: () -> Unit) {
    val builtins = remember {
        Levels.all.map { l -> PickOption(l.name, "内置 · ${l.difficulty.label} ${l.rows}×${l.cols}") { l } }
    }
    val online = remember {
        listOf(
            PickOption("每日一题", Dates.todayIso()) {
                PuzzleRepository.fetchDaily(Dates.todayIso()).getOrNull()?.level?.toModel()
            },
            PickOption("随机简单题", "服务器即时生成") {
                ApiClient.safe { ApiClient.api().bank(difficulty = "EASY", limit = 8) }
                    .getOrNull()?.items?.firstOrNull()
                    ?.let { PuzzleRepository.fetchPuzzle(it.id).getOrNull() }
            },
        )
    }
    var busyLabel by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (busyLabel == null) onDismiss() },
        title = { Text("选择对战关卡", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text("内置关(房主本地)", fontSize = 12.sp, color = Cocoa)
                builtins.forEach { opt ->
                    PickRow(opt, busyLabel == opt.label, enabled = busyLabel == null) {
                        busyLabel = opt.label
                        scope.launch {
                            opt.loader()?.let(onPick)
                            busyLabel = null
                        }
                    }
                }
                Text("服务器题目", fontSize = 12.sp, color = Cocoa, modifier = Modifier.padding(top = 10.dp))
                online.forEach { opt ->
                    PickRow(opt, busyLabel == opt.label, enabled = busyLabel == null) {
                        busyLabel = opt.label
                        scope.launch {
                            val level = opt.loader()
                            busyLabel = null
                            if (level != null) onPick(level)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun PickRow(opt: PickOption, loading: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(opt.label, fontSize = 14.sp, color = Ink, modifier = Modifier.weight(1f))
        Text(opt.detail, fontSize = 11.sp, color = Cocoa)
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(14.dp),
                strokeWidth = 1.5.dp,
            )
        }
    }
}

/** 竞速视图。 */
@Composable
private fun RaceView(
    session: BattleSession,
    level: Level,
    account: AccountStore?,
    onExit: () -> Unit,
) {
    val totalFilled = remember(level.id) {
        level.answerGrid.sumOf { row -> row.count { it == 1 } }
    }
    val myFinished = session.mySubmitted

    // 服务器权威计时显示:按 NTP 校时后的"服务器时间"重算本局用时(由服务器时钟驱动)
    var nowMs by remember { mutableLongStateOf(session.serverNowMs()) }
    LaunchedEffect(session.phase, session.serverStartMs) {
        while (session.phase == BattlePhase.RACING) {
            delay(1000)
            nowMs = session.serverNowMs()
        }
    }
    // 非房主点「返回房间」时关掉结算卡,留在房间页等房主重赛;
    // phase 切回 lobby(房主发 rematch 触发)或切到 RACING(新一局)时自动重置
    var settleDismissed by remember { mutableStateOf(false) }
    LaunchedEffect(session.phase) {
        // 进入新阶段(lobby/racing/settled)时清掉"已关结算卡"标记,避免下一局结算卡不显示
        settleDismissed = false
    }
    val raceElapsedMs = if (session.serverStartMs > 0) {
        (nowMs - session.serverStartMs).coerceAtLeast(0L)
    } else 0L

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    onClick = onExit,
                    modifier = Modifier.size(34.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) { Box(contentAlignment = Alignment.Center) { Text("✕", fontWeight = FontWeight.Bold) } }
                Text(
                    "⚔ ${level.name}",
                    style = MaterialTheme.typography.titleMedium,
                    color = Ink,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 10.dp),
                    maxLines = 1,
                )
                Text(
                    when {
                        session.phase == BattlePhase.SETTLED -> "结算"
                        myFinished -> "已提交 ✓"
                        session.serverStartMs > 0 -> formatDuration(raceElapsedMs.toInt())
                        else -> "竞速中…"
                    },
                    fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    color = if (session.phase == BattlePhase.SETTLED) Amber
                    else if (myFinished) Color(0xFF66BB6A) else Coral,
                )
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 116.dp),
                contentPadding = PaddingValues(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(session.players, key = { it.userId }) { p ->
                    PlayerRaceRow(p, totalFilled)
                }
            }
            // 「有人完成」提示 —— 非阻断:先完成者的名次实时播报,剩下的人继续做同一道题;
            // 全员完成后由服务器下发权威排行榜(见 BattleSession.finishedPlayers)。
            if (session.someoneFinished) {
                val first = session.finishedPlayers.first()
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = Amber.copy(alpha = 0.16f),
                    border = BorderStroke(1.dp, Amber.copy(alpha = 0.5f)),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("🏁", fontSize = 14.sp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                when {
                                    session.mySubmitted -> "你已完成,等其他人做完"
                                    else -> "${first.nickname} 第${first.finishedRank}名完成 · 你继续加油!"
                                },
                                fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Ink,
                                modifier = Modifier.weight(1f),
                            )
                            if (session.racingCount > 0) {
                                Text(
                                    "还剩 ${session.racingCount} 人",
                                    fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Cocoa,
                                )
                            }
                        }
                        if (session.finishedPlayers.size > 1) {
                            Text(
                                session.finishedPlayers.joinToString(" · ") {
                                    "${it.nickname} 第${it.finishedRank}名"
                                },
                                fontSize = 10.sp, color = Cocoa,
                                maxLines = 2,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }
            }
            if (account != null) {
                GameScreen(
                    level = level,
                    account = account,
                    mode = GameMode.FREE,
                    onBack = onExit,
                    showOverlays = false,
                    onSolved = { board, elapsed -> session.localFinish(board, elapsed) },
                    onProgress = { filled, elapsed -> session.reportProgress(filled, elapsed) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // 结算(非房主点「返回房间」后会隐藏结算卡,留在房间页等房主重赛)
        if (session.phase == BattlePhase.SETTLED && session.resultEntries.isNotEmpty() && !settleDismissed) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xB3000000)),
                contentAlignment = Alignment.Center,
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(0.88f),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, Coral.copy(alpha = 0.4f)),
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        val myEntry = session.resultEntries.firstOrNull { it.userId == session.myUserId }
                        Text(
                            if (myEntry?.rank == 1) "🏆 恭喜夺冠!" else "🏁 本局结束",
                            fontSize = 24.sp, fontWeight = FontWeight.Black, color = Ink,
                        )
                        Text(
                            "「${level.name}」同题竞速 · 用时由服务器计时",
                            fontSize = 12.sp, color = Cocoa,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        session.resultEntries.forEach { e ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    when (e.rank) { 1 -> "🥇"; 2 -> "🥈"; 3 -> "🥉"; else -> "${e.rank}." },
                                    fontSize = 18.sp, modifier = Modifier.width(42.dp),
                                )
                                Text(
                                    e.nickname.ifEmpty { "玩家${e.userId}" } +
                                        if (e.userId == session.myUserId) "(你)" else "",
                                    fontSize = 15.sp,
                                    fontWeight = if (e.userId == session.myUserId) FontWeight.Black
                                    else FontWeight.SemiBold,
                                    color = Ink, modifier = Modifier.weight(1f),
                                )
                                Text(
                                    formatDuration(e.durationMs.toInt()),
                                    fontSize = 15.sp, fontWeight = FontWeight.Black,
                                    color = if (e.rank == 1) Amber else Ink,
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        // 「返回房间」+「返回大厅」两个选项:
                        //   - 房主点返回房间 → 发 rematch,服务端把 phase 切回 lobby,全员回到房间待机页(保留房号与原房间)
                        //   - 非房主点返回房间 → 关掉结算卡,留在房间页等服务端广播 phase=lobby(房主点重赛后自动切)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            // 返回房间:保留原房号与房间,等房主重新选题发车
                            OutlinedButton(
                                onClick = {
                                    if (session.isHost) session.requestRematch()
                                    settleDismissed = true
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text("返回房间", fontWeight = FontWeight.Bold) }
                            // 返回大厅:断开 transport,需重新建房/加房
                            GradientButton(
                                text = "返回大厅",
                                onClick = onExit,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        // 提示成绩去向:名次与用时已由服务器入库,可在排行榜「对战榜」里查(需把题目切到本题)
                        Text(
                            "本局成绩已入榜 · 到排行榜「对战榜」把题目切到「${level.name}」即可查看",
                            fontSize = 10.sp, color = Cocoa, textAlign = TextAlign.Center,
                            lineHeight = 15.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                        )
                        if (!session.isHost) {
                            Text(
                                "返回房间后,需等房主重新选题发车",
                                fontSize = 10.sp, color = Cocoa, textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        }

        // 已在房间等下一局(点了「返回房间」之后):给明确状态 + 房号,避免停在一张冻结的棋盘上干等。
        // 连接一直保持,房主 rematch 广播 phase=lobby 后本页自动切回房间待机页。
        if (session.phase == BattlePhase.SETTLED && settleDismissed) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xCC000000)),
                contentAlignment = Alignment.Center,
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(0.86f),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, Cyan.copy(alpha = 0.4f)),
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("🛋️", fontSize = 34.sp)
                        Text("已在房间等待下一局", fontSize = 18.sp, fontWeight = FontWeight.Black, color = Ink)
                        Text(
                            session.puzzleName?.let { "上一局题目:${it}" } ?: "本局题目:${level.name}",
                            fontSize = 11.sp, color = Cocoa,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        if (session.isHost) {
                            Text(
                                "你是房主 · 房号 ${session.roomTag}",
                                fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Coral,
                                modifier = Modifier.padding(top = 10.dp),
                            )
                            Text(
                                "点下面按钮回到房间待机页,重新选题即可再来一局(房号不变)",
                                fontSize = 11.sp, color = Cocoa,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = { session.requestRematch() },
                                colors = ButtonDefaults.buttonColors(containerColor = Coral),
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("↩ 回到房间待机页", fontWeight = FontWeight.Bold) }
                        } else {
                            Text(
                                "房号 ${session.roomTag} · 席位已保留",
                                fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Cyan,
                                modifier = Modifier.padding(top = 10.dp),
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(top = 8.dp),
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Text(
                                    " 等房主重新选题发车…",
                                    fontSize = 12.sp, color = Cocoa,
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            OutlinedButton(
                                onClick = { settleDismissed = false },
                                modifier = Modifier.weight(1f),
                            ) { Text("查看结算", fontSize = 13.sp) }
                            OutlinedButton(
                                onClick = onExit,
                                modifier = Modifier.weight(1f),
                            ) { Text("返回大厅", fontSize = 13.sp) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerRaceRow(p: BattleProtocol.RacePlayer, totalFilled: Int) {
    val mine = p.userId == AuthManager.session?.userId
    val fraction = if (totalFilled == 0) 0f else (p.filled.toFloat() / totalFilled).coerceIn(0f, 1f)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = if (mine) Coral.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (mine) Coral.copy(alpha = 0.4f) else Color(0x14FFFFFF)),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    p.nickname + if (mine) "(你)" else "",
                    fontSize = 13.sp, fontWeight = FontWeight.ExtraBold,
                    color = if (mine) Coral else Ink,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                when {
                    p.finishedRank > 0 -> Text(
                        "已完成 第${p.finishedRank}名", fontSize = 11.sp,
                        fontWeight = FontWeight.Bold, color = Amber,
                    )
                    else -> Text("${p.filled}/$totalFilled", fontSize = 11.sp,
                        fontWeight = FontWeight.Bold, color = Cocoa)
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .height(5.dp)
                        .background(
                            if (p.finishedRank > 0) Brush.horizontalGradient(listOf(Amber, Color(0xFFFFE29A)))
                            else Brush.horizontalGradient(listOf(Coral, Cyan)),
                        ),
                )
            }
        }
    }
}
