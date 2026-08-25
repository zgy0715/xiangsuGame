package com.example.xiangsugame.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.xiangsugame.model.Levels
import com.example.xiangsugame.model.UserRole
import com.example.xiangsugame.ui.theme.Amber
import com.example.xiangsugame.ui.theme.Cocoa
import com.example.xiangsugame.ui.theme.Coral
import com.example.xiangsugame.ui.theme.Ink

/** 管理员默认密码（课程项目固定，README 注明）。 */
private const val ADMIN_PASSWORD = "admin"

/**
 * 登录页 —— 游戏开场页 + 双身份权限系统入口。
 *
 * 上半部分是品牌 Hero（靛紫渐变 + 真实关卡图案的像素画装饰 + 大标题），
 * 下半部分是两个身份卡片：
 *  - 玩家：点一下即进入，顺序解锁、无特权；
 *  - 管理员：需输入密码（默认 admin），全局解锁全部关卡（含隐藏关）。
 * 回调 [onLogin] 把所选身份交给上层决定后续导航。
 */
@Composable
fun LoginScreen(
    onLogin: (UserRole) -> Unit,
    modifier: Modifier = Modifier,
) {
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    val bg = Brush.verticalGradient(listOf(Color(0xFFF3F0FF), Color(0xFFF7F5FF)))

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(bg)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // —— 品牌 Hero：渐变 + 像素画装饰 + 大标题 ——
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(bottomStart = 30.dp, bottomEnd = 30.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF8A6CF9), Color(0xFF5E5CE6), Color(0xFF4A3B9E)),
                    ),
                ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 30.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 三幅真实关卡图案的像素画（心 / 笑脸 / 蝴蝶），带轻微浮动动画
                FloatingPixelRow()
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    "像素填空",
                    fontSize = 42.sp,
                    fontWeight = FontWeight.Black,
                    color = Color.White,
                )
                Text(
                    "Fill-a-Pix · 用数字线索还原像素画",
                    fontSize = 14.sp,
                    color = Color(0xFFE3DEFF),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        Column(modifier = Modifier.padding(horizontal = 24.dp)) {
            Text(
                "选择你的身份进入",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = Ink,
                modifier = Modifier.padding(top = 26.dp, bottom = 14.dp),
            )

            // —— 玩家身份卡：点一下即进 ——
            RoleCard(
                emoji = "👤",
                title = "玩家",
                desc = "顺序解锁 · 记录进度 · 无特权",
                accent = Coral,
                onClick = { onLogin(UserRole.PLAYER) },
            )

            Spacer(modifier = Modifier.height(14.dp))

            // —— 管理员身份卡：需密码 ——
            AdminCard(
                password = password,
                error = error,
                onPasswordChange = {
                    password = it
                    error = false
                },
                onLogin = {
                    if (password == ADMIN_PASSWORD) {
                        onLogin(UserRole.ADMIN)
                    } else {
                        error = true
                    }
                },
            )

            Spacer(modifier = Modifier.height(24.dp))
            Text(
                "课程项目 · 双身份权限系统",
                fontSize = 11.sp,
                color = Cocoa,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

/** 品牌 Hero 里三幅像素画的浮动行（心 / 笑脸 / 蝴蝶），上下轻微浮动增加生命力。 */
@Composable
private fun FloatingPixelRow() {
    val transition = rememberInfiniteTransition(label = "float")
    val float by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        // 往返动画：上下轻轻浮动，避免 Restart 模式每 2 秒"跳回"一次
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
                    .background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                PicturePreview(
                    answer = answer,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(7.dp),
                    filledColor = Color(0xFF5E5CE6),
                )
            }
        }
    }
}

/** 玩家 / 管理员通用身份卡：图标徽章 + 标题说明 + 右侧进入胶囊。 */
@Composable
private fun RoleCard(
    emoji: String,
    title: String,
    desc: String,
    accent: Color,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp, pressedElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(54.dp),
                shape = RoundedCornerShape(16.dp),
                color = accent.copy(alpha = 0.14f),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(emoji, fontSize = 28.sp)
                }
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Ink,
                )
                Text(
                    desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = Cocoa,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Surface(
                shape = RoundedCornerShape(50),
                color = accent,
                contentColor = Color.White,
            ) {
                Text(
                    "进入 ▶",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
        }
    }
}

/** 管理员身份卡：金色徽章 + 密码输入 + 登录按钮。 */
@Composable
private fun AdminCard(
    password: String,
    error: Boolean,
    onPasswordChange: (String) -> Unit,
    onLogin: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(54.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = Amber.copy(alpha = 0.16f),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("👑", fontSize = 28.sp)
                    }
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "管理员",
                        fontSize = 19.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Ink,
                    )
                    Text(
                        "全局解锁全部关卡（含隐藏关 64×64）",
                        style = MaterialTheme.typography.bodySmall,
                        color = Cocoa,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            OutlinedTextField(
                value = password,
                onValueChange = onPasswordChange,
                label = { Text("管理员密码") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                isError = error,
                modifier = Modifier.fillMaxWidth(),
            )
            if (error) {
                Text(
                    "密码错误，请重试",
                    color = Color(0xFFD32F2F),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onLogin,
                colors = ButtonDefaults.buttonColors(containerColor = Amber),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("以管理员身份进入", fontWeight = FontWeight.Bold)
            }
        }
    }
}
