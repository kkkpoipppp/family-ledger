package com.familyledger.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.familyledger.app.LedgerViewModel
import com.familyledger.app.SavingAction
import com.familyledger.app.data.WorkerBalanceRow
import com.familyledger.app.domain.formatYuan
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun WorkersScreen(
    workers: List<WorkerBalanceRow>,
    viewModel: LedgerViewModel,
    onBack: () -> Unit,
    onWorkerClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var pendingOffboard by remember { mutableStateOf<WorkerBalanceRow?>(null) }
    var pendingPurge by remember { mutableStateOf<WorkerBalanceRow?>(null) }
    var purgeConfirmation by remember { mutableStateOf("") }
    val savingAction by viewModel.savingAction.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "返回") }
            Text("工人账本", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Button(
                    onClick = { showAddDialog = true },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text("添加工人", modifier = Modifier.padding(start = 8.dp))
                }
            }
            if (workers.isEmpty()) {
                item {
                    Text("还没有工人。添加后即可记录计件工钱和预支。")
                }
            } else {
                items(workers, key = { it.worker.id }) { row ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onWorkerClick(row.worker.id) }
                                .padding(17.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(row.worker.name, style = MaterialTheme.typography.titleMedium)
                                    if (row.worker.isDeleted) {
                                        Text(
                                            "已离职",
                                            color = MaterialTheme.colorScheme.error,
                                            style = MaterialTheme.typography.labelLarge,
                                        )
                                    }
                                }
                                Text(
                                    if (row.worker.isDeleted) {
                                        "${row.entryCount} 笔历史流水"
                                    } else {
                                        "${row.entryCount} 笔流水"
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(formatYuan(row.balanceMicros), fontWeight = FontWeight.Medium)
                                Text(
                                    if (row.balanceMicros >= 0) "目前还应付" else "目前多付",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                TextButton(onClick = { onWorkerClick(row.worker.id) }) {
                                    Text("查看账本")
                                }
                                if (row.worker.isDeleted) {
                                    TextButton(onClick = { viewModel.restoreWorker(row.worker.id) }) {
                                        Text("恢复在职")
                                    }
                                } else {
                                    TextButton(onClick = { pendingOffboard = row }) {
                                        Text("设为离职", color = MaterialTheme.colorScheme.error)
                                    }
                                }
                                TextButton(
                                    onClick = {
                                        purgeConfirmation = ""
                                        pendingPurge = row
                                    },
                                ) {
                                    Text("永久删除", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddWorkerDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { name, note ->
                viewModel.addWorker(name, note) { showAddDialog = false }
            },
        )
    }

    pendingOffboard?.let { row ->
        val hasBalance = row.balanceMicros != 0L
        AlertDialog(
            onDismissRequest = { pendingOffboard = null },
            title = { Text("将${row.worker.name}设为离职？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("请先确认是否已经为这位工人完全结清工资。")
                    Text(
                        if (row.balanceMicros > 0) {
                            "账本显示目前还应付 ${formatYuan(row.balanceMicros)}。"
                        } else if (row.balanceMicros < 0) {
                            "账本显示目前已经多付 ${formatYuan(row.balanceMicros)}。"
                        } else {
                            "账本余额为 ¥0.00，可以安全设为离职。"
                        },
                        fontWeight = FontWeight.Medium,
                        color = if (hasBalance) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                    Text("离职后不会再出现在记工、预支和工资结算选项中，但全部历史流水都会保留。")
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.offboardWorker(row.worker.id, force = hasBalance) {
                            pendingOffboard = null
                        }
                    },
                ) {
                    Text(if (hasBalance) "仍然强制设为离职" else "已结清，设为离职")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingOffboard = null }) { Text("取消") }
            },
        )
    }

    pendingPurge?.let { row ->
        val isPurging = savingAction == SavingAction.PURGE_WORKER
        val confirmationMatches = purgeConfirmation.trim() == row.worker.name
        AlertDialog(
            onDismissRequest = { if (!isPurging) pendingPurge = null },
            title = { Text("永久删除${row.worker.name}？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "这不是“设为离职”。工人资料、全部流水、结算记录和结算明细都会从本机及云端账本删除，无法撤销。",
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Medium,
                    )
                    Text("其他手机会在下次同步时一并清除；开启云同步时，永久删除必须联网完成。")
                    Text("以前手动导出的 JSON 备份文件不会自动删除，如有需要请自行清理旧备份。")
                    Text("为防止误删，请输入工人姓名“${row.worker.name}”确认。")
                    OutlinedTextField(
                        value = purgeConfirmation,
                        onValueChange = { purgeConfirmation = it },
                        label = { Text("输入工人姓名") },
                        singleLine = true,
                        enabled = !isPurging,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.permanentlyDeleteWorker(row.worker.id, purgeConfirmation) {
                            pendingPurge = null
                            purgeConfirmation = ""
                        }
                    },
                    enabled = confirmationMatches && !isPurging,
                ) {
                    Text(if (isPurging) "正在永久删除…" else "永久删除全部资料")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingPurge = null }, enabled = !isPurging) { Text("取消") }
            },
        )
    }
}

@Composable
private fun AddWorkerDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加工人") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("姓名或称呼") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注（可以不填）") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(name, note) }, enabled = name.isNotBlank()) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
