package com.familyledger.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.familyledger.app.data.BackupCodec
import com.familyledger.app.data.LedgerRepository
import com.familyledger.app.data.LengthType
import com.familyledger.app.data.SettlementPreviewData
import com.familyledger.app.data.SettlementWithLines
import com.familyledger.app.domain.parseYuanToMicros
import com.familyledger.app.sync.CloudSyncManager
import com.familyledger.app.sync.CloudSyncWork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.UUID

object SavingAction {
    const val WORK_ENTRY = "work-entry"
    const val ADVANCE = "advance"
    const val SETTLEMENT = "settlement"
    const val REVERSAL = "reversal"
    const val PURGE_WORKER = "purge-worker"
}

data class SettlementRequest(
    val workerId: String,
    val start: LocalDate,
    val end: LocalDate,
)

data class SettlementPreview(
    val request: SettlementRequest,
    val data: SettlementPreviewData,
    val operationId: String,
)

class LedgerViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = LedgerRepository.getInstance(application)
    private val cloudSync = CloudSyncManager.getInstance(application)

    val cloudSyncState = cloudSync.state

    val workers = repository.observeWorkersWithBalance().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )
    val allWorkers = repository.observeAllWorkersWithBalance().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )
    val workItems = repository.observeWorkItems().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )
    val recentEntries = repository.observeRecentEntries().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()

    private val _settlementPreview = MutableStateFlow<SettlementPreview?>(null)
    val settlementPreview = _settlementPreview.asStateFlow()

    private val _savingAction = MutableStateFlow<String?>(null)
    val savingAction = _savingAction.asStateFlow()

    private val mutationMutex = Mutex()
    private var settlementJob: Job? = null
    private var currentSettlementRequest: SettlementRequest? = null

    init {
        viewModelScope.launch {
            runCatching {
                repository.seedDefaultWorkItems()
                if (cloudSync.hasConfiguration()) {
                    CloudSyncWork.schedule(getApplication())
                    cloudSync.syncNow()
                }
            }
                .onFailure { _messages.emit(it.userMessage()) }
        }
    }

    fun entriesForWorker(workerId: String) = repository.observeEntriesForWorker(workerId)
    fun settlementsForWorker(workerId: String): kotlinx.coroutines.flow.Flow<List<SettlementWithLines>> =
        repository.observeSettlementsForWorker(workerId)

    fun addWorker(name: String, note: String, onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            runCatching { repository.addWorker(name, note) }
                .onSuccess {
                    _messages.emit("工人已添加")
                    onSuccess()
                    syncAfterLocalChange()
                }
                .onFailure { _messages.emit(it.userMessage()) }
        }
    }

    fun addWorkItem(
        name: String,
        garmentType: String,
        lengthType: String,
        processName: String,
        unit: String,
        priceText: String,
        onSuccess: () -> Unit = {},
    ) {
        viewModelScope.launch {
            val price = if (priceText.isBlank()) 0 else parseYuanToMicros(priceText)
            if (price == null) {
                _messages.emit("单价格式不正确，最多填写四位小数")
                return@launch
            }
            runCatching {
                repository.addWorkItem(name, garmentType, lengthType, processName, unit, price)
            }.onSuccess {
                _messages.emit("做工项目已添加")
                onSuccess()
                syncAfterLocalChange()
            }.onFailure { _messages.emit(it.userMessage()) }
        }
    }

    fun offboardWorker(workerId: String, force: Boolean, onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            runCatching { repository.offboardWorker(workerId, force) }
                .onSuccess {
                    _messages.emit("已设为离职，历史流水仍然保留")
                    onSuccess()
                    syncAfterLocalChange()
                }
                .onFailure { _messages.emit(it.userMessage()) }
        }
    }

    fun restoreWorker(workerId: String) {
        viewModelScope.launch {
            runCatching { repository.restoreWorker(workerId) }
                .onSuccess {
                    _messages.emit("已恢复为在职工人")
                    syncAfterLocalChange()
                }
                .onFailure { _messages.emit(it.userMessage()) }
        }
    }

    fun permanentlyDeleteWorker(
        workerId: String,
        confirmedName: String,
        onSuccess: () -> Unit = {},
    ) {
        runMutation(
            action = SavingAction.PURGE_WORKER,
            successMessage = "这位工人及其全部账务资料已永久删除",
            onSuccess = onSuccess,
        ) {
            repository.requireWorkerName(workerId, confirmedName)
            if (cloudSync.hasConfiguration()) {
                cloudSync.purgeWorker(workerId)
            } else {
                repository.permanentlyDeleteWorker(workerId, confirmedName)
            }
        }
    }

    fun archiveWorkItem(workItemId: String, onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            runCatching { repository.archiveWorkItem(workItemId) }
                .onSuccess {
                    _messages.emit("做工项目已停用，历史流水仍然保留")
                    onSuccess()
                    syncAfterLocalChange()
                }
                .onFailure { _messages.emit(it.userMessage()) }
        }
    }

    fun addWorkEntry(
        operationId: String,
        workerId: String,
        workItemId: String,
        date: LocalDate,
        quantityText: String,
        priceText: String,
        note: String,
        onSuccess: () -> Unit,
    ) {
        runMutation(SavingAction.WORK_ENTRY, "工钱流水已记入", onSuccess) {
            val quantity = quantityText.trim().toLongOrNull()
            val price = parseYuanToMicros(priceText)
            require(quantity != null && quantity > 0) { "请输入正确的数量" }
            require(price != null && price > 0) { "请输入正确的单价，最多四位小数" }
            repository.addWorkEntry(operationId, workerId, workItemId, date, quantity, price, note)
        }
    }

    fun addAdvance(
        operationId: String,
        workerId: String,
        date: LocalDate,
        amountText: String,
        note: String,
        onSuccess: () -> Unit,
    ) {
        runMutation(SavingAction.ADVANCE, "预支已记入", onSuccess) {
            val amount = parseYuanToMicros(amountText)
            require(amount != null && amount > 0) { "请输入正确的预支金额" }
            repository.addAdvance(operationId, workerId, date, amount, note)
        }
    }

    fun loadSettlement(workerId: String, start: LocalDate, end: LocalDate) {
        val request = SettlementRequest(workerId, start, end)
        currentSettlementRequest = request
        settlementJob?.cancel()
        _settlementPreview.value = null
        settlementJob = viewModelScope.launch {
            runCatching {
                if (cloudSync.hasConfiguration()) cloudSync.syncNow()
                repository.settlementPreview(workerId, start, end)
            }
                .onSuccess {
                    if (currentSettlementRequest == request) {
                        _settlementPreview.value = SettlementPreview(
                            request = request,
                            data = it,
                            operationId = UUID.randomUUID().toString(),
                        )
                    }
                }
                .onFailure {
                    if (currentSettlementRequest == request) {
                        _settlementPreview.value = null
                        _messages.emit(it.userMessage())
                    }
                }
        }
    }

    fun clearSettlementPreview() {
        settlementJob?.cancel()
        currentSettlementRequest = null
        _settlementPreview.value = null
    }

    fun settle(preview: SettlementPreview, onSuccess: () -> Unit) {
        runMutation(SavingAction.SETTLEMENT, "这个日期区间已结清", {
            loadSettlement(preview.request.workerId, preview.request.start, preview.request.end)
            onSuccess()
        }) {
            require(currentSettlementRequest == preview.request) { "结算条件已经改变，请重新确认金额" }
            if (cloudSync.hasConfiguration()) {
                cloudSync.settle(
                    operationId = preview.operationId,
                    workerId = preview.request.workerId,
                    startEpochDay = preview.request.start.toEpochDay(),
                    endEpochDay = preview.request.end.toEpochDay(),
                )
            } else {
                repository.settle(
                    operationId = preview.operationId,
                    workerId = preview.request.workerId,
                    start = preview.request.start,
                    end = preview.request.end,
                )
            }
        }
    }

    fun reverseSettlement(
        operationId: String,
        settlementId: String,
        reason: String,
        onSuccess: () -> Unit,
    ) {
        runMutation(SavingAction.REVERSAL, "结算已撤销，原付款和冲正流水都已保留", onSuccess) {
            if (cloudSync.hasConfiguration()) {
                cloudSync.reverseSettlement(operationId, settlementId, reason)
            } else {
                repository.reverseSettlement(operationId, settlementId, reason)
            }
        }
    }

    fun deleteEntry(entryId: String) {
        viewModelScope.launch {
            runCatching { repository.softDeleteEntry(entryId) }
                .onSuccess {
                    _messages.emit("流水已撤销，记录仍保留在数据库中")
                    syncAfterLocalChange()
                }
                .onFailure { _messages.emit(it.userMessage()) }
        }
    }

    suspend fun exportBackupJson(): String {
        val snapshot = repository.backupSnapshot()
        return withContext(Dispatchers.Default) { BackupCodec.encode(snapshot) }
    }

    suspend fun importBackupJson(json: String) {
        val backup = withContext(Dispatchers.Default) { BackupCodec.decode(json) }
        repository.restoreBackup(backup)
        _messages.emit("备份已恢复")
        syncAfterLocalChange()
    }

    fun configureCloudSync(endpoint: String, syncKey: String) {
        viewModelScope.launch {
            runCatching { cloudSync.configure(endpoint, syncKey) }
                .onSuccess {
                    CloudSyncWork.ensurePeriodic(getApplication())
                    _messages.emit("云同步已开启，两部手机可以共同记账")
                }
                .onFailure { _messages.emit(it.userMessage()) }
        }
    }

    fun syncNow(showMessage: Boolean = true) {
        if (!cloudSync.hasConfiguration()) return
        viewModelScope.launch {
            runCatching { cloudSync.syncNow() }
                .onSuccess { if (showMessage) _messages.emit("云端账本已同步") }
                .onFailure {
                    CloudSyncWork.enqueue(getApplication())
                    if (showMessage) _messages.emit(it.userMessage())
                }
        }
    }

    fun disableCloudSync() {
        CloudSyncWork.cancel(getApplication())
        viewModelScope.launch {
            cloudSync.disable()
            _messages.emit("云同步已关闭，本机账本仍然保留")
        }
    }

    fun defaultLengthType(garmentType: String): String =
        if (garmentType == "裤子") LengthType.LONG else LengthType.NOT_APPLICABLE

    private fun Throwable.userMessage(): String = message?.takeIf { it.isNotBlank() } ?: "操作失败，请再试一次"

    private fun runMutation(
        action: String,
        successMessage: String,
        onSuccess: () -> Unit = {},
        block: suspend () -> Unit,
    ) {
        viewModelScope.launch {
            if (!mutationMutex.tryLock()) return@launch
            _savingAction.value = action
            try {
                block()
                _messages.emit(successMessage)
                onSuccess()
                syncAfterLocalChange()
            } catch (error: Throwable) {
                _messages.emit(error.userMessage())
            } finally {
                _savingAction.value = null
                mutationMutex.unlock()
            }
        }
    }

    private fun syncAfterLocalChange() {
        if (!cloudSync.hasConfiguration()) return
        viewModelScope.launch {
            runCatching { cloudSync.syncNow() }
                .onFailure { CloudSyncWork.enqueue(getApplication()) }
        }
    }
}
