package com.familyledger.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.familyledger.app.sync.CloudSyncState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val syncTimeFormatter = DateTimeFormatter.ofPattern("M月d日 HH:mm")

@Composable
fun BackupScreen(
    syncState: CloudSyncState,
    onBack: () -> Unit,
    onConfigureSync: (String, String) -> Unit,
    onSyncNow: () -> Unit,
    onDisableSync: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var endpoint by remember(syncState.endpoint) { mutableStateOf(syncState.endpoint) }
    var syncKey by rememberSaveable { mutableStateOf("") }
    var showDisableConfirmation by rememberSaveable { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "返回") }
            Text("同步与备份", style = MaterialTheme.typography.headlineMedium)
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SyncStatusBanner(syncState)
            Text("云同步", style = MaterialTheme.typography.titleLarge)
            Text(
                "开启后，两部手机使用同一个家庭同步码。平时断网仍可记账，恢复网络后会自动合并。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = endpoint,
                onValueChange = { endpoint = it },
                label = { Text("云同步地址") },
                placeholder = { Text("https://…/ledger-sync") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = syncKey,
                onValueChange = { syncKey = it },
                label = { Text(if (syncState.isConfigured) "家庭同步码（不修改可留空）" else "家庭同步码") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { onConfigureSync(endpoint, syncKey) },
                enabled = !syncState.isSyncing && endpoint.isNotBlank() && (syncState.isConfigured || syncKey.isNotBlank()),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) {
                Icon(Icons.Default.CloudSync, contentDescription = null)
                Text(
                    if (syncState.isConfigured) "保存设置并测试" else "开启并测试云同步",
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            if (syncState.isConfigured) {
                OutlinedButton(
                    onClick = onSyncNow,
                    enabled = !syncState.isSyncing,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                ) {
                    Text(if (syncState.isSyncing) "正在同步…" else "立即同步")
                }
                syncState.lastSuccessAt?.let { timestamp ->
                    Text(
                        "最近成功：${Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).format(syncTimeFormatter)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { showDisableConfirmation = true }) { Text("关闭云同步") }
            }

            Text("文件备份", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp))
            Text("即使开启云同步，也建议每次结工资后导出一份备份文件。")
            Button(
                onClick = onExport,
                modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
            ) {
                Icon(Icons.Default.FileDownload, contentDescription = null)
                Text("导出备份文件", modifier = Modifier.padding(start = 8.dp))
            }
            ElevatedButton(
                onClick = onImport,
                modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
            ) {
                Icon(Icons.Default.FileUpload, contentDescription = null)
                Text("从备份恢复", modifier = Modifier.padding(start = 8.dp))
            }
            Text(
                "恢复采用合并方式，不会先清空手机里的现有账本。相同编号会保留修订号和修改时间更新的记录。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showDisableConfirmation) {
        AlertDialog(
            onDismissRequest = { showDisableConfirmation = false },
            title = { Text("关闭云同步？") },
            text = { Text("本机账本不会删除，但这部手机之后不会再与另一部手机共同更新。") },
            confirmButton = {
                TextButton(onClick = {
                    showDisableConfirmation = false
                    onDisableSync()
                }) { Text("确认关闭") }
            },
            dismissButton = {
                TextButton(onClick = { showDisableConfirmation = false }) { Text("取消") }
            },
        )
    }
}
