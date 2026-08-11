package com.familyledger.app.ui

import android.app.DatePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.familyledger.app.data.WorkerBalanceRow
import com.familyledger.app.sync.CloudSyncState
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

private val chineseDateFormatter = DateTimeFormatter.ofPattern("yyyy年M月d日")

@Composable
fun rememberCurrentDate(): State<LocalDate> = produceState(LocalDate.now()) {
    while (true) {
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.now(zone)
        val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay(zone)
        val waitMillis = Duration.between(now, nextMidnight).toMillis().coerceAtLeast(1_000L) + 1_000L
        delay(waitMillis)
        value = LocalDate.now(zone)
    }
}

@Composable
fun SyncStatusBanner(state: CloudSyncState, modifier: Modifier = Modifier) {
    val icon = when {
        !state.isConfigured -> Icons.Default.CloudOff
        state.lastError != null -> Icons.Default.SyncProblem
        else -> Icons.Default.CloudDone
    }
    val title = when {
        !state.isConfigured -> "本机模式"
        state.isSyncing -> "正在同步"
        state.lastError != null -> "云同步待重试"
        else -> "云同步已开启"
    }
    val detail = when {
        !state.isConfigured -> "账本保存在本机，可开启两部手机共同编辑"
        state.lastError != null -> "本机记账不受影响：${state.lastError}"
        state.lastSuccessAt != null -> "两部手机使用同一份云端账本"
        else -> "首次同步尚未完成"
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column {
                Text(title, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(
                    detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
fun WorkerSelector(
    workers: List<WorkerBalanceRow>,
    selectedWorkerId: String?,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (workers.isEmpty()) {
        Text("请先添加工人", color = MaterialTheme.colorScheme.error)
        return
    }
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(end = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(workers, key = { it.worker.id }) { row ->
            FilterChip(
                selected = row.worker.id == selectedWorkerId,
                onClick = { onSelected(row.worker.id) },
                label = {
                    Text(if (row.worker.isDeleted) "${row.worker.name}（已离职）" else row.worker.name)
                },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
}

@Composable
fun DateField(
    label: String,
    date: LocalDate,
    onDateChanged: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 6.dp))
        OutlinedButton(
            onClick = {
                DatePickerDialog(
                    context,
                    { _, year, month, day -> onDateChanged(LocalDate.of(year, month + 1, day)) },
                    date.year,
                    date.monthValue - 1,
                    date.dayOfMonth,
                ).apply {
                    datePicker.maxDate = System.currentTimeMillis()
                }.show()
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) {
            Icon(Icons.Default.CalendarMonth, contentDescription = null)
            Text(date.format(chineseDateFormatter), modifier = Modifier.padding(start = 8.dp))
        }
    }
}
