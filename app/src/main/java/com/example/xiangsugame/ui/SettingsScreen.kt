package com.example.xiangsugame.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.xiangsugame.api.ApiClient
import com.example.xiangsugame.auth.AuthManager
import com.example.xiangsugame.data.LocalSettings
import com.example.xiangsugame.ui.theme.Cocoa
import com.example.xiangsugame.ui.theme.Coral
import com.example.xiangsugame.ui.theme.Ink
import kotlinx.coroutines.launch

/** 设置页:服务器地址 / 关卡解锁 / 账号 / 退出登录。 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var url by remember { mutableStateOf(LocalSettings.serverUrl) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding(),
    ) {
        ScreenTopBar(title = "设置", subtitle = "服务器与偏好", onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            SettingsCard("🌐 服务器地址") {
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        testResult = null
                    },
                    singleLine = true,
                    placeholder = { Text(LocalSettings.DEFAULT_SERVER_URL) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "模拟器用默认地址;真机填电脑局域网 IP",
                    fontSize = 11.sp,
                    color = Cocoa,
                    modifier = Modifier.padding(top = 6.dp, bottom = 10.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            LocalSettings.saveServerUrl(url)
                            testResult = "已保存,测试连接…"
                            testing = true
                            scope.launch {
                                ApiClient.safe { ApiClient.api().me() }
                                    .onSuccess { me ->
                                        testResult = "✓ 连接成功:你好,${me.nickname}"
                                        if (me.nickname != AuthManager.session?.nickname) {
                                            AuthManager.updateNickname(me.nickname)
                                        }
                                    }
                                    .onFailure { testResult = "✗ ${it.message}" }
                                testing = false
                            }
                        },
                        enabled = !testing,
                    ) { Text(if (testing) "测试中…" else "保存并测试") }
                    if (testing) {
                        CircularProgressIndicator(modifier = Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
                    }
                }
                testResult?.let {
                    Text(
                        it,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (it.startsWith("✓")) Color(0xFF66BB6A) else MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            SettingsCard("🔓 全部解锁") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "所有内置关卡直接可玩",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Ink,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = LocalSettings.demoAllUnlocked,
                        onCheckedChange = { LocalSettings.saveDemoAllUnlocked(it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = Coral),
                    )
                }
            }

            SettingsCard("👤 账号") {
                val nickname = AuthManager.session?.nickname ?: "-"
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(nickname, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Ink)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "用户 #${AuthManager.session?.userId ?: "-"}",
                        fontSize = 11.sp, color = Cocoa,
                    )
                }
                OutlinedButton(
                    onClick = { AuthManager.logout() },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("退出登录") }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, Color(0x1FFFFFFF)),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = Ink)
            Spacer(modifier = Modifier.height(10.dp))
            content()
        }
    }
}
