package com.zhiwo.shiguangjian.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhiwo.shiguangjian.data.update.UpdateManager
import com.zhiwo.shiguangjian.data.update.UpdateState
import com.zhiwo.shiguangjian.ui.screens.SectionTitle
import com.zhiwo.shiguangjian.ui.screens.StorageRow

private fun mb(bytes: Long): String = "%.1f".format(bytes / 1024f / 1024f)

/**
 * 设置页「应用更新」分区：当前版本、检查按钮、以及整个更新流程的状态反馈
 * （状态与全局弹窗同源，都来自 UpdateManager.state）。
 */
@Composable
fun UpdateSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state by UpdateManager.state.collectAsState()

    Column(modifier = modifier.fillMaxWidth()) {
        SectionTitle("应用更新")
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                StorageRow("当前版本", UpdateManager.localVersionName(context))

                when (val s = state) {
                    is UpdateState.Idle -> {
                        OutlinedButton(
                            onClick = { UpdateManager.checkNow(context) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) { Text("检查更新") }
                    }
                    is UpdateState.Checking -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("正在检查…", style = MaterialTheme.typography.bodyMedium)
                    }
                    is UpdateState.UpToDate -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("已是最新版（云端 ${s.versionName}）",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    is UpdateState.Available -> Text(
                        "发现新版本 ${s.info.versionName}，请到弹窗确认",
                        style = MaterialTheme.typography.bodySmall
                    )
                    is UpdateState.Downloading -> {
                        val percent = if (s.total > 0) (s.downloaded * 100 / s.total).toInt() else -1
                        LinearProgressIndicator(
                            progress = { if (percent >= 0) percent / 100f else 0f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (percent >= 0) "下载中 $percent%（${mb(s.downloaded)} MB）"
                            else "下载中 ${mb(s.downloaded)} MB",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    is UpdateState.ReadyToInstall -> {
                        Button(
                            onClick = { UpdateManager.confirmInstall(context) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) { Text("安装已下载的更新（${s.info.versionName}）") }
                    }
                    is UpdateState.Failed -> Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, contentDescription = null,
                                tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(s.message, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { UpdateManager.checkNow(context) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) { Text("重试") }
                    }
                }
            }
        }
    }
}

/**
 * 全局更新弹窗宿主：挂在 MainActivity 顶层，处理「发现新版询问」「下载进度」
 * 「下载完成待安装」三种打断式状态。检查失败只在设置页显示，不弹窗打扰。
 */
@Composable
fun UpdateHost() {
    val context = LocalContext.current
    val state by UpdateManager.state.collectAsState()

    when (val s = state) {
        is UpdateState.Available -> AlertDialog(
            onDismissRequest = { UpdateManager.dismiss() },
            title = { Text("发现新版本 ${s.info.versionName}") },
            text = {
                Column {
                    Text(
                        s.info.notes.ifBlank { "包含修复与优化" },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 8,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "下载约 ${mb(s.info.sizeBytes)} MB，安装后数据不受影响",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { UpdateManager.downloadAndInstall(context, s.info) }) {
                    Text("立即更新")
                }
            },
            dismissButton = {
                TextButton(onClick = { UpdateManager.dismiss() }) { Text("以后再说") }
            }
        )
        is UpdateState.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("正在下载 ${s.info.versionName}") },
            text = {
                val percent = if (s.total > 0) (s.downloaded * 100 / s.total).toInt() else -1
                Column {
                    LinearProgressIndicator(
                        progress = { if (percent >= 0) percent / 100f else 0f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (percent >= 0) "$percent%（${mb(s.downloaded)} / ${mb(s.total)} MB）"
                        else "已下载 ${mb(s.downloaded)} MB",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            confirmButton = {},
            dismissButton = {}
        )
        is UpdateState.ReadyToInstall -> AlertDialog(
            onDismissRequest = { UpdateManager.dismiss() },
            title = { Text("下载完成") },
            text = {
                Text("新版本 ${s.info.versionName} 已就绪，点「安装更新」开始安装。" +
                    "若系统询问「是否允许安装」，请选择允许。")
            },
            confirmButton = {
                TextButton(onClick = { UpdateManager.confirmInstall(context) }) {
                    Text("安装更新")
                }
            },
            dismissButton = {
                TextButton(onClick = { UpdateManager.dismiss() }) { Text("稍后") }
            }
        )
        else -> {}
    }
}
