package com.example.xiangsugame.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.xiangsugame.api.dto.toModel
import com.example.xiangsugame.auth.AuthManager
import com.example.xiangsugame.battle.BattlePhase
import com.example.xiangsugame.battle.BattleRole
import com.example.xiangsugame.battle.BattleSession
import com.example.xiangsugame.battle.BluetoothBattleTransport
import com.example.xiangsugame.data.AccountStore
import com.example.xiangsugame.model.GameMode
import com.example.xiangsugame.model.Level
import com.example.xiangsugame.model.Levels
import com.example.xiangsugame.ui.theme.Amber
import com.example.xiangsugame.ui.theme.Cocoa
import com.example.xiangsugame.ui.theme.Coral
import com.example.xiangsugame.ui.theme.Cyan
import com.example.xiangsugame.ui.theme.Ink

private enum class BtStage { ROLE, DEVICE_PICK, ROOM }

/**
 * 蓝牙对战(双机直连):主机等待被连接并选题发车,客机选设备连接。
 * 连接后与网络对战共用 BattleSession + 帧协议;胜负由双方对称校验。
 */
@Composable
fun BluetoothBattleRoot(
    onExit: () -> Unit,
    account: AccountStore?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val myId = AuthManager.session?.userId ?: -1
    val myNick = AuthManager.session?.nickname ?: "玩家$myId"
    var stage by remember { mutableStateOf(BtStage.ROLE) }
    var hostRole by remember { mutableStateOf(false) }
    var session by remember { mutableStateOf<BattleSession?>(null) }
    var transport by remember { mutableStateOf<BluetoothBattleTransport?>(null) }
    var permissionMsg by remember { mutableStateOf<String?>(null) }
    var showPick by remember { mutableStateOf(false) }
    var peerName by remember { mutableStateOf<String?>(null) }

    // —— 会话动作(值闭包,供结果回调与按钮共用)——
    var startHostAccept: () -> Unit = {}
    startHostAccept = {
        stage = BtStage.ROOM
        val t = BluetoothBattleTransport(BattleRole.HOST, null, myId, myNick)
        transport = t
        val s = BattleSession(t, BattleRole.HOST, myId, roomTag = "蓝牙主机")
        session = s
        s.open()
    }
    val connectToDevice: (BluetoothDevice, String) -> Unit = { dev, name ->
        stage = BtStage.ROOM
        peerName = name
        val t = BluetoothBattleTransport(BattleRole.GUEST, dev, myId, myNick)
        transport = t
        val s = BattleSession(t, BattleRole.GUEST, myId, roomTag = "蓝牙客机")
        session = s
        s.open()
    }
    val teardown: () -> Unit = {
        session?.close()
        session = null
        transport = null
        stage = BtStage.ROLE
    }

    // —— 权限(31+ 运行时;旧版安装时已授)+ 可被发现 ——
    val discoverIntent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
        putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)
    }
    val discoverableLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { startHostAccept() }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (hostRole) {
            // 主机:ADVERTISE + CONNECT;通过后进"可被发现"
            if (result[Manifest.permission.BLUETOOTH_ADVERTISE] == true &&
                result[Manifest.permission.BLUETOOTH_CONNECT] == true
            ) discoverableLauncher.launch(discoverIntent) else permissionMsg = "需要蓝牙权限才能开启对战"
        } else {
            // 客机:SCAN + CONNECT
            if (result[Manifest.permission.BLUETOOTH_SCAN] == true &&
                result[Manifest.permission.BLUETOOTH_CONNECT] == true
            ) stage = BtStage.DEVICE_PICK else permissionMsg = "需要蓝牙权限才能搜索设备"
        }
    }
    fun ensureBluetoothOn(): Boolean {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return false
        return adapter.isEnabled
    }

    DisposableEffect(Unit) { onDispose { session?.close() } }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding(),
    ) {
        when (stage) {
            BtStage.ROLE -> {
                ScreenTopBar(title = "蓝牙对战", subtitle = "双机近场直连 · 无需网络", onBack = onExit)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                ) {
                    Text(
                        "玩法:主机选题并等待,客机在设备列表里点主机 → 双方同一道题竞速。\n" +
                            "胜负有双方对称校验,无需服务器 —— 适合同一教室/宿舍开黑。",
                        fontSize = 12.sp, color = Cocoa, lineHeight = 18.sp,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .clickable {
                                hostRole = true
                                if (!ensureBluetoothOn()) {
                                    permissionMsg = "请先在系统设置中打开蓝牙"
                                } else if (Build.VERSION.SDK_INT >= 31) {
                                    permLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.BLUETOOTH_ADVERTISE,
                                            Manifest.permission.BLUETOOTH_CONNECT,
                                        ),
                                    )
                                } else {
                                    discoverableLauncher.launch(discoverIntent)
                                }
                            },
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, Coral.copy(alpha = 0.4f)),
                    ) {
                        Row(modifier = Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("🎮", fontSize = 26.sp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("作为主机开局", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = Ink)
                                Text("等待好友连接,由我选关", fontSize = 12.sp, color = Cocoa)
                            }
                            Text("▶", color = Coral, fontSize = 16.sp)
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .clickable {
                                hostRole = false
                                if (!ensureBluetoothOn()) {
                                    permissionMsg = "请先在系统设置中打开蓝牙"
                                } else if (Build.VERSION.SDK_INT >= 31) {
                                    permLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.BLUETOOTH_SCAN,
                                            Manifest.permission.BLUETOOTH_CONNECT,
                                        ),
                                    )
                                } else {
                                    stage = BtStage.DEVICE_PICK
                                }
                            },
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, Cyan.copy(alpha = 0.4f)),
                    ) {
                        Row(modifier = Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("📱", fontSize = 26.sp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("作为客机加入", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = Ink)
                                Text("从设备列表选主机,同题竞速", fontSize = 12.sp, color = Cocoa)
                            }
                            Text("▶", color = Cyan, fontSize = 16.sp)
                        }
                    }
                    Text(
                        "提示:首次请先让两台设备在系统蓝牙里配对一次,\n对战走经典蓝牙 RFCOMM,无需在同一 Wi-Fi。",
                        fontSize = 11.sp, color = Cocoa, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 14.dp),
                    )
                }
            }

            BtStage.DEVICE_PICK -> DevicePicker(
                onBack = { stage = BtStage.ROLE },
                onPick = { dev, name -> connectToDevice(dev, name) },
                onMessage = { permissionMsg = it },
            )

            BtStage.ROOM -> {
                val s = session
                val t = transport
                if (s == null || t == null) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        CircularProgressIndicator()
                    }
                } else {
                    BtRoomBody(
                        session = s,
                        transport = t,
                        account = account,
                        onExit = { teardown() },
                        onPickPuzzle = { showPick = true },
                    )
                }
            }
        }
    }

    permissionMsg?.let {
        AlertDialog(
            onDismissRequest = { permissionMsg = null },
            title = { Text("蓝牙对战") },
            text = { Text(it) },
            confirmButton = { TextButton(onClick = { permissionMsg = null }) { Text("知道了") } },
        )
    }

    if (showPick) {
        PickPuzzleDialog(
            onPick = { level ->
                showPick = false
                transport?.sendRaceStart(level)
            },
            onDismiss = { showPick = false },
        )
    }
}

