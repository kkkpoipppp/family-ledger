package com.familyledger.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.familyledger.app.LedgerViewModel
import com.familyledger.app.data.EntryType
import com.familyledger.app.data.LedgerEntryEntity
import com.familyledger.app.data.LedgerEntryRow
import com.familyledger.app.data.WorkerBalanceRow
import com.familyledger.app.domain.formatSignedYuan
import com.familyledger.app.domain.formatYuan
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private enum class EntryFilter { ALL, WORK, ADVANCE }

@Composable
fun LedgerScreen(
    workerId: String?,
    workers: List<WorkerBalanceRow>,
    viewModel: LedgerViewModel,
    onBack: () -> Unit,
    onWorkerSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val worker = workers.firstOrNull { it.worker.id == workerId }
    val flow = remember(workerId) { workerId?.let(viewModel::entriesForWorker) }
    val entries by (flow?.collectAsStateWithLifecycle(initialValue = emptyList())
        ?: remember { mutableStateOf<List<LedgerEntryRow>>(emptyList()) })
    var filter by remember { mutableStateOf(EntryFilter.ALL) }
    var pendingDelete by remember { mutableStateOf<LedgerEntryEntity?>(null) }
    val shownEntries = entries.filter {
        when (filter) {
            EntryFilter.ALL -> true
            EntryFilter.WORK -> it.entry.entryType == EntryType.WORK
            EntryFilter.ADVANCE -> it.entry.entryType in setOf(
                EntryType.ADVANCE,
                EntryType.PAYMENT,
                EntryType.ADJUSTMENT,
            )
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "返回") }
            Text(
                worker?.let {
                    if (it.worker.isDeleted) "${it.worker.name}的历史账本（已离职）" else "${it.worker.name}的账本"
                } ?: "选择工人",
                style = MaterialTheme.typography.headlineMedium,
            )
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                WorkerSelector(workers, workerId, onWorkerSelected)
            }
            if (worker != null) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            Text(
                                if (worker.balanceMicros >= 0) "目前还应付" else "目前已多付",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                formatYuan(worker.balanceMicros),
                                style = MaterialTheme.typography.headlineLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(filter == EntryFilter.ALL, { filter = EntryFilter.ALL }, { Text("全部") })
                        FilterChip(filter == EntryFilter.WORK, { filter = EntryFilter.WORK }, { Text("工钱") })
                        FilterChip(filter == EntryFilter.ADVANCE, { filter = EntryFilter.ADVANCE }, { Text("预支/结算") })
                    }
                }
                if (shownEntries.isEmpty()) {
                    item { Text("这个分类还没有流水") }
                } else {
                    items(shownEntries, key = { it.entry.id }) { row ->
                        LedgerEntryCard(
                            row,
                            onDelete = if (row.entry.settlementId == null) {
                                { pendingDelete = row.entry }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("撤销这笔流水？") },
            text = { Text("流水会从账本中隐藏，但数据库仍保留撤销记录，方便以后核对。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteEntry(entry.id)
                    pendingDelete = null
                }) { Text("确认撤销", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun LedgerEntryCard(row: LedgerEntryRow, onDelete: (() -> Unit)?) {
    val entry = row.entry
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(entry.description, fontWeight = FontWeight.Medium)
                Text(
                    entryDetail(entry),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (entry.note.isNotBlank()) {
                    Text("备注：${entry.note}", style = MaterialTheme.typography.bodyMedium)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(formatSignedYuan(entry.amountMicros), fontWeight = FontWeight.Medium)
                if (onDelete != null) {
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = "撤销这笔流水")
                    }
                } else {
                    Text(
                        if (entry.entryType == EntryType.ADJUSTMENT) "结算冲正" else "结算付款",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun entryDetail(entry: LedgerEntryEntity): String = buildString {
    append(LocalDate.ofEpochDay(entry.entryEpochDay).format(DateTimeFormatter.ofPattern("yyyy年M月d日")))
    if (entry.entryType == EntryType.WORK && entry.quantity != null) {
        append(" · ${entry.quantity}${entry.unitSnapshot}")
        append(" × ${formatYuan(entry.unitPriceMicros)}")
    }
}
