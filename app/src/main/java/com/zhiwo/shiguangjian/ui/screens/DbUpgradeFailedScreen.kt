package com.zhiwo.shiguangjian.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zhiwo.shiguangjian.data.db.DbGate

@Composable
fun DbCheckingScreen() {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text("正在准备你的时光笺…", style = MaterialTheme.typography.titleMedium)
        }
    }
}

/**
 * 升级失败页。存在的意义就是"不偷偷清库"：这里没有任何一条路会在你没明确打字确认的情况下删数据。
 */
@Composable
fun DbUpgradeFailedScreen(
    info: String,
    attempts: Int,
    onRetry: () -> Unit,
    onRebuildConfirmed: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    var confirmText by remember { mutableStateOf("") }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("没能完成升级，你的数据还在", style = MaterialTheme.typography.headlineSmall)
            Text(
                "App 试着把旧数据库升到新版本，中途失败了。我们没有清空它——记录、任务、日记都还留在手机上，" +
                    "只是这版本暂时读不了。可以先退出 App 再打开重试一次；反复失败的话，把下面的提示信息复制给我。",
                style = MaterialTheme.typography.bodyMedium
            )
            Text("已尝试 $attempts 次", style = MaterialTheme.typography.labelLarge)
            Surface(
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Text(
                    text = info,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp)
                )
            }
            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(info)) }) {
                Text("复制提示信息")
            }
            Button(onClick = onRetry) { Text("退出并重试一次") }

            if (attempts >= 2) {
                Text(
                    "实在升不上去时，可以清空重建一个空数据库。这**会丢掉全部记录、任务、日记**（会先备份一份文件，" +
                        "但把备份取回来要连电脑，基本等于放弃）。所以请打字确认，不是点一下就删。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Start
                )
                OutlinedTextField(
                    value = confirmText,
                    onValueChange = { confirmText = it },
                    label = { Text("要清空请打字输入：${DbGate.REBUILD_CONFIRM_TEXT}") },
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    enabled = confirmText.trim() == DbGate.REBUILD_CONFIRM_TEXT,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    onClick = onRebuildConfirmed
                ) { Text("确认清空并重建") }
            }
        }
    }
}