// ---------------- 客机设备选择 ----------------

@Composable
private fun DevicePicker(
    onBack: () -> Unit,
    onPick: (BluetoothDevice, String) -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    var bonded by remember { mutableStateOf(listOf<Pair<BluetoothDevice, String>>()) }
    var found by remember { mutableStateOf(listOf<Pair<BluetoothDevice, String>>()) }
    var scanning by remember { mutableStateOf(false) }
    val receiver = remember {
        object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action == BluetoothDevice.ACTION_FOUND) {
                    val dev = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    if (dev != null) {
                        val name = dev.name ?: dev.address
                        found = (found + (dev to name)).distinctBy { it.first.address }
                    }
                }
            }
        }
    }

    fun loadBonded() {
        runCatching {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return@runCatching
            bonded = adapter.bondedDevices
                .filter { it.name != null && it.type != BluetoothDevice.DEVICE_TYPE_LE }
                .map { it to (it.name ?: it.address) }
        }
    }

    LaunchedEffect(Unit) { loadBonded() }
    DisposableEffect(Unit) {
        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        context.registerReceiver(receiver, filter)
        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
            runCatching { BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery() }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenTopBar(title = "选择主机设备", subtitle = "让主机开启 120 秒可被发现后点搜索", onBack = onBack)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = {
                    scanning = true
                    found = emptyList()
                    runCatching {
                        BluetoothAdapter.getDefaultAdapter()?.startDiscovery()
                    }.onFailure { scanning = false }
                },
                enabled = !scanning,
                modifier = Modifier.weight(1f),
            ) { Text(if (scanning) "搜索中…" else "🔍 搜索附近设备") }
            TextButton(onClick = { loadBonded() }) { Text("刷新已配对") }
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val list = found.ifEmpty { bonded }
            if (list.isEmpty()) {
                item {
                    Text(
                        if (scanning) "正在搜索…请在主机上保持本页" else "暂无设备 —— 点「搜索附近设备」," +
                            "并确认主机已开启可被发现",
                        fontSize = 12.sp, color = Cocoa,
                        modifier = Modifier.padding(top = 30.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                item { Text("共 ${list.size} 台设备", fontSize = 12.sp, color = Cocoa) }
                items(list, key = { it.first.address }) { (dev, name) ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { onPick(dev, name) },
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, Color(0x1FFFFFFF)),
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("📱", fontSize = 20.sp)
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(name, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Ink)
                                Text(dev.address, fontSize = 11.sp, color = Cocoa)
                            }
                            Text("连接 ▶", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Cyan)
                        }
                    }
                }
            }
        }
    }
}

