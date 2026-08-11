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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.familyledger.app.LedgerViewModel
import com.familyledger.app.SavingAction
import com.familyledger.app.data.GarmentType
import com.familyledger.app.data.LengthType
import com.familyledger.app.data.WorkItemEntity
import com.familyledger.app.data.WorkerBalanceRow
import com.familyledger.app.domain.calculateWorkAmount
import com.familyledger.app.domain.formatYuan
import com.familyledger.app.domain.parseYuanToMicros
import java.time.LocalDate
import java.util.UUID

@Composable
fun WorkEntryScreen(
    workers: List<WorkerBalanceRow>,
    workItems: List<WorkItemEntity>,
    viewModel: LedgerViewModel,
    initialWorkerId: String?,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentDate by rememberCurrentDate()
    var selectedWorkerId by remember { mutableStateOf(initialWorkerId ?: workers.firstOrNull()?.worker?.id) }
    var selectedItemId by remember { mutableStateOf(workItems.firstOrNull()?.id) }
    var date by remember { mutableStateOf(currentDate) }
    var dateFollowsToday by remember { mutableStateOf(true) }
    var quantityText by remember { mutableStateOf("") }
    var priceText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var showAddItem by remember { mutableStateOf(false) }
    var showManageItems by remember { mutableStateOf(false) }
    var pendingArchiveItem by remember { mutableStateOf<WorkItemEntity?>(null) }
    val operationId = rememberSaveable { UUID.randomUUID().toString() }
    val savingAction by viewModel.savingAction.collectAsStateWithLifecycle()
    val isSaving = savingAction == SavingAction.WORK_ENTRY

    LaunchedEffect(currentDate) {
        if (dateFollowsToday) date = currentDate
    }

    LaunchedEffect(workers) {
        if (selectedWorkerId == null || workers.none { it.worker.id == selectedWorkerId }) {
            selectedWorkerId = workers.firstOrNull()?.worker?.id
        }
    }
    LaunchedEffect(workItems) {
        if (selectedItemId == null || workItems.none { it.id == selectedItemId }) {
            selectedItemId = workItems.firstOrNull()?.id
        }
    }
    val selectedItem = workItems.firstOrNull { it.id == selectedItemId }
    LaunchedEffect(selectedItemId) {
        priceText = selectedItem?.defaultUnitPriceMicros
            ?.takeIf { it > 0 }
            ?.let { formatYuan(it).removePrefix("¥") }
            .orEmpty()
    }
    val total = quantityText.toLongOrNull()?.let { quantity ->
        parseYuanToMicros(priceText)?.let { price -> calculateWorkAmount(quantity, price) }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "返回") }
            Text("记一笔工钱", style = MaterialTheme.typography.headlineMedium)
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item {
                FormSection("1. 选择工人") {
                    WorkerSelector(workers, selectedWorkerId, { selectedWorkerId = it })
                }
            }
            item {
                FormSection("2. 选择做工项目") {
                    if (workItems.isEmpty()) {
                        Text("还没有做工项目")
                    } else {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            items(workItems, key = { it.id }) { item ->
                                FilterChip(
                                    selected = item.id == selectedItemId,
                                    onClick = { selectedItemId = item.id },
                                    label = { Text(item.name) },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                )
                            }
                        }
                        selectedItem?.let {
                            Text(
                                listOf(it.garmentType, it.lengthType, it.processName).filter(String::isNotBlank).joinToString(" · "),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { showAddItem = true }) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Text("新增项目")
                        }
                        if (workItems.isNotEmpty()) {
                            TextButton(onClick = { showManageItems = true }) {
                                Text("管理/停用项目")
                            }
                        }
                    }
                }
            }
            item {
                DateField("3. 做工日期", date, {
                    date = it
                    dateFollowsToday = false
                })
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = quantityText,
                        onValueChange = { quantityText = it.filter(Char::isDigit) },
                        label = { Text("数量") },
                        suffix = { Text(selectedItem?.unit.orEmpty()) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = priceText,
                        onValueChange = { priceText = it.filter { char -> char.isDigit() || char == '.' } },
                        label = { Text("每${selectedItem?.unit.orEmpty()}单价") },
                        prefix = { Text("¥") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
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
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(18.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("这笔工钱")
                        Text(
                            total?.let(::formatYuan) ?: "¥0.00",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            item {
                Button(
                    onClick = {
                        viewModel.addWorkEntry(
                            operationId = operationId,
                            workerId = selectedWorkerId.orEmpty(),
                            workItemId = selectedItemId.orEmpty(),
                            date = date,
                            quantityText = quantityText,
                            priceText = priceText,
                            note = note,
                            onSuccess = onSaved,
                        )
                    },
                    enabled = !isSaving && selectedWorkerId != null && selectedItemId != null && total != null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
                ) {
                    Text(if (isSaving) "正在保存…" else "确认记入账本")
                }
            }
        }
    }

    if (showAddItem) {
        AddWorkItemDialog(
            viewModel = viewModel,
            onDismiss = { showAddItem = false },
        )
    }

    if (showManageItems) {
        AlertDialog(
            onDismissRequest = { showManageItems = false },
            title = { Text("管理做工项目") },
            text = {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(workItems, key = { it.id }) { item ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(item.name, fontWeight = FontWeight.Medium)
                                    Text(
                                        listOf(item.garmentType, item.lengthType, item.processName)
                                            .filter { it.isNotBlank() && it != LengthType.NOT_APPLICABLE }
                                            .joinToString(" · "),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        pendingArchiveItem = item
                                        showManageItems = false
                                    },
                                ) {
                                    Icon(
                                        Icons.Default.DeleteOutline,
                                        contentDescription = "停用${item.name}",
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showManageItems = false }) { Text("完成") }
            },
        )
    }

    pendingArchiveItem?.let { item ->
        AlertDialog(
            onDismissRequest = {
                pendingArchiveItem = null
                showManageItems = true
            },
            title = { Text("停用${item.name}？") },
            text = {
                Text("停用后它不会再出现在记工选项中；以前使用这个项目记录的数量、单价和流水都会继续保留。")
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.archiveWorkItem(item.id) { pendingArchiveItem = null }
                }) { Text("确认停用") }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingArchiveItem = null
                    showManageItems = true
                }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun FormSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@Composable
private fun AddWorkItemDialog(viewModel: LedgerViewModel, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var garmentType by remember { mutableStateOf(GarmentType.PANTS) }
    var lengthType by remember { mutableStateOf(LengthType.LONG) }
    var processName by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("条") }
    var price by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新增做工项目") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("项目名称，例如：长裤锁边") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    Text("衣服还是裤子", style = MaterialTheme.typography.bodyMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(listOf(GarmentType.TOP, GarmentType.PANTS, GarmentType.OTHER)) { type ->
                            FilterChip(
                                selected = type == garmentType,
                                onClick = {
                                    garmentType = type
                                    lengthType = viewModel.defaultLengthType(type)
                                    unit = if (type == GarmentType.PANTS) "条" else "件"
                                },
                                label = { Text(type) },
                            )
                        }
                    }
                }
                item {
                    Text("长款还是短款", style = MaterialTheme.typography.bodyMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(listOf(LengthType.LONG, LengthType.SHORT, LengthType.NOT_APPLICABLE)) { type ->
                            FilterChip(type == lengthType, { lengthType = type }, { Text(type) })
                        }
                    }
                }
                item {
                    OutlinedTextField(
                        value = processName,
                        onValueChange = { processName = it },
                        label = { Text("工序（可以不填）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = unit,
                            onValueChange = { unit = it },
                            label = { Text("单位") },
                            singleLine = true,
                            modifier = Modifier.weight(0.8f),
                        )
                        OutlinedTextField(
                            value = price,
                            onValueChange = { price = it.filter { char -> char.isDigit() || char == '.' } },
                            label = { Text("常用单价") },
                            prefix = { Text("¥") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1.2f),
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    viewModel.addWorkItem(name, garmentType, lengthType, processName, unit, price) { onDismiss() }
                },
                enabled = name.isNotBlank() && unit.isNotBlank(),
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
