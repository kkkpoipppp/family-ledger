package com.familyledger.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface LedgerDao {
    @Query(
        """
        SELECT w.*,
               COALESCE(SUM(CASE WHEN e.isDeleted = 0 THEN e.amountMicros ELSE 0 END), 0) AS balanceMicros,
               COUNT(CASE WHEN e.isDeleted = 0 THEN 1 END) AS entryCount
        FROM workers w
        LEFT JOIN ledger_entries e ON e.workerId = w.id
        WHERE w.isDeleted = 0
        GROUP BY w.id
        ORDER BY w.name COLLATE NOCASE
        """,
    )
    fun observeWorkersWithBalance(): Flow<List<WorkerBalanceRow>>

    @Query(
        """
        SELECT w.*,
               COALESCE(SUM(CASE WHEN e.isDeleted = 0 THEN e.amountMicros ELSE 0 END), 0) AS balanceMicros,
               COUNT(CASE WHEN e.isDeleted = 0 THEN 1 END) AS entryCount
        FROM workers w
        LEFT JOIN ledger_entries e ON e.workerId = w.id
        GROUP BY w.id
        ORDER BY w.isDeleted, w.name COLLATE NOCASE
        """,
    )
    fun observeAllWorkersWithBalance(): Flow<List<WorkerBalanceRow>>

    @Query("SELECT * FROM workers WHERE isDeleted = 0 ORDER BY name COLLATE NOCASE")
    fun observeWorkers(): Flow<List<WorkerEntity>>

    @Query("SELECT * FROM work_items WHERE isDeleted = 0 ORDER BY sortOrder, name COLLATE NOCASE")
    fun observeWorkItems(): Flow<List<WorkItemEntity>>

    @Query(
        """
        SELECT e.*, w.name AS workerName
        FROM ledger_entries e
        INNER JOIN workers w ON w.id = e.workerId
        WHERE e.isDeleted = 0
        ORDER BY e.entryEpochDay DESC, e.createdAt DESC
        LIMIT :limit
        """,
    )
    fun observeRecentEntries(limit: Int): Flow<List<LedgerEntryRow>>

    @Query(
        """
        SELECT e.*, w.name AS workerName
        FROM ledger_entries e
        INNER JOIN workers w ON w.id = e.workerId
        WHERE e.workerId = :workerId AND e.isDeleted = 0
        ORDER BY e.entryEpochDay DESC, e.createdAt DESC
        """,
    )
    fun observeEntriesForWorker(workerId: String): Flow<List<LedgerEntryRow>>

    @Query(
        """
        SELECT
            COALESCE(SUM(CASE WHEN entryType = 'WORK' THEN amountMicros ELSE 0 END), 0) AS earnedMicros,
            COALESCE(-SUM(CASE WHEN entryType = 'ADVANCE' THEN amountMicros ELSE 0 END), 0) AS advancesMicros,
            COALESCE(-SUM(CASE WHEN entryType = 'PAYMENT' THEN amountMicros ELSE 0 END), 0) AS paymentsMicros,
            COALESCE(SUM(CASE WHEN entryType = 'ADJUSTMENT' THEN amountMicros ELSE 0 END), 0) AS adjustmentsMicros,
            COALESCE(SUM(amountMicros), 0) AS balanceMicros
        FROM ledger_entries
        WHERE workerId = :workerId
          AND entryEpochDay BETWEEN :startEpochDay AND :endEpochDay
          AND isDeleted = 0
        """,
    )
    suspend fun settlementTotals(
        workerId: String,
        startEpochDay: Long,
        endEpochDay: Long,
    ): SettlementTotalsRow

    @Query(
        """
        SELECT workItemId,
               description,
               garmentTypeSnapshot,
               lengthTypeSnapshot,
               processNameSnapshot,
               COALESCE(SUM(quantity), 0) AS quantity,
               unitSnapshot,
               COALESCE(SUM(amountMicros), 0) AS amountMicros
        FROM ledger_entries
        WHERE workerId = :workerId
          AND entryType = 'WORK'
          AND entryEpochDay BETWEEN :startEpochDay AND :endEpochDay
          AND isDeleted = 0
        GROUP BY workItemId, description, garmentTypeSnapshot, lengthTypeSnapshot,
                 processNameSnapshot, unitSnapshot
        ORDER BY garmentTypeSnapshot, lengthTypeSnapshot, description, processNameSnapshot
        """,
    )
    suspend fun workSummary(
        workerId: String,
        startEpochDay: Long,
        endEpochDay: Long,
    ): List<WorkSummaryRow>

    @Query("SELECT * FROM workers WHERE id = :id AND isDeleted = 0 LIMIT 1")
    suspend fun workerById(id: String): WorkerEntity?

    @Query("SELECT * FROM workers WHERE id = :id LIMIT 1")
    suspend fun workerAnyById(id: String): WorkerEntity?

    @Query("SELECT * FROM work_items WHERE id = :id AND isDeleted = 0 LIMIT 1")
    suspend fun workItemById(id: String): WorkItemEntity?

    @Query("SELECT * FROM ledger_entries WHERE id = :id AND isDeleted = 0 LIMIT 1")
    suspend fun ledgerEntryById(id: String): LedgerEntryEntity?

    @Query(
        """
        SELECT COALESCE(SUM(amountMicros), 0)
        FROM ledger_entries
        WHERE workerId = :workerId AND isDeleted = 0
        """,
    )
    suspend fun workerBalance(workerId: String): Long

    @Query("SELECT * FROM settlements WHERE id = :id AND isDeleted = 0 LIMIT 1")
    suspend fun settlementById(id: String): SettlementEntity?

    @Transaction
    @Query(
        """
        SELECT * FROM settlements
        WHERE workerId = :workerId AND isDeleted = 0
        ORDER BY createdAt DESC
        """,
    )
    fun observeSettlementsForWorker(workerId: String): Flow<List<SettlementWithLines>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertWorker(worker: WorkerEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertWorkItem(workItem: WorkItemEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBuiltInWorkItems(workItems: List<WorkItemEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEntry(entry: LedgerEntryEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEntryIfAbsent(entry: LedgerEntryEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSettlement(settlement: SettlementEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSettlementLines(lines: List<SettlementLineEntity>)

    @Query(
        """
        UPDATE settlements
        SET reversedAt = :reversedAt,
            reversalReason = :reason,
            reversalEntryId = :reversalEntryId,
            updatedAt = :reversedAt,
            revision = revision + 1,
            originDeviceId = :deviceId
        WHERE id = :settlementId AND isDeleted = 0 AND reversedAt IS NULL
        """,
    )
    suspend fun markSettlementReversed(
        settlementId: String,
        reversedAt: Long,
        reason: String,
        reversalEntryId: String,
        deviceId: String,
    ): Int

    @Query(
        """
        UPDATE work_items
        SET defaultUnitPriceMicros = :priceMicros,
            updatedAt = :updatedAt,
            revision = revision + 1,
            originDeviceId = :deviceId
        WHERE id = :workItemId AND isDeleted = 0
        """,
    )
    suspend fun updateWorkItemPrice(
        workItemId: String,
        priceMicros: Long,
        updatedAt: Long,
        deviceId: String,
    )

    @Query(
        """
        UPDATE workers
        SET isDeleted = 1,
            updatedAt = :updatedAt,
            revision = revision + 1,
            originDeviceId = :deviceId
        WHERE id = :workerId AND isDeleted = 0
        """,
    )
    suspend fun archiveWorker(workerId: String, updatedAt: Long, deviceId: String): Int

    @Query(
        """
        UPDATE workers
        SET isDeleted = 0,
            updatedAt = :updatedAt,
            revision = revision + 1,
            originDeviceId = :deviceId
        WHERE id = :workerId AND isDeleted = 1
        """,
    )
    suspend fun restoreWorker(workerId: String, updatedAt: Long, deviceId: String): Int

    @Query(
        """
        UPDATE work_items
        SET isDeleted = 1,
            updatedAt = :updatedAt,
            revision = revision + 1,
            originDeviceId = :deviceId
        WHERE id = :workItemId AND isDeleted = 0
        """,
    )
    suspend fun archiveWorkItem(workItemId: String, updatedAt: Long, deviceId: String): Int

    @Query(
        """
        UPDATE ledger_entries
        SET isDeleted = 1,
            updatedAt = :updatedAt,
            revision = revision + 1,
            originDeviceId = :deviceId
        WHERE id = :entryId AND isDeleted = 0
        """,
    )
    suspend fun softDeleteEntry(entryId: String, updatedAt: Long, deviceId: String)

    @Query("SELECT * FROM workers ORDER BY createdAt")
    suspend fun allWorkersSnapshot(): List<WorkerEntity>

    @Query("SELECT * FROM work_items ORDER BY sortOrder, createdAt")
    suspend fun allWorkItemsSnapshot(): List<WorkItemEntity>

    @Query("SELECT * FROM ledger_entries ORDER BY createdAt")
    suspend fun allEntriesSnapshot(): List<LedgerEntryEntity>

    @Query("SELECT * FROM settlements ORDER BY createdAt")
    suspend fun allSettlementsSnapshot(): List<SettlementEntity>

    @Query("SELECT * FROM settlement_lines ORDER BY settlementId, id")
    suspend fun allSettlementLinesSnapshot(): List<SettlementLineEntity>

    @Query("SELECT * FROM purged_workers ORDER BY purgedAt, id")
    suspend fun allPurgedWorkersSnapshot(): List<PurgedWorkerEntity>

    @Query(
        "DELETE FROM settlement_lines WHERE settlementId IN " +
            "(SELECT id FROM settlements WHERE workerId = :workerId)",
    )
    suspend fun deleteSettlementLinesForWorker(workerId: String)

    @Query("DELETE FROM ledger_entries WHERE workerId = :workerId")
    suspend fun deleteEntriesForWorker(workerId: String)

    @Query("DELETE FROM settlements WHERE workerId = :workerId")
    suspend fun deleteSettlementsForWorker(workerId: String)

    @Query("DELETE FROM workers WHERE id = :workerId")
    suspend fun deleteWorker(workerId: String)

    @Upsert
    suspend fun restoreWorkers(workers: List<WorkerEntity>)

    @Upsert
    suspend fun restoreWorkItems(workItems: List<WorkItemEntity>)

    @Upsert
    suspend fun restoreEntries(entries: List<LedgerEntryEntity>)

    @Upsert
    suspend fun restoreSettlements(settlements: List<SettlementEntity>)

    @Upsert
    suspend fun restoreSettlementLines(lines: List<SettlementLineEntity>)

    @Upsert
    suspend fun restorePurgedWorkers(workers: List<PurgedWorkerEntity>)
}