// ---------------- 蓝牙房间 / 竞速 / 结算 ----------------

@Composable
private fun BtRoomBody(
    session: BattleSession,
    transport: BluetoothBattleTransport,
    account: AccountStore?,
    onExit: () -> Unit,
    onPickPuzzle: () -> Unit,
) {
    session.fatal?.let {
        AlertDialog(
            onDismissRequest = onExit,
            title = { Text("对局中断") },
            text = { Text(it) },
            confirmButton = { TextButton(onClick = onExit) { Text("返回") } },
        )
        return
    }

    when (session.phase) {
        BattlePhase.LOBBY -> {
            val opponent = session.players.firstOrNull { it.userId != session.myUserId }
            Column(modifier = Modifier.fillMaxSize()) {
                ScreenTopBar(
                    title = "蓝牙对战房间",
                    subtitle = if (session.isHost) {
                        "等待好友连接…(对方屏幕上点你的名字)"
                    } else {
                        opponent?.let { "已连接 ${it.nickname}" } ?: "连接中…"
                    },
                    onBack = onExit,
                )
                Spacer(modifier = Modifier.height(10.dp))
                if (opponent == null) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            Text(
                                if (session.isHost) "正在等待蓝牙连接(最长 90 秒)…"
                                else "正在连接主机…",
                                fontSize = 13.sp, color = Cocoa,
                                modifier = Modifier.padding(top = 10.dp),
                            )
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                    ) {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, Color(0x1FFFFFFF)),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row {
                                    Text("🎮 我", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Coral)
                                    Spacer(modifier = Modifier.weight(1f))
                                    Text("⚔", fontSize = 14.sp)
                                    Spacer(modifier = Modifier.weight(1f))
                                    Text("📱 ${opponent.nickname}", fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold, color = Cyan)
                                }
                                Text(
                                    if (session.isHost) "已连接!选一道题发车,双方同题竞速"
                                    else "已连接!等待主机选题发车",
                                    fontSize = 12.sp, color = Cocoa,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        if (session.isHost) {
                            Button(
                                onClick = onPickPuzzle,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Coral),
                            ) { Text("🎯 选关并发车", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(90.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(MaterialTheme.colorScheme.surface),
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                    Text("等待主机选题…", fontSize = 12.sp, color = Cocoa,
                                        modifier = Modifier.padding(top = 6.dp))
                                }
                            }
                        }
                    }
                }
            }
        }

        BattlePhase.RACING, BattlePhase.SETTLED -> {
            val level = session.raceLevel
            if (level == null) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    CircularProgressIndicator()
                }
            } else {
                BtRaceView(session, level, account, onExit)
            }
        }

        BattlePhase.CLOSED -> Box(modifier = Modifier.fillMaxSize()) {}
    }

    session.notice?.let {
        AlertDialog(
            onDismissRequest = { session.consumeNotice() },
            title = { Text("提示") },
            text = { Text(it) },
            confirmButton = { TextButton(onClick = { session.consumeNotice() }) { Text("知道了") } },
        )
    }
}

