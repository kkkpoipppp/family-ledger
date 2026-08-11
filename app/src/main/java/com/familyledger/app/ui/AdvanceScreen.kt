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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.familyledger.app.LedgerViewModel
import com.familyledger.app.SavingAction
import com.familyledger.app.data.WorkerBalanceRow
import java.time.LocalDate
import java.util.UUID

@Composable
fun AdvanceScreen(
    workers: List<WorkerBalanceRow>,
    viewModel: LedgerViewModel,
    initialWorkerId: String?,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentDate by rememberCurrentDate()
    var selectedWorkerId by remember { mutableStateOf(initialWorkerId ?: workers.firstOrNull()?.worker?.id) }
    var date by remember { mutableStateOf(currentDate) }
    var dateFollowsToday by remember { mutableStateOf(true) }
    var amountText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("预支工资") }
    val operationId = rememberSaveable { UUID.randomUUID().toString() }
    val savingAction by viewModel.savingAction.collectAsStateWithLifecycle()
    val isSaving = savingAction == SavingAction.ADVANCE

    LaunchedEffect(currentDate) {
        if (dateFollowsToday) date = currentDate
    }

    LaunchedEffect(workers) {
        if (selectedWorkerId == null || workers.none { it.worker.id == selectedWorkerId }) {
            selectedWorkerId = workers.firstOrNull()?.worker?.id
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "返回") }
            Text("记一笔预支", style = MaterialTheme.typography.headlineMedium)
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item {
                Text("1. 选择工人", style = MaterialTheme.typography.titleMedium)
                WorkerSelector(
                    workers,
                    selectedWorkerId,
                    { selectedWorkerId = it },
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            item {
                DateField("2. 预支日期", date, {
                    date = it
                    dateFollowsToday = false
                })
            }
            item {
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it.filter { char -> char.isDigit() || char == '.' } },
                    label = { Text("3. 预支金额") },
                    prefix = { Text("¥") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf("500", "1000", "2000").forEach { amount ->
                        ElevatedButton(
                            onClick = { amountText = amount },
                            modifier = Modifier.weight(1f),
                        ) { Text("¥$amount") }
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注（可以不填）") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Button(
                    onClick = {
                        viewModel.addAdvance(
                            operationId = operationId,
                            workerId = selectedWorkerId.orEmpty(),
                            date = date,
                            amountText = amountText,
                            note = note,
                            onSuccess = onSaved,
                        )
                    },
                    enabled = !isSaving && selectedWorkerId != null && amountText.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
                ) { Text(if (isSaving) "正在保存…" else "确认记录预支") }
            }
        }
    }
}
