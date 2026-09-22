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
import android.provider.Settings
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
import androidx.core.content.ContextCompat
import com.example.xiangsugame.api.dto.toModel
import com.example.xiangsugame.battle.BattleIdentity
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
import kotlinx.coroutines.delay

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
    // 身份与登录解耦:登录用户用服务端 id;游客用本机派生的负值 —— 否则两端都是 -99(或同一个账号),
    // 系统会认为房间里只有一个人,连上了也永远等不到"对手"。详见 BattleIdentity。
    val myId = remember { BattleIdentity.localUserId(context) }
    val myNick = remember(myId) { BattleIdentity.localNickname(context, myId) }
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
    // 时长给足 300 秒:客机那边要过权限弹窗、搜索设备、可能还要先完成系统配对,
    // 120 秒经常不够,而窗口一过主机就彻底搜不到了。
    var discoverableLeft by remember { mutableStateOf(0) }
    val discoverIntent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
        putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
    }
    val discoverableLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        // ACTION_REQUEST_DISCOVERABLE 成功时 resultCode = 可被发现秒数;用户点「拒绝」是 RESULT_CANCELED。
        // 旧代码无条件 startHostAccept():用户拒绝后主机照样进"等待连接",而实际对方永远搜不到本机。
        if (result.resultCode > 0) {
            discoverableLeft = result.resultCode
            if (session == null) startHostAccept()
        } else if (session == null) {
            permissionMsg = "你拒绝了「允许附近的设备发现本机」,对方将搜不到你。" +
                "请重新点「作为主机开局」,并在系统弹窗里选「允许」。"
        } else {
            permissionMsg = "已取消:本机不再对外可见,对方可能搜不到你。"
        }
    }
    // 可被发现倒计时(归零后在等待页提示重新开启)
    LaunchedEffect(discoverableLeft) {
        while (discoverableLeft > 0) {
            delay(1000)
            discoverableLeft -= 1
        }
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (hostRole) {
            // 主机:ADVERTISE + CONNECT;通过后进"可被发现"
            if (result[Manifest.permission.BLUETOOTH_ADVERTISE] == true &&
                result[Manifest.permission.BLUETOOTH_CONNECT] == true
            ) discoverableLauncher.launch(discoverIntent) else permissionMsg = "需要蓝牙权限才能开启对战"
        } else if (Build.VERSION.SDK_INT < 31) {
            // 客机(Android 11 及以下):搜索附近设备要位置权限,没它 startDiscovery() 一条结果都不给。
            // 可以直接用「已配对」列表绕过,所以这里只提示、不拦死。
            if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
                stage = BtStage.DEVICE_PICK
            } else {
                permissionMsg = "安卓 11 及以下「搜索附近设备」需要位置权限,你拒绝了。\n" +
                    "两种做法:① 到系统设置里给本应用开启位置权限后重来;" +
                    "② 直接去「系统设置 → 蓝牙」与主机配对,再回来在「已配对」列表里点主机连接。"
                stage = BtStage.DEVICE_PICK
            }
        } else {
            // 客机(Android 12+):SCAN + CONNECT
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
                                    // Android 11 及以下:先要位置权限,否则搜不到任何设备
                                    permLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
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
                        discoverableLeft = discoverableLeft,
                        onRefreshDiscoverable = { discoverableLauncher.launch(discoverIntent) },
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
    /** 正在配对的设备名(非空时显示配对弹窗)。 */
    var pairing by remember { mutableStateOf<String?>(null) }
    var pendingDevice by remember { mutableStateOf<BluetoothDevice?>(null) }

    val reloadBonded: () -> Unit = {
        runCatching {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return@runCatching
            bonded = adapter.bondedDevices
                // 只排除 BLE-only 设备;名字为空的也用地址兜底显示
                .filter { it.type != BluetoothDevice.DEVICE_TYPE_LE }
                .map { it to (it.name ?: it.address) }
        }
        Unit
    }

    val receiver = remember {
        object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val dev = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        if (dev != null) {
                            val name = dev.name ?: dev.address
                            found = (found + (dev to name)).distinctBy { it.first.address }
                        }
                    }
                    // 没有这个分支时 scanning 永远是 true:按钮一直显示"搜索中…"且被禁用,
                    // 用户第二次点搜索根本没反应。
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> scanning = false
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        val dev = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        val state = intent.getIntExtra(
                            BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                        val target = pendingDevice
                        if (dev != null && target != null && dev.address == target.address) {
                            when (state) {
                                BluetoothDevice.BOND_BONDED -> {
                                    pairing = null
                                    pendingDevice = null
                                    reloadBonded()
                                    onPick(dev, dev.name ?: dev.address)  // 配对成功 → 直接连接
                                }
                                BluetoothDevice.BOND_NONE -> {
                                    pairing = null
                                    pendingDevice = null
                                    onMessage(
                                        "与「${dev.name ?: dev.address}」配对未完成。" +
                                            "请到「系统设置 → 蓝牙」里手动配对,再回来点连接。")
                                }
                                else -> Unit  // BOND_BONDING:继续等
                            }
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) { reloadBonded() }
    DisposableEffect(Unit) {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        // targetSdk 34+ 要求动态注册接收器显式声明导出标志。这里**必须用 EXPORTED**:
        // 蓝牙广播由蓝牙协议栈进程(com.android.bluetooth,独立 uid)发出,而 NOT_EXPORTED 的接收器
        // 只接收"同 uid 或 system uid"发来的广播 —— 用 NOT_EXPORTED 会一个 ACTION_FOUND、
        // 一个配对状态变化都收不到(现象:搜不到主机、配对成功也不自动连接),且完全静默。
        // 这三个 action 都是系统受保护广播,导出不会引入被第三方伪造的风险。
        runCatching {
            ContextCompat.registerReceiver(
                context, receiver, filter, ContextCompat.RECEIVER_EXPORTED,
            )
        }.onFailure { context.registerReceiver(receiver, filter) }  // 旧版 core 兜底
        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
            runCatching { BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery() }
        }
    }

    /** 点设备:未配对先走系统配对,配对成功再连接(RFCOMM 未配对时必然被拒)。 */
    fun pick(dev: BluetoothDevice, name: String) {
        if (dev.bondState == BluetoothDevice.BOND_BONDED) {
            onPick(dev, name)
            return
        }
        pendingDevice = dev
        pairing = name
        val started = runCatching { dev.createBond() }.getOrDefault(false)
        if (!started) {
            pairing = null
            pendingDevice = null
            onMessage(
                "无法自动发起配对。请到「系统设置 → 蓝牙」里与「$name」手动配对," +
                    "配对成功后再回来点「连接 ▶」。")
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenTopBar(title = "选择主机设备", subtitle = "主机需停在等待连接页;本页可配对", onBack = onBack)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = {
                    // Android 11 及以下:没有位置权限时 startDiscovery() 不抛异常、也不返回结果,
                    // 界面会永远停在"正在搜索…"。这里先自查一次,直接给出可操作的提示。
                    val needLocation = Build.VERSION.SDK_INT < 31 &&
                        ContextCompat.checkSelfPermission(
                            context, Manifest.permission.ACCESS_FINE_LOCATION,
                        ) != PackageManager.PERMISSION_GRANTED
                    if (needLocation) {
                        onMessage(
                            "搜索附近设备需要位置权限(安卓 11 及以下):请到「系统设置 → 应用 → 权限」" +
                                "里允许位置权限;或者直接用「刷新已配对」+ 系统蓝牙里配对。")
                        return@OutlinedButton
                    }
                    scanning = true
                    found = emptyList()
                    runCatching {
                        BluetoothAdapter.getDefaultAdapter()?.startDiscovery()
                    }.onFailure { scanning = false }
                },
                enabled = !scanning,
                modifier = Modifier.weight(1f),
            ) { Text(if (scanning) "搜索中…" else "🔍 搜索附近设备") }
            TextButton(onClick = reloadBonded) { Text("刷新已配对") }
            TextButton(onClick = {
                // 配对是 RFCOMM 的硬前提,系统设置页是最可靠的配对入口
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }) { Text("蓝牙设置") }
        }
        Text(
            "提示:先在这里点本机与主机的配对(或去系统蓝牙里配对),再点「连接 ▶」。" +
                "已配对的设备会置顶显示;主机必须停在「等待好友连接…」页面。",
            fontSize = 11.sp, color = Cocoa, lineHeight = 16.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 4.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 已配对 ∪ 已发现(已配对置顶)。
            // 旧代码是 `found.ifEmpty { bonded }`:只要搜到任何一台设备(耳机/电视/别人手机),
            // 已经配对好的主机就完全不显示了 —— 这正是"搜到了却连不上"的常见原因。
            val bondedMap = bonded.associateBy { it.first.address }
            val list = (bonded + found.filter { it.first.address !in bondedMap })
                .distinctBy { it.first.address }
            if (list.isEmpty()) {
                item {
                    Text(
                        if (scanning) {
                            "正在搜索…请确认主机已点「作为主机开局」并允许被本机发现"
                        } else {
                            "暂无设备。两种做法:\n" +
                                "① 到「系统设置 → 蓝牙」里与主机配对,回来后点「刷新已配对」;\n" +
                                "② 让主机点「作为主机开局」,再点「搜索附近设备」。"
                        },
                        fontSize = 12.sp, color = Cocoa,
                        modifier = Modifier.padding(top = 30.dp),
                        textAlign = TextAlign.Center,
                        lineHeight = 18.sp,
                    )
                }
            } else {
                item { Text("共 ${list.size} 台设备(已配对置顶)", fontSize = 12.sp, color = Cocoa) }
                items(list, key = { it.first.address }) { (dev, name) ->
                    val isBonded = bondedMap.containsKey(dev.address)
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { pick(dev, name) },
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, if (isBonded) Cyan.copy(alpha = 0.5f) else Color(0x1FFFFFFF)),
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(if (isBonded) "✅" else "📱", fontSize = 20.sp)
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(name, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Ink)
                                Text(
                                    dev.address + if (isBonded) " · 已配对" else " · 未配对(点它会先配对)",
                                    fontSize = 11.sp, color = Cocoa,
                                )
                            }
                            Text(
                                if (isBonded) "连接 ▶" else "配对并连接 ▶",
                                fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Cyan,
                            )
                        }
                    }
                }
            }
        }
    }

    pairing?.let { name ->
        AlertDialog(
            onDismissRequest = { pairing = null; pendingDevice = null },
            title = { Text("正在与「$name」配对") },
            text = {
                Text(
                    "两台手机会弹出系统配对框,请在两台手机上确认同一个 6 位配对码。\n" +
                        "配对成功后会自动开始连接;若没弹出或配对失败," +
                        "请到「系统设置 → 蓝牙」里手动配对,再回来点连接。",
                    fontSize = 13.sp, lineHeight = 19.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = { pairing = null; pendingDevice = null }) { Text("取消") }
            },
        )
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
    discoverableLeft: Int = 0,
    onRefreshDiscoverable: (() -> Unit)? = null,
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
                                if (session.isHost) "正在等待对方连接…(一直等,不超时)"
                                else "正在连接主机…",
                                fontSize = 13.sp, color = Cocoa,
                                modifier = Modifier.padding(top = 10.dp),
                            )
                            if (session.isHost) {
                                Text(
                                    "对方需要:①在「系统设置 → 蓝牙」里与本机配对;" +
                                        "②在本 App 里选「作为客机加入」并点本机名字",
                                    fontSize = 11.sp, color = Cocoa, lineHeight = 16.sp,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(top = 8.dp, start = 30.dp, end = 30.dp),
                                )
                                Spacer(modifier = Modifier.height(14.dp))
                                Text(
                                    if (discoverableLeft > 0) {
                                        "本机可被附近设备发现:剩余 $discoverableLeft 秒"
                                    } else {
                                        "⚠ 本机已不再对外可见,对方搜不到你"
                                    },
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (discoverableLeft > 0) Cocoa else MaterialTheme.colorScheme.error,
                                )
                                if (onRefreshDiscoverable != null) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    OutlinedButton(onClick = onRefreshDiscoverable) {
                                        Text("重新开启可被发现(300 秒)")
                                    }
                                }
                            }
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

        // 连接已关闭(连接超时/对方断开/发送失败):以前这里渲染一个空 Box,
        // 用户只能看到白屏、只能靠系统返回键脱身 —— 现在给明确的说明和出口。
        BattlePhase.CLOSED -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("📶", fontSize = 40.sp)
                Text(
                    if (session.raceLevel != null) "对局已结束" else "蓝牙连接已断开",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Ink,
                    modifier = Modifier.padding(top = 10.dp),
                )
                Text(
                    "连接已关闭。若是等待超时,请确认两台手机已完成系统级蓝牙配对、" +
                        "且主机已开启「可被发现」;具体原因见上方弹窗。",
                    fontSize = 12.sp,
                    color = Cocoa,
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Spacer(modifier = Modifier.height(18.dp))
                Button(
                    onClick = onExit,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("返回对战大厅") }
            }
        }
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
                    onSolved = { board, elapsed -> session.localFinish(board, elapsed) },
                    onProgress = { filled, elapsed -> session.reportProgress(filled, elapsed) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // 「对方完成」提示 —— 非阻断:对方名次已定,我继续做完自己的盘面,
        // 双端都完成后由对称校验给出最终排名(不再强制退出本局)。
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
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("🏁", fontSize = 14.sp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        when {
                            session.mySubmitted -> "你已完成,等对方做完"
                            else -> "${first.nickname} 第${first.finishedRank}名完成 · 你继续加油!"
                        },
                        fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Ink,
                        modifier = Modifier.weight(1f),
                    )
                    if (session.racingCount > 0) {
                        Text("还剩 ${session.racingCount} 人", fontSize = 11.sp,
                            fontWeight = FontWeight.Bold, color = Cocoa)
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
