package com.example.xiangsugame.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.example.xiangsugame.api.ApiClient
import com.example.xiangsugame.auth.AuthManager
import com.example.xiangsugame.data.LocalSettings
import com.example.xiangsugame.model.Levels
import com.example.xiangsugame.ui.theme.Cocoa
import com.example.xiangsugame.ui.theme.Ink
import kotlinx.coroutines.launch

/**
 * 登录页 —— 一键"微信授权登录"(连服务器);连不上服务器可直接「游客进入」离线玩。
 * 登录成功 / 游客进入后由根导航监听 AuthManager.session 自动进首页。
 */
@Composable
fun LoginScreen(
    modifier: Modifier = Modifier,
) {
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showServerDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun doLogin() {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            AuthManager.loginMockWechat()
                .onFailure { error = it.message ?: "登录失败,请检查服务器连接" }
            busy = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF171531), Color(0xFF100E1C))))
            .systemBarsPadding()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // —— 品牌 Hero:渐变 + 光斑 + 像素画装饰 + 大标题 ——
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(bottomStart = 32.dp, bottomEnd = 32.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF7A68F2), Color(0xFF5647C9), Color(0xFF3B2E8F)),
                    ),
                ),
        ) {
            Box(
                modifier = Modifier
                    .size(150.dp)
                    .offset(x = (-40).dp, y = (-30).dp)
                    .background(Brush.radialGradient(listOf(Color(0x3DFFFFFF), Color.Transparent)), CircleShape),
            )
            Box(
                modifier = Modifier
                    .size(190.dp)
                    .align(Alignment.BottomEnd)
                    .offset(x = 50.dp, y = 60.dp)
                    .background(Brush.radialGradient(listOf(Color(0x33FFE29A), Color.Transparent)), CircleShape),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                FloatingPixelRow()
                Spacer(modifier = Modifier.height(22.dp))
                Text(
                    "像素填空",
                    fontSize = 42.sp,
                    fontWeight = FontWeight.Black,
                    color = Color.White,
                    letterSpacing = 2.sp,
                )
                Text(
                    "用数字线索还原像素画",
                    fontSize = 13.sp,
                    color = Color(0xFFE3DEFF),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        Column(
            modifier = Modifier.padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(30.dp))

            // —— 一键微信登录(需连服务器)——
            val wechatBrush = if (busy) {
                Brush.verticalGradient(listOf(Color(0xFF5AAE7C), Color(0xFF5AAE7C)))
            } else {
                Brush.horizontalGradient(listOf(Color(0xFF4CB964), Color(0xFF28A745)))
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(wechatBrush)
                    .clickable(enabled = !busy, onClick = { doLogin() }),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = Color.White,
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                } else {
                    Text("💬", fontSize = 20.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    if (busy) "正在登录…" else "微信授权登录",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Black,
                    color = Color.White,
                    letterSpacing = 1.sp,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // —— 游客进入(免登录,离线也能玩)——
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, Color(0x40FFFFFF), RoundedCornerShape(16.dp))
                    .clickable(enabled = !busy, onClick = { AuthManager.enterGuest() }),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text("👤", fontSize = 18.sp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "游客进入",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Ink,
                )
            }

            if (error != null) {
                Text(
                    "⚠ $error",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // —— 服务器设置入口 ——
            TextButton(onClick = { showServerDialog = true }) {
                Text(
                    "服务器设置 · ${ApiClient.serverHost()}",
                    fontSize = 12.sp,
                    color = Cocoa,
                )
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }

    if (showServerDialog) {
        ServerUrlDialog(onDismiss = { showServerDialog = false })
    }
}

/** 登录页的服务器设置弹窗。 */
@Composable
private fun ServerUrlDialog(onDismiss: () -> Unit) {
    var url by remember { mutableStateOf(LocalSettings.serverUrl) }
    var saved by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("服务器设置", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    singleLine = true,
                    label = { Text("服务器地址(含 http:// 和端口)") },
                    placeholder = { Text(LocalSettings.DEFAULT_SERVER_URL) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "模拟器保持默认即可;\n真机填电脑局域网 IP,如 http://192.168.1.8:8000/",
                    fontSize = 11.sp,
                    color = Cocoa,
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(top = 10.dp),
                )
                if (saved) {
                    Text(
                        "✓ 已保存",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF66BB6A),
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                LocalSettings.saveServerUrl(url)
                saved = true
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

/** 品牌 Hero 里三幅像素画的浮动行(心 / 笑脸 / 蝴蝶):玻璃相框 + 上下轻微浮动。 */
@Composable
private fun FloatingPixelRow() {
    val transition = rememberInfiniteTransition(label = "float")
    val float by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 2000), RepeatMode.Reverse),
        label = "floatPhase",
    )
    val y = ((float * 8f) - 4f).dp
    val patterns = remember { listOf(Levels.all[0].answerGrid, Levels.all[1].answerGrid, Levels.all[2].answerGrid) }
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        patterns.forEach { answer ->
            Box(
                modifier = Modifier
                    .offset(y = y)
                    .size(58.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0x2EFFFFFF))
                    .border(1.dp, Color(0x59FFFFFF), RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center,
            ) {
                PicturePreview(
                    answer = answer,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(7.dp),
                    filledColor = Color(0xFFF4F2FF),
                )
            }
        }
    }
}
