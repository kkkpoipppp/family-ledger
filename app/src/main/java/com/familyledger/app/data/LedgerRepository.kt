package com.familyledger.app.data

import android.content.Context
import androidx.room.withTransaction
import com.familyledger.app.domain.calculateWorkAmount
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.util.UUID

class LedgerRepository internal constructor(
    private val database: LedgerDatabase,
    private val deviceId: String,
) {
    private val dao = database.ledgerDao()

    val currentDeviceId: String get() = deviceId

    fun observeWorkersWithBalance(): Flow<List<WorkerBalanceRow>> = dao.observeWorkersWithBalance()
    fun observeAllWorkersWithBalance(): Flow<List<WorkerBalanceRow>> = dao.observeAllWorkersWithBalance()
    fun observeWorkers(): Flow<List<WorkerEntity>> = dao.observeWorkers()
    fun observeWorkItems(): Flow<List<WorkItemEntity>> = dao.observeWorkItems()
    fun observeRecentEntries(limit: Int = 8): Flow<List<LedgerEntryRow>> = dao.observeRecentEntries(limit)
    fun observeEntriesForWorker(workerId: String): Flow<List<LedgerEntryRow>> = dao.observeEntriesForWorker(workerId)
    fun observeSettlementsForWorker(workerId: String): Flow<List<SettlementWithLines>> =
        dao.observeSettlementsForWorker(workerId)

    suspend fun seedDefaultWorkItems() {
        val now = System.currentTimeMillis()
        dao.insertBuiltInWorkItems(
            listOf(
                WorkItemEntity(
                    id = BUILT_IN_LONG_PANTS_ID,
                    name = "长裤",
                    garmentType = GarmentType.PANTS,
                    lengthType = LengthType.LONG,
                    unit = "条",
                    sortOrder = 10,
                    createdAt = now,
                    updatedAt = now,
                    originDeviceId = BUILT_IN_ORIGIN,
                ),
                WorkItemEntity(
                    id = BUILT_IN_SHORT_PANTS_ID,
                    name = "短裤",
                    garmentType = GarmentType.PANTS,
                    lengthType = LengthType.SHORT,
                    unit = "条",
                    sortOrder = 20,
                    createdAt = now,
                    updatedAt = now,
                    originDeviceId = BUILT_IN_ORIGIN,
                ),
                WorkItemEntity(
                    id = BUILT_IN_TOP_ID,
                    name = "上衣",
                    garmentType = GarmentType.TOP,
                    lengthType = LengthType.NOT_APPLICABLE,
                    unit = "件",
                    sortOrder = 30,
                    createdAt = now,
                    updatedAt = now,
                    originDeviceId = BUILT_IN_ORIGIN,
                ),
            ),
        )
    }

    suspend fun addWorker(name: String, note: String = "") {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "工人姓名不能为空" }
        val now = System.currentTimeMillis()
        dao.insertWorker(
            WorkerEntity(
                id = UUID.randomUUID().toString(),
                name = cleanName,
                note = note.trim(),
                createdAt = now,
                updatedAt = now,
                originDeviceId = deviceId,
            ),
        )
    }

    suspend fun addWorkItem(
        name: String,
        garmentType: String,
        lengthType: String,
        processName: String,
        unit: String,
        defaultUnitPriceMicros: Long,
    ) {
        require(name.trim().isNotEmpty()) { "项目名称不能为空" }
        require(unit.trim().isNotEmpty()) { "单位不能为空" }
        require(defaultUnitPriceMicros >= 0) { "单价不能小于零" }
        val now = System.currentTimeMillis()
        dao.insertWorkItem(
            WorkItemEntity(
                id = UUID.randomUUID().toString(),
                name = name.trim(),
                garmentType = garmentType,
                lengthType = lengthType,
                processName = processName.trim(),
                unit = unit.trim(),
                defaultUnitPriceMicros = defaultUnitPriceMicros,
                sortOrder = Int.MAX_VALUE,
                createdAt = now,
                updatedAt = now,
                originDeviceId = deviceId,
            ),
        )
    }

    suspend fun offboardWorker(workerId: String, force: Boolean) {
        val worker = requireNotNull(dao.workerById(workerId)) { "找不到这位在职工人" }
        val balance = dao.workerBalance(worker.id)
        require(force || balance == 0L) { "这位工人的工资还没有结清" }
        require(dao.archiveWorker(worker.id, System.currentTimeMillis(), deviceId) == 1) {
            "工人状态已经变化，请刷新后再试"
        }
    }

    suspend fun restoreWorker(workerId: String) {
        require(dao.restoreWorker(workerId, System.currentTimeMillis(), deviceId) == 1) {
            "这位工人已经是在职状态"
        }
    }

    suspend fun archiveWorkItem(workItemId: String) {
        requireNotNull(dao.workItemById(workItemId)) { "找不到这个做工项目" }
        require(dao.archiveWorkItem(workItemId, System.currentTimeMillis(), deviceId) == 1) {
            "项目状态已经变化，请刷新后再试"
        }
    }

    suspend fun addWorkEntry(
        operationId: String,
        workerId: String,
        workItemId: String,
        date: LocalDate,
        quantity: Long,
        unitPriceMicros: Long,
        note: String,
    ) {
        require(operationId.isNotBlank()) { "操作编号不能为空" }
        require(!date.isAfter(LocalDate.now())) { "做工日期不能晚于今天" }
        val worker = requireNotNull(dao.workerById(workerId)) { "找不到这位工人" }
        val item = requireNotNull(dao.workItemById(workItemId)) { "找不到这个做工项目" }
        val amount = requireNotNull(calculateWorkAmount(quantity, unitPriceMicros)) { "数量或单价不正确" }
        val now = System.currentTimeMillis()
        database.withTransaction {
            val inserted = dao.insertEntryIfAbsent(
                LedgerEntryEntity(
                    id = operationId,
                    workerId = worker.id,
                    entryType = EntryType.WORK,
                    entryEpochDay = date.toEpochDay(),
                    description = item.name,
                    workItemId = item.id,
                    garmentTypeSnapshot = item.garmentType,
                    lengthTypeSnapshot = item.lengthType,
                    processNameSnapshot = item.processName,
                    quantity = quantity,
                    unitSnapshot = item.unit,
                    unitPriceMicros = unitPriceMicros,
                    amountMicros = amount,
                    note = note.trim(),
                    createdAt = now,
                    updatedAt = now,
                    originDeviceId = deviceId,
                ),
            )
            if (inserted != -1L && item.defaultUnitPriceMicros != unitPriceMicros) {
                dao.updateWorkItemPrice(item.id, unitPriceMicros, now, deviceId)
            }
        }
    }

    suspend fun addAdvance(
        operationId: String,
        workerId: String,
        date: LocalDate,
        amountMicros: Long,
        note: String,
    ) {
        require(operationId.isNotBlank()) { "操作编号不能为空" }
        require(!date.isAfter(LocalDate.now())) { "预支日期不能晚于今天" }
        val worker = requireNotNull(dao.workerById(workerId)) { "找不到这位工人" }
        require(amountMicros > 0) { "预支金额必须大于零" }
        val now = System.currentTimeMillis()
        dao.insertEntryIfAbsent(
            LedgerEntryEntity(
                id = operationId,
                workerId = worker.id,
                entryType = EntryType.ADVANCE,
                entryEpochDay = date.toEpochDay(),
                description = "预支工资",
                amountMicros = -amountMicros,
                note = note.trim(),
                createdAt = now,
                updatedAt = now,
                originDeviceId = deviceId,
            ),
        )
    }

    suspend fun settlementTotals(workerId: String, start: LocalDate, end: LocalDate): SettlementTotalsRow {
        require(!end.isBefore(start)) { "结束日期不能早于开始日期" }
        return dao.settlementTotals(workerId, start.toEpochDay(), end.toEpochDay())
    }

    suspend fun settlementPreview(workerId: String, start: LocalDate, end: LocalDate): SettlementPreviewData {
        require(!end.isBefore(start)) { "结束日期不能早于开始日期" }
        require(!end.isAfter(LocalDate.now())) { "结算日期不能晚于今天" }
        requireNotNull(dao.workerById(workerId)) { "找不到这位工人" }
        return SettlementPreviewData(
            totals = dao.settlementTotals(workerId, start.toEpochDay(), end.toEpochDay()),
            workSummary = dao.workSummary(workerId, start.toEpochDay(), end.toEpochDay()),
        )
    }

    suspend fun settle(
        operationId: String,
        workerId: String,
        start: LocalDate,
        end: LocalDate,
    ): SettlementEntity {
        require(operationId.isNotBlank()) { "操作编号不能为空" }
        require(!end.isBefore(start)) { "结束日期不能早于开始日期" }
        require(!end.isAfter(LocalDate.now())) { "结算日期不能晚于今天" }
        return database.withTransaction {
            dao.settlementById(operationId)?.let { existing ->
                require(
                    existing.workerId == workerId &&
                        existing.startEpochDay == start.toEpochDay() &&
                        existing.endEpochDay == end.toEpochDay(),
                ) { "这个操作编号已经用于其他结算" }
                return@withTransaction existing
            }
            val worker = requireNotNull(dao.workerById(workerId)) { "找不到这位工人" }
            val totals = dao.settlementTotals(worker.id, start.toEpochDay(), end.toEpochDay())
            val summary = dao.workSummary(worker.id, start.toEpochDay(), end.toEpochDay())
            require(totals.balanceMicros > 0) { "这个日期区间没有需要支付的余额" }
            val now = System.currentTimeMillis()
            val settlementId = operationId
            val settlement = SettlementEntity(
                id = settlementId,
                workerId = worker.id,
                startEpochDay = start.toEpochDay(),
                endEpochDay = end.toEpochDay(),
                earnedMicros = totals.earnedMicros,
                advancesMicros = totals.advancesMicros,
                paymentsMicros = totals.paymentsMicros,
                adjustmentsMicros = totals.adjustmentsMicros,
                balanceMicros = totals.balanceMicros,
                settledPaymentMicros = totals.balanceMicros,
                createdAt = now,
                updatedAt = now,
                originDeviceId = deviceId,
            )
            dao.insertSettlement(settlement)
            if (summary.isNotEmpty()) {
                dao.insertSettlementLines(
                    summary.mapIndexed { index, line ->
                        SettlementLineEntity(
                            id = "$settlementId-line-$index",
                            settlementId = settlementId,
                            workItemId = line.workItemId,
                            description = line.description,
                            garmentTypeSnapshot = line.garmentTypeSnapshot,
                            lengthTypeSnapshot = line.lengthTypeSnapshot,
                            processNameSnapshot = line.processNameSnapshot,
                            quantity = line.quantity,
                            unitSnapshot = line.unitSnapshot,
                            amountMicros = line.amountMicros,
                        )
                    },
                )
            }
            dao.insertEntry(
                LedgerEntryEntity(
                    id = "$settlementId-payment",
                    workerId = worker.id,
                    entryType = EntryType.PAYMENT,
                    entryEpochDay = end.toEpochDay(),
                    description = "工资结清",
                    amountMicros = -totals.balanceMicros,
                    note = "${start} 至 ${end}",
                    settlementId = settlementId,
                    createdAt = now,
                    updatedAt = now,
                    originDeviceId = deviceId,
                ),
            )
            settlement
        }
    }

    suspend fun reverseSettlement(
        operationId: String,
        settlementId: String,
        reason: String,
    ) {
        require(operationId.isNotBlank()) { "操作编号不能为空" }
        val cleanReason = reason.trim()
        require(cleanReason.isNotEmpty()) { "请填写撤销原因" }
        database.withTransaction {
            val settlement = requireNotNull(dao.settlementById(settlementId)) { "找不到这笔结算" }
            if (settlement.reversedAt != null) return@withTransaction
            val now = System.currentTimeMillis()
            val changed = dao.markSettlementReversed(
                settlementId = settlement.id,
                reversedAt = now,
                reason = cleanReason,
                reversalEntryId = operationId,
                deviceId = deviceId,
            )
            if (changed == 0) return@withTransaction
            dao.insertEntry(
                LedgerEntryEntity(
                    id = operationId,
                    workerId = settlement.workerId,
                    entryType = EntryType.ADJUSTMENT,
                    entryEpochDay = settlement.endEpochDay,
                    description = "撤销结算",
                    amountMicros = settlement.settledPaymentMicros,
                    note = cleanReason,
                    settlementId = settlement.id,
                    createdAt = now,
                    updatedAt = now,
                    originDeviceId = deviceId,
                ),
            )
        }
    }

    suspend fun softDeleteEntry(entryId: String) {
        val entry = requireNotNull(dao.ledgerEntryById(entryId)) { "找不到这笔流水" }
        require(entry.settlementId == null) { "结算付款不能单独撤销" }
        dao.softDeleteEntry(entryId, System.currentTimeMillis(), deviceId)
    }

    suspend fun requireWorkerName(workerId: String, confirmedName: String) {
        val worker = requireNotNull(dao.workerAnyById(workerId)) { "找不到这位工人" }
        require(confirmedName.trim() == worker.name) { "输入的姓名不一致，未执行永久删除" }
    }

    suspend fun permanentlyDeleteWorker(workerId: String, confirmedName: String) {
        database.withTransaction {
            val worker = requireNotNull(dao.workerAnyById(workerId)) { "找不到这位工人" }
            require(confirmedName.trim() == worker.name) { "输入的姓名不一致，未执行永久删除" }
            dao.restorePurgedWorkers(
                listOf(
                    PurgedWorkerEntity(
                        id = worker.id,
                        purgedAt = System.currentTimeMillis(),
                        originDeviceId = deviceId,
                    ),
                ),
            )
            purgeWorkerData(worker.id)
        }
    }

    suspend fun backupSnapshot(): LedgerBackup = database.withTransaction {
        LedgerBackup(
            workers = dao.allWorkersSnapshot(),
            workItems = dao.allWorkItemsSnapshot(),
            entries = dao.allEntriesSnapshot(),
            settlements = dao.allSettlementsSnapshot(),
            settlementLines = dao.allSettlementLinesSnapshot(),
            purgedWorkers = dao.allPurgedWorkersSnapshot(),
        )
    }

    suspend fun restoreBackup(backup: LedgerBackup) {
        database.withTransaction {
            val purgedWorkers = (dao.allPurgedWorkersSnapshot() + backup.purgedWorkers)
                .groupBy(PurgedWorkerEntity::id)
                .map { (_, records) ->
                    records.maxWith(compareBy(PurgedWorkerEntity::purgedAt, PurgedWorkerEntity::originDeviceId))
                }
            dao.restorePurgedWorkers(purgedWorkers)
            purgedWorkers.forEach { purgeWorkerData(it.id) }
            val purgedIds = purgedWorkers.mapTo(mutableSetOf(), PurgedWorkerEntity::id)
            val incomingWorkers = backup.workers.filterNot { it.id in purgedIds }
            val incomingSettlements = backup.settlements.filterNot { it.workerId in purgedIds }
            val incomingSettlementIds = incomingSettlements.mapTo(mutableSetOf(), SettlementEntity::id)
            val incomingEntries = backup.entries.filterNot { it.workerId in purgedIds }
            val incomingLines = backup.settlementLines.filter { it.settlementId in incomingSettlementIds }
            dao.restoreWorkers(
                newerIncomingRecords(
                    dao.allWorkersSnapshot(), incomingWorkers,
                    WorkerEntity::id, WorkerEntity::revision, WorkerEntity::updatedAt, WorkerEntity::originDeviceId,
                ),
            )
            dao.restoreWorkItems(
                newerIncomingRecords(
                    dao.allWorkItemsSnapshot(), backup.workItems,
                    WorkItemEntity::id, WorkItemEntity::revision, WorkItemEntity::updatedAt, WorkItemEntity::originDeviceId,
                ),
            )
            dao.restoreSettlements(
                newerIncomingRecords(
                    dao.allSettlementsSnapshot(), incomingSettlements,
                    SettlementEntity::id, SettlementEntity::revision, SettlementEntity::updatedAt, SettlementEntity::originDeviceId,
                ),
            )
            dao.restoreSettlementLines(incomingLines)
            dao.restoreEntries(
                newerIncomingRecords(
                    dao.allEntriesSnapshot(), incomingEntries,
                    LedgerEntryEntity::id, LedgerEntryEntity::revision, LedgerEntryEntity::updatedAt, LedgerEntryEntity::originDeviceId,
                ),
            )
        }
    }

    private suspend fun purgeWorkerData(workerId: String) {
        dao.deleteSettlementLinesForWorker(workerId)
        dao.deleteEntriesForWorker(workerId)
        dao.deleteSettlementsForWorker(workerId)
        dao.deleteWorker(workerId)
    }

    private fun <T> newerIncomingRecords(
        local: List<T>,
        incoming: List<T>,
        id: (T) -> String,
        revision: (T) -> Long,
        updatedAt: (T) -> Long,
        originDeviceId: (T) -> String,
    ): List<T> {
        val localById = local.associateBy(id)
        return incoming.filter { candidate ->
            val current = localById[id(candidate)] ?: return@filter true
            compareValuesBy(candidate, current, revision, updatedAt, originDeviceId) > 0
        }
    }

    companion object {
        private const val BUILT_IN_ORIGIN = "built-in-v1"
        private const val BUILT_IN_LONG_PANTS_ID = "00000000-0000-5000-8000-000000000001"
        private const val BUILT_IN_SHORT_PANTS_ID = "00000000-0000-5000-8000-000000000002"
        private const val BUILT_IN_TOP_ID = "00000000-0000-5000-8000-000000000003"

        @Volatile
        private var instance: LedgerRepository? = null

        fun getInstance(context: Context): LedgerRepository =
            instance ?: synchronized(this) {
                instance ?: LedgerRepository(
                    database = LedgerDatabase.getInstance(context),
                    deviceId = DeviceIdentity(context).id,
                ).also { instance = it }
            }
    }
}

data class LedgerBackup(
    val workers: List<WorkerEntity>,
    val workItems: List<WorkItemEntity>,
    val entries: List<LedgerEntryEntity>,
    val settlements: List<SettlementEntity>,
    val settlementLines: List<SettlementLineEntity> = emptyList(),
    val purgedWorkers: List<PurgedWorkerEntity> = emptyList(),
)
