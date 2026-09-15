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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.xiangsugame.api.ApiClient
import com.example.xiangsugame.auth.AuthManager
import com.example.xiangsugame.auth.LoginRules
import com.example.xiangsugame.data.LocalSettings
import com.example.xiangsugame.model.Levels
import com.example.xiangsugame.service.SoundEffectManager
import com.example.xiangsugame.ui.theme.Cocoa
import com.example.xiangsugame.ui.theme.Coral
import com.example.xiangsugame.ui.theme.Ink
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 登录流程所处的步骤。 */
private enum class LoginStep { EMAIL, CODE }

/**
 * 登录页 —— 邮箱 + 6 位验证码两步式:
 *   ① 输入邮箱 → 请求发码(服务端 SMTP 发信,未配置则打印在服务端控制台)
 *   ② 填入 6 位码 → 校验通过即签发 token → 根导航自动进首页
 * 连不上服务器时可直接「游客进入」离线玩内置关。
 */
@Composable
fun LoginScreen(
    modifier: Modifier = Modifier,
) {
    var step by remember { mutableStateOf(LoginStep.EMAIL) }
    var email by remember { mutableStateOf(AuthManager.lastEmail) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var devCode by remember { mutableStateOf<String?>(null) }
    var countdown by remember { mutableStateOf(0) }
    var showServerDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 重发倒计时(服务端 60 秒重发间隔,本地同步显示)
    LaunchedEffect(step, countdown) {
        if (step == LoginStep.CODE && countdown > 0) {
            delay(1000)
            countdown = LoginRules.tick(countdown)
        }
    }

    fun sendCode(isResend: Boolean) {
        if (busy) return
        AuthManager.validateEmail(email)?.let { error = it; return }
        SoundEffectManager.click()
        busy = true
        error = null
        scope.launch {
            AuthManager.requestEmailCode(email)
                .onSuccess { resp ->
                    step = LoginStep.CODE
                    code = ""
                    countdown = resp.resendAfterSeconds
                    devCode = resp.devCode
                    notice = if (resp.delivered) {
                        "验证码已发送至 ${resp.email},请查收(含垃圾箱)"
                    } else if (resp.devCode != null) {
                        "演示模式:服务器未配置 SMTP,验证码已显示在下方"
                    } else {
                        "服务器未配置 SMTP,验证码已打印在服务端控制台"
                    }
                    if (isResend) notice = "已重新发送,请使用最新收到的验证码"
                }
                .onFailure { error = it.message ?: "发送失败,请检查服务器连接" }
            busy = false
        }
    }

    fun doLogin() {
        if (busy) return
        if (!LoginRules.canSubmit(code)) {
            error = "请输入 6 位数字验证码"
            return
        }
        SoundEffectManager.click()
        busy = true
        error = null
        scope.launch {
            AuthManager.loginWithEmailCode(email, code)
                .onSuccess { SoundEffectManager.win() }
                .onFailure { error = it.message ?: "登录失败,请重试" }
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
            Spacer(modifier = Modifier.height(26.dp))

            when (step) {
                LoginStep.EMAIL -> {
                    Text(
                        "邮箱验证码登录",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Black,
                        color = Ink,
                        letterSpacing = 1.sp,
                    )
                    Text(
                        "输入邮箱,我们会发送 6 位验证码;首次登录自动注册",
                        fontSize = 12.sp,
                        color = Cocoa,
                        textAlign = TextAlign.Center,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(top = 6.dp, bottom = 8.dp),
                    )
                    Text(
                        "服务器未配 SMTP 时,验证码会直接显示在下一步(演示模式)",
                        fontSize = 10.sp,
                        color = Cocoa,
                        textAlign = TextAlign.Center,
                        lineHeight = 14.sp,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )

                    OutlinedTextField(
                        value = email,
                        onValueChange = {
                            email = it.trim()
                            error = null
                        },
                        singleLine = true,
                        label = { Text("邮箱") },
                        placeholder = { Text("you@example.com") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        isError = error != null,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    PrimaryButton(
                        text = if (busy) "正在发送…" else "获取验证码",
                        enabled = !busy,
                        busy = busy,
                        onClick = { sendCode(isResend = false) },
                    )
                }

                LoginStep.CODE -> {
                    Text(
                        "输入验证码",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Black,
                        color = Ink,
                        letterSpacing = 1.sp,
                    )
                    Text(
                        "已发送至 ${email.trim()}",
                        fontSize = 12.sp,
                        color = Cocoa,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    TextButton(
                        onClick = {
                            SoundEffectManager.click()
                            step = LoginStep.EMAIL
                            code = ""
                            error = null
                            notice = null
                        },
                        enabled = !busy,
                    ) { Text("← 换个邮箱", fontSize = 12.sp, color = Coral) }

                    OutlinedTextField(
                        value = code,
                        onValueChange = { input ->
                            val digits = LoginRules.formatCode(input)
                            code = digits
                            error = null
                            // 填满 6 位自动提交,省一次点击
                            if (LoginRules.canSubmit(digits) && !busy) doLogin()
                        },
                        singleLine = true,
                        label = { Text("6 位验证码") },
                        placeholder = { Text("______") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        textStyle = TextStyle(
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 8.sp,
                            textAlign = TextAlign.Center,
                            color = Ink,
                        ),
                        isError = error != null,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (devCode != null) {
                        // 演示模式:服务端未配 SMTP 时自动回显验证码,用户无需看控制台即可登录
                        Text(
                            "演示模式验证码",
                            fontSize = 11.sp,
                            color = Cocoa,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                        Text(
                            devCode!!,
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 6.sp,
                            color = Coral,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                        Text(
                            "配置 SMTP 后此提示自动消失",
                            fontSize = 10.sp,
                            color = Cocoa,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    PrimaryButton(
                        text = if (busy) "正在登录…" else "登录",
                        enabled = !busy && LoginRules.canSubmit(code),
                        busy = busy,
                        onClick = { doLogin() },
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    TextButton(
                        onClick = { sendCode(isResend = true) },
                        enabled = !busy && LoginRules.canResend(countdown),
                    ) {
                        Text(
                            LoginRules.resendLabel(countdown),
                            fontSize = 12.sp,
                            color = if (LoginRules.canResend(countdown)) Coral else Cocoa,
                        )
                    }
                }
            }

            notice?.let {
                Text(
                    "✓ $it",
                    fontSize = 12.sp,
                    color = Color(0xFF66BB6A),
                    textAlign = TextAlign.Center,
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            error?.let {
                Text(
                    "⚠ $it",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            // —— 游客进入(免登录,离线也能玩)——
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, Color(0x40FFFFFF), RoundedCornerShape(16.dp))
                    .clickable(enabled = !busy, onClick = { SoundEffectManager.click(); AuthManager.enterGuest() }),
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

            Spacer(modifier = Modifier.height(14.dp))

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

/** 主操作按钮(品牌渐变,忙碌时显示进度)。 */
@Composable
private fun PrimaryButton(
    text: String,
    enabled: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
) {
    val brush = if (enabled) {
        Brush.horizontalGradient(listOf(Color(0xFF7A68F2), Color(0xFF5647C9)))
    } else {
        Brush.horizontalGradient(listOf(Color(0xFF4A4470), Color(0xFF3B3659)))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(brush)
            .clickable(enabled = enabled, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = Color.White,
                strokeWidth = 2.dp,
            )
            Spacer(modifier = Modifier.width(10.dp))
        }
        Text(
            text,
            fontSize = 16.sp,
            fontWeight = FontWeight.Black,
            color = Color.White,
            letterSpacing = 1.sp,
        )
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
