package com.familyledger.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.familyledger.app.LedgerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.time.LocalDate

private const val MAX_BACKUP_CHARS = 20 * 1024 * 1024

private enum class Destination {
    HOME,
    WORKERS,
    LEDGER,
    SETTLEMENT,
    WORK_ENTRY,
    ADVANCE,
    BACKUP,
}

@Composable
fun LedgerApp(viewModel: LedgerViewModel = viewModel()) {
    val context = LocalContext.current
    val workers by viewModel.workers.collectAsStateWithLifecycle()
    val allWorkers by viewModel.allWorkers.collectAsStateWithLifecycle()
    val workItems by viewModel.workItems.collectAsStateWithLifecycle()
    val recentEntries by viewModel.recentEntries.collectAsStateWithLifecycle()
    val cloudSyncState by viewModel.cloudSyncState.collectAsStateWithLifecycle()
    var destination by rememberSaveable { mutableStateOf(Destination.HOME) }
    var selectedWorkerId by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    val json = viewModel.exportBackupJson()
                    withContext(Dispatchers.IO) {
                        val output = requireNotNull(context.contentResolver.openOutputStream(uri, "wt"))
                        output.bufferedWriter(Charsets.UTF_8).use { it.write(json) }
                    }
                }.onSuccess {
                    snackbarHostState.showSnackbar("备份文件已保存")
                }.onFailure {
                    snackbarHostState.showSnackbar(it.message ?: "备份失败")
                }
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    val json = withContext(Dispatchers.IO) {
                        val input = requireNotNull(context.contentResolver.openInputStream(uri))
                        input.use(InputStream::readBackupText)
                    }
                    viewModel.importBackupJson(json)
                }.onFailure {
                    snackbarHostState.showSnackbar(it.message ?: "恢复失败")
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }
    LaunchedEffect(allWorkers) {
        if (selectedWorkerId == null || allWorkers.none { it.worker.id == selectedWorkerId }) {
            selectedWorkerId = allWorkers.firstOrNull()?.worker?.id
        }
    }
    LaunchedEffect(destination) {
        if (destination == Destination.HOME) viewModel.syncNow(showMessage = false)
    }

    val isMainDestination = destination in setOf(
        Destination.HOME,
        Destination.WORKERS,
        Destination.LEDGER,
        Destination.SETTLEMENT,
    )
    BackHandler(enabled = destination != Destination.HOME) {
        destination = Destination.HOME
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (isMainDestination) {
                NavigationBar {
                    NavigationBarItem(
                        selected = destination == Destination.HOME,
                        onClick = { destination = Destination.HOME },
                        icon = { Icon(Icons.Default.Home, contentDescription = null) },
                        label = { Text("首页") },
                    )
                    NavigationBarItem(
                        selected = destination == Destination.WORKERS,
                        onClick = { destination = Destination.WORKERS },
                        icon = { Icon(Icons.Default.Groups, contentDescription = null) },
                        label = { Text("工人") },
                    )
                    NavigationBarItem(
                        selected = destination == Destination.LEDGER,
                        onClick = { destination = Destination.LEDGER },
                        icon = { Icon(Icons.Default.MenuBook, contentDescription = null) },
                        label = { Text("账本") },
                    )
                    NavigationBarItem(
                        selected = destination == Destination.SETTLEMENT,
                        onClick = { destination = Destination.SETTLEMENT },
                        icon = { Icon(Icons.Default.ReceiptLong, contentDescription = null) },
                        label = { Text("结算") },
                    )
                }
            }
        },
    ) { padding ->
        val screenModifier = Modifier.padding(padding)
        when (destination) {
            Destination.HOME -> HomeScreen(
                workers = workers,
                recentEntries = recentEntries,
                onWorkEntry = {
                    destination = if (workers.isEmpty()) Destination.WORKERS else Destination.WORK_ENTRY
                },
                onAdvance = {
                    destination = if (workers.isEmpty()) Destination.WORKERS else Destination.ADVANCE
                },
                onWorkers = { destination = Destination.WORKERS },
                onSettlement = { destination = Destination.SETTLEMENT },
                onBackup = { destination = Destination.BACKUP },
                cloudSyncState = cloudSyncState,
                modifier = screenModifier,
            )
            Destination.WORKERS -> WorkersScreen(
                workers = allWorkers,
                viewModel = viewModel,
                onBack = { destination = Destination.HOME },
                onWorkerClick = {
                    selectedWorkerId = it
                    destination = Destination.LEDGER
                },
                modifier = screenModifier,
            )
            Destination.LEDGER -> LedgerScreen(
                workerId = selectedWorkerId,
                workers = allWorkers,
                viewModel = viewModel,
                onBack = { destination = Destination.HOME },
                onWorkerSelected = { selectedWorkerId = it },
                modifier = screenModifier,
            )
            Destination.SETTLEMENT -> SettlementScreen(
                workers = workers,
                viewModel = viewModel,
                initialWorkerId = selectedWorkerId,
                onBack = { destination = Destination.HOME },
                modifier = screenModifier,
            )
            Destination.WORK_ENTRY -> WorkEntryScreen(
                workers = workers,
                workItems = workItems,
                viewModel = viewModel,
                initialWorkerId = selectedWorkerId,
                onBack = { destination = Destination.HOME },
                onSaved = { destination = Destination.HOME },
                modifier = screenModifier,
            )
            Destination.ADVANCE -> AdvanceScreen(
                workers = workers,
                viewModel = viewModel,
                initialWorkerId = selectedWorkerId,
                onBack = { destination = Destination.HOME },
                onSaved = { destination = Destination.HOME },
                modifier = screenModifier,
            )
            Destination.BACKUP -> BackupScreen(
                syncState = cloudSyncState,
                onBack = { destination = Destination.HOME },
                onConfigureSync = viewModel::configureCloudSync,
                onSyncNow = { viewModel.syncNow() },
                onDisableSync = viewModel::disableCloudSync,
                onExport = {
                    exportLauncher.launch("家庭工账-${LocalDate.now()}.json")
                },
                onImport = { importLauncher.launch(arrayOf("application/json", "text/plain")) },
                modifier = screenModifier,
            )
        }
    }
}

private fun InputStream.readBackupText(): String {
    val result = StringBuilder()
    bufferedReader(Charsets.UTF_8).use { reader ->
        val buffer = CharArray(8_192)
        while (true) {
            val count = reader.read(buffer)
            if (count < 0) break
            require(result.length + count <= MAX_BACKUP_CHARS) { "备份文件过大，不能超过 20 MB" }
            result.append(buffer, 0, count)
        }
    }
    return result.toString()
}