@Composable
private fun BtRaceView(
    session: BattleSession,
    level: Level,
    account: AccountStore?,
    onExit: () -> Unit,
) {
    val totalFilled = remember(level.id) {
        level.answerGrid.sumOf { row -> row.count { it == 1 } }
    }
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
                    "📶 ${level.name}",
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
                        session.mySubmitted -> "已提交 ✓"
                        else -> "竞速中…"
                    },
                    fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    color = if (session.phase == BattlePhase.SETTLED) Amber
                    else if (session.mySubmitted) Color(0xFF66BB6A) else Coral,
                )
            }
            session.players.forEach { p ->
                val mine = p.userId == session.myUserId
                val fraction = if (totalFilled == 0) 0f
                else (p.filled.toFloat() / totalFilled).coerceIn(0f, 1f)
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 2.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = if (mine) Coral.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surface,
                ) {
                    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                (if (mine) "🎮 " else "📱 ") + p.nickname + if (mine) "(你)" else "",
                                fontSize = 12.sp, fontWeight = FontWeight.ExtraBold,
                                color = if (mine) Coral else Cyan,
                                maxLines = 1, modifier = Modifier.weight(1f),
                            )
                            Text(
                                if (p.finishedRank > 0) "完成!" else "${p.filled}/$totalFilled",
                                fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                color = if (p.finishedRank > 0) Amber else Cocoa,
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(fraction)
                                    .height(4.dp)
                                    .background(if (mine) Coral else Cyan),
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
                    externalPaused = session.pausedForMe,
                    onSolved = { board, elapsed -> session.localFinish(board, elapsed) },
                    onProgress = { filled, elapsed -> session.reportProgress(filled, elapsed) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (session.pausedForMe && session.phase == BattlePhase.RACING) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x66000000)),
                contentAlignment = Alignment.Center,
            ) {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, Amber.copy(alpha = 0.5f)),
                ) {
                    Column(modifier = Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🏁", fontSize = 36.sp)
                        Text("对方已完成!", fontSize = 19.sp, fontWeight = FontWeight.Black, color = Ink)
                        Text("本局继续由对方胜出,回大厅再来一局", fontSize = 12.sp, color = Cocoa,
                            modifier = Modifier.padding(top = 4.dp))
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedButton(onClick = onExit) { Text("返回对战大厅") }
                    }
                }
            }
        }

        if (session.phase == BattlePhase.SETTLED && session.resultEntries.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xB3000000)),
                contentAlignment = Alignment.Center,
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(0.86f),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, Coral.copy(alpha = 0.4f)),
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        val my = session.resultEntries.firstOrNull { it.userId == session.myUserId }
                        Text(
                            if (my?.rank == 1) "🏆 蓝牙对局获胜!" else "🏁 蓝牙对局结束",
                            fontSize = 24.sp, fontWeight = FontWeight.Black, color = Ink,
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        session.resultEntries.forEach { e ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    if (e.rank == 1) "🥇" else "🥈",
                                    fontSize = 18.sp, modifier = Modifier.width(40.dp),
                                )
                                Text(
                                    e.nickname.ifEmpty { "玩家${e.userId}" } +
                                        if (e.userId == session.myUserId) "(你)" else "",
                                    fontSize = 15.sp,
                                    fontWeight = if (e.userId == session.myUserId) FontWeight.Black
                                    else FontWeight.SemiBold,
                                    color = Ink, modifier = Modifier.weight(1f),
                                )
                                Text(formatDuration(e.durationMs.toInt()), fontSize = 15.sp,
                                    fontWeight = FontWeight.Black,
                                    color = if (e.rank == 1) Amber else Ink)
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        GradientButton(text = "返回对战大厅", onClick = onExit, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}
