package com.familyledger.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Handshake
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.familyledger.app.data.EntryType
import com.familyledger.app.data.LedgerEntryRow
import com.familyledger.app.data.WorkerBalanceRow
import com.familyledger.app.domain.formatSignedYuan
import com.familyledger.app.sync.CloudSyncState
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val shortDateFormatter = DateTimeFormatter.ofPattern("M月d日")

@Composable
fun HomeScreen(
    workers: List<WorkerBalanceRow>,
    recentEntries: List<LedgerEntryRow>,
    onWorkEntry: () -> Unit,
    onAdvance: () -> Unit,
    onWorkers: () -> Unit,
    onSettlement: () -> Unit,
    onBackup: () -> Unit,
    cloudSyncState: CloudSyncState,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text("家庭工账", style = MaterialTheme.typography.headlineLarge)
            Text(
                "平时记流水，结工资时按日期区间汇总",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        item { SyncStatusBanner(cloudSyncState) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onWorkEntry,
                    modifier = Modifier.weight(1f).heightIn(min = 108.dp),
                ) {
                    Column {
                        Icon(Icons.Default.Work, contentDescription = null)
                        Spacer(Modifier.height(10.dp))
                        Text("记一笔工钱", fontWeight = FontWeight.Medium)
                        Text("数量 × 单价", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                ElevatedButton(
                    onClick = onAdvance,
                    modifier = Modifier.weight(1f).heightIn(min = 108.dp),
                ) {
                    Column {
                        Icon(Icons.Default.Payments, contentDescription = null)
                        Spacer(Modifier.height(10.dp))
                        Text("记一笔预支", fontWeight = FontWeight.Medium)
                        Text("记录已给的钱", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        item {
            WideHomeButton(Icons.Default.Handshake, "工人账本", "查看每位工人的余额", onWorkers)
        }
        item {
            WideHomeButton(Icons.Default.ReceiptLong, "工资结算", "自由选择开始和结束日期", onSettlement)
        }
        item {
            WideHomeButton(Icons.Default.Backup, "同步与备份", "两部手机共同记账、导出备份", onBackup)
        }
        item {
            Text(
                if (workers.isEmpty()) "先添加一位工人，就可以开始记账" else "最近流水",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (recentEntries.isEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text("目前还没有流水", modifier = Modifier.padding(18.dp))
                }
            }
        } else {
            items(recentEntries, key = { it.entry.id }) { row ->
                RecentEntryCard(row)
            }
        }
    }
}

@Composable
private fun WideHomeButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    ElevatedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 68.dp),
    ) {
        Icon(icon, contentDescription = null)
        Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun RecentEntryCard(row: LedgerEntryRow) {
    val entry = row.entry
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(15.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(row.workerName, fontWeight = FontWeight.Medium)
                Text(
                    buildString {
                        append(entry.description)
                        if (entry.entryType == EntryType.WORK && entry.quantity != null) {
                            append(" · ${entry.quantity}${entry.unitSnapshot}")
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column {
                Text(formatSignedYuan(entry.amountMicros), fontWeight = FontWeight.Medium)
                Text(
                    LocalDate.ofEpochDay(entry.entryEpochDay).format(shortDateFormatter),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
