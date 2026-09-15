package com.example.xiangsugame.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.xiangsugame.auth.AuthManager
import com.example.xiangsugame.ui.theme.Cocoa
import com.example.xiangsugame.ui.theme.Coral
import com.example.xiangsugame.ui.theme.GlowPurple
import com.example.xiangsugame.ui.theme.Ink
import kotlinx.coroutines.launch

// ============================================================
// 共享游戏控件 —— 分段选择 / 渐变主按钮 / 玻璃胶囊
// 多个界面复用，保证整套 UI 风格统一。
// ============================================================

/**
 * 分段选择控件（Segmented Control）：
 * 半透明玻璃容器 + 若干等宽选项，选中项品牌渐变高亮 + 深字，未选中浮起微光。
 * 用于游戏页工具切换、首页模式切换等二选一/多选一场景。
 */
@Composable
fun <T> SegmentedControl(
    items: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items.forEach { (key, label) ->
            val isSelected = key == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (isSelected) Brush.horizontalGradient(listOf(Coral, GlowPurple))
                        // 未选中：一层极淡的浮起光，像玻璃上微微凸起的按键
                        else Brush.verticalGradient(
                            listOf(Color(0x14FFFFFF), Color(0x08FFFFFF)),
                        ),
                    )
                    .clickable(enabled = enabled) { onSelect(key) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = if (isSelected) Color(0xFF1A1240)
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * 渐变主按钮：品牌渐变底 + 深字粗体，圆角胶囊；禁用时退化为灰面。
 * 比 Material Button 更有"游戏按键"的力道，用于开始游戏 / 下一关等主行动。
 */
@Composable
fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    brush: Brush = Brush.horizontalGradient(listOf(Coral, GlowPurple)),
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (enabled) brush
                else Brush.horizontalGradient(
                    listOf(
                        MaterialTheme.colorScheme.surfaceVariant,
                        MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ),
            )
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (enabled) Color(0xFF1A1240)
            else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
        )
    }
}

/**
 * 玻璃胶囊：半透明白底 + 白字，放在渐变 Hero 卡内展示数据（通关数、额度等）。
 */
@Composable
fun GlassChip(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = Color(0x29FFFFFF),
        contentColor = Color.White,
    ) {
        Text(
            text,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

/**
 * 二级页顶栏:圆形返回键 + 标题副题;用于题库/排行榜/对战/设置等页面。
 */
@Composable
fun ScreenTopBar(
    title: String,
    subtitle: String? = null,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            onClick = onBack,
            modifier = Modifier.size(38.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("←", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = Ink)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (action != null) action()
    }
}

/**
 * 修改昵称弹窗:本地校验长度 → 提交服务端持久化 → 成功后同步本地会话。
 * 两处复用:首次邮箱登录后的引导(根导航)与设置页的手动修改。
 */
@Composable
fun NicknameDialog(
    current: String,
    onDismiss: () -> Unit,
    title: String = "修改昵称",
    hint: String = "排行榜、对局名次等展示用昵称,提交后即时生效",
) {
    var name by remember { mutableStateOf(current) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun submit() {
        val trimmed = name.trim()
        when {
            trimmed.isEmpty() -> error = "昵称不能为空"
            trimmed.length > 20 -> error = "昵称最多 20 个字符"
            busy -> return
            else -> {
                busy = true
                error = null
                scope.launch {
                    AuthManager.changeNickname(trimmed)
                        .onSuccess { onDismiss() }
                        .onFailure { error = it.message ?: "修改失败" }
                    busy = false
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        if (it.length <= 20) {
                            name = it
                            error = null
                        }
                    },
                    singleLine = true,
                    label = { Text("昵称(≤20 字)") },
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Text(
                        "⚠ $it",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                Text(
                    hint,
                    fontSize = 11.sp,
                    color = Cocoa,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.width(20.dp).height(20.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Button(onClick = { submit() }) { Text("保存") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("跳过") }
        },
    )
}
