package com.familyledger.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.familyledger.app.SavingAction
import com.familyledger.app.SettlementPreview
import com.familyledger.app.SettlementRequest
import com.familyledger.app.data.SettlementTotalsRow
import com.familyledger.app.data.SettlementWithLines
import com.familyledger.app.data.WorkSummaryRow
import com.familyledger.app.data.WorkerBalanceRow
import com.familyledger.app.domain.formatSignedYuan
import com.familyledger.app.domain.formatYuan
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

@Composable
fun SettlementScreen(
    workers: List<WorkerBalanceRow>,
    viewModel: LedgerViewModel,
    initialWorkerId: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val today by rememberCurrentDate()
    val lastHalfStart = remember(today) {
        if (today.monthValue > 6) LocalDate.of(today.year, 1, 1) else LocalDate.of(today.year - 1, 7, 1)
    }
    val lastHalfEnd = remember(today) {
        if (today.monthValue > 6) LocalDate.of(today.year, 6, 30) else LocalDate.of(today.year - 1, 12, 31)
    }
    var selectedWorkerId by remember { mutableStateOf(initialWorkerId ?: workers.firstOrNull()?.worker?.id) }
    var startDate by remember { mutableStateOf(lastHalfStart) }
    var endDate by remember { mutableStateOf(lastHalfEnd) }
    var confirmPreview by remember { mutableStateOf<SettlementPreview?>(null) }
    var pendingReverse by remember { mutableStateOf<SettlementWithLines?>(null) }
    var reversalReason by remember { mutableStateOf("") }
    var reversalOperationId by remember { mutableStateOf("") }
    val rawPreview by viewModel.settlementPreview.collectAsStateWithLifecycle()
    val savingAction by viewModel.savingAction.collectAsStateWithLifecycle()
    val datesValid = !endDate.isBefore(startDate) && !endDate.isAfter(today)
    val currentRequest = selectedWorkerId?.let { SettlementRequest(it, startDate, endDate) }
    val preview = rawPreview?.takeIf { it.request == currentRequest }
    val totals = preview?.data?.totals
    val historyFlow = remember(selectedWorkerId) { selectedWorkerId?.let(viewModel::settlementsForWorker) }
    val history by (historyFlow?.collectAsStateWithLifecycle(initialValue = emptyList())
        ?: remember { mutableStateOf<List<SettlementWithLines>>(emptyList()) })

    LaunchedEffect(workers) {
        if (selectedWorkerId == null || workers.none { it.worker.id == selectedWorkerId }) {
            selectedWorkerId = workers.firstOrNull()?.worker?.id
        }
    }
    LaunchedEffect(currentRequest, datesValid) {
        confirmPreview = null
        if (currentRequest != null && datesValid) {
            viewModel.loadSettlement(currentRequest.workerId, currentRequest.start, currentRequest.end)
        } else {
            viewModel.clearSettlementPreview()
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "返回") }
            Column {
                Text("工资结算", style = MaterialTheme.typography.headlineMedium)
                Text("通常半年结一次，也可以自由选日期", style = MaterialTheme.typography.bodyMedium)
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text("1. 常用日期", style = MaterialTheme.typography.titleMedium)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ElevatedButton(
                        onClick = {
                            startDate = LocalDate.of(today.year, 1, 1)
                            endDate = minOf(today, LocalDate.of(today.year, 6, 30))
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("今年上半年") }
                    ElevatedButton(
                        onClick = {
                            startDate = LocalDate.of(today.year, 7, 1)
                            endDate = today
                        },
                        enabled = today.monthValue >= 7,
                        modifier = Modifier.weight(1f),
                    ) { Text("今年下半年") }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DateField("开始日期", startDate, { startDate = it }, Modifier.weight(1f))
                    DateField("结束日期", endDate, { endDate = it }, Modifier.weight(1f))
                }
                when {
                    endDate.isBefore(startDate) -> ErrorText("结束日期不能早于开始日期")
                    endDate.isAfter(today) -> ErrorText("结算日期不能晚于今天")
                }
            }
            item {
                Text("2. 选择工人", style = MaterialTheme.typography.titleMedium)
                WorkerSelector(
                    workers,
                    selectedWorkerId,
                    { selectedWorkerId = it },
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            item { SettlementSummaryCard(totals, currentRequest != null && datesValid && preview == null) }
            if (preview?.data?.workSummary?.isNotEmpty() == true) {
                item { WorkSummaryCard(preview.data.workSummary) }
            }
            item {
                val isSaving = savingAction == SavingAction.SETTLEMENT
                Button(
                    onClick = { confirmPreview = preview },
                    enabled = !isSaving && preview != null && (totals?.balanceMicros ?: 0) > 0,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
                ) { Text(if (isSaving) "正在结算…" else "确认这个日期区间已经结清") }
                Text(
                    "确认后会自动记一笔付款，并保存本次件数明细；原始流水不会删除。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (history.isNotEmpty()) {
                item { Text("结算历史", style = MaterialTheme.typography.titleLarge) }
                items(history, key = { it.settlement.id }) { record ->
                    SettlementHistoryCard(record) {
                        pendingReverse = record
                        reversalReason = ""
                        reversalOperationId = UUID.randomUUID().toString()
                    }
                }
            }
        }
    }

    confirmPreview?.let { confirmed ->
        AlertDialog(
            onDismissRequest = { if (savingAction != SavingAction.SETTLEMENT) confirmPreview = null },
            title = { Text("确认已经付清？") },
            text = {
                Text(
                    "将记录已支付 ${formatYuan(confirmed.data.totals.balanceMicros)}。请再次核对工人和日期区间。",
                )
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.settle(confirmed) { confirmPreview = null } },
                    enabled = savingAction != SavingAction.SETTLEMENT,
                ) { Text(if (savingAction == SavingAction.SETTLEMENT) "正在保存…" else "确认结清") }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmPreview = null },
                    enabled = savingAction != SavingAction.SETTLEMENT,
                ) { Text("取消") }
            },
        )
    }

    pendingReverse?.let { record ->
        AlertDialog(
            onDismissRequest = { if (savingAction != SavingAction.REVERSAL) pendingReverse = null },
            title = { Text("撤销这次结算？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("原付款不会删除，系统会增加一笔等额冲正流水，账目会恢复为未支付状态。")
                    OutlinedTextField(
                        value = reversalReason,
                        onValueChange = { reversalReason = it },
                        label = { Text("撤销原因（必填）") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.reverseSettlement(
                            operationId = reversalOperationId,
                            settlementId = record.settlement.id,
                            reason = reversalReason,
                        ) {
                            pendingReverse = null
                            currentRequest?.let {
                                viewModel.loadSettlement(it.workerId, it.start, it.end)
                            }
                        }
                    },
                    enabled = reversalReason.isNotBlank() && savingAction != SavingAction.REVERSAL,
                ) { Text(if (savingAction == SavingAction.REVERSAL) "正在撤销…" else "确认撤销") }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingReverse = null },
                    enabled = savingAction != SavingAction.REVERSAL,
                ) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ErrorText(message: String) {
    Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun SettlementSummaryCard(totals: SettlementTotalsRow?, loading: Boolean) {
    val balance = totals?.balanceMicros ?: 0
    val balanceLabel = when {
        balance > 0 -> "还应支付"
        balance < 0 -> "已经多付"
        else -> "无需支付"
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("所选日期汇总", style = MaterialTheme.typography.titleMedium)
            if (loading) Text("正在重新计算…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            SummaryLine("计件工钱", totals?.earnedMicros ?: 0)
            SummaryLine("预支工资", -(totals?.advancesMicros ?: 0))
            SummaryLine("已经支付", -(totals?.paymentsMicros ?: 0))
            SummaryLine("其他调整", totals?.adjustmentsMicros ?: 0)
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(balanceLabel, fontWeight = FontWeight.Medium)
                Text(
                    formatYuan(balance),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun WorkSummaryCard(lines: List<WorkSummaryRow>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("这段时间做了多少", style = MaterialTheme.typography.titleMedium)
            lines.forEachIndexed { index, line ->
                if (index > 0) HorizontalDivider()
                SummaryQuantityLine(
                    description = workDescription(
                        line.description,
                        line.lengthTypeSnapshot,
                        line.processNameSnapshot,
                    ),
                    quantity = line.quantity,
                    unit = line.unitSnapshot,
                    amountMicros = line.amountMicros,
                )
            }
        }
    }
}

@Composable
private fun SettlementHistoryCard(record: SettlementWithLines, onReverse: () -> Unit) {
    val settlement = record.settlement
    val formatter = remember { DateTimeFormatter.ofPattern("yyyy/M/d") }
    val isReversed = settlement.reversedAt != null
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "${LocalDate.ofEpochDay(settlement.startEpochDay).format(formatter)} — " +
                            LocalDate.ofEpochDay(settlement.endEpochDay).format(formatter),
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        "支付 ${formatYuan(settlement.settledPaymentMicros)}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Surface(
                    color = if (isReversed) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.primaryContainer
                    },
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        if (isReversed) "已撤销" else "已结清",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        maxLines = 1,
                        color = if (isReversed) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        },
                    )
                }
            }
            record.lines.forEach { line ->
                SummaryQuantityLine(
                    description = workDescription(
                        line.description,
                        line.lengthTypeSnapshot,
                        line.processNameSnapshot,
                    ),
                    quantity = line.quantity,
                    unit = line.unitSnapshot,
                    amountMicros = line.amountMicros,
                )
            }
            if (isReversed) {
                Text("撤销原因：${settlement.reversalReason}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                TextButton(onClick = onReverse) { Text("撤销这次结算") }
            }
        }
    }
}

@Composable
private fun SummaryQuantityLine(
    description: String,
    quantity: Long,
    unit: String,
    amountMicros: Long,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(modifier = Modifier.weight(1f)) {
            Text(description)
            Text("$quantity$unit", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(formatYuan(amountMicros))
    }
}

private fun workDescription(name: String, length: String, process: String): String =
    listOf(name, length, process).filter { it.isNotBlank() && it != "不区分" }.distinct().joinToString(" · ")

@Composable
private fun SummaryLine(label: String, amountMicros: Long) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(formatSignedYuan(amountMicros))
    }
}
