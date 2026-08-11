package com.familyledger.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class LedgerRepositoryTest {
    private lateinit var database: LedgerDatabase
    private lateinit var repository: LedgerRepository
    private val workerId = "worker-1"
    private val workItemId = "item-1"
    private val date = LocalDate.of(2026, 6, 15)

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = LedgerRepository(database, "test-device")
        val now = System.currentTimeMillis()
        database.ledgerDao().insertWorker(
            WorkerEntity(
                id = workerId,
                name = "测试工人",
                createdAt = now,
                updatedAt = now,
                originDeviceId = "test-device",
            ),
        )
        database.ledgerDao().insertWorkItem(
            WorkItemEntity(
                id = workItemId,
                name = "长裤",
                garmentType = GarmentType.PANTS,
                lengthType = LengthType.LONG,
                unit = "条",
                defaultUnitPriceMicros = 125_000,
                createdAt = now,
                updatedAt = now,
                originDeviceId = "test-device",
            ),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun duplicateWorkAndSettlementOperationsAreIdempotentAndReversible() = runBlocking {
        repeat(2) {
            repository.addWorkEntry(
                operationId = "work-operation",
                workerId = workerId,
                workItemId = workItemId,
                date = date,
                quantity = 10,
                unitPriceMicros = 125_000,
                note = "",
            )
        }
        repeat(2) {
            repository.addAdvance(
                operationId = "advance-operation",
                workerId = workerId,
                date = date,
                amountMicros = 200_000,
                note = "预支",
            )
        }

        val preview = repository.settlementPreview(workerId, date, date)
        assertEquals(1_250_000L, preview.totals.earnedMicros)
        assertEquals(200_000L, preview.totals.advancesMicros)
        assertEquals(1_050_000L, preview.totals.balanceMicros)
        assertEquals(1, preview.workSummary.size)
        assertEquals(10L, preview.workSummary.single().quantity)

        val first = repository.settle("settlement-operation", workerId, date, date)
        val duplicate = repository.settle("settlement-operation", workerId, date, date)
        assertEquals(first.id, duplicate.id)
        assertEquals(0L, repository.settlementTotals(workerId, date, date).balanceMicros)

        repository.offboardWorker(workerId, force = false)
        assertTrue(repository.observeWorkersWithBalance().first().isEmpty())
        assertTrue(repository.observeAllWorkersWithBalance().first().single().worker.isDeleted)
        assertEquals(3, repository.observeEntriesForWorker(workerId).first().size)
        repository.restoreWorker(workerId)

        repeat(2) {
            repository.reverseSettlement("reversal-operation", first.id, "日期选错")
        }
        assertEquals(1_050_000L, repository.settlementTotals(workerId, date, date).balanceMicros)
        assertTrue(runCatching { repository.offboardWorker(workerId, force = false) }.isFailure)
        repository.offboardWorker(workerId, force = true)
        assertTrue(repository.observeWorkersWithBalance().first().isEmpty())

        val history = repository.observeSettlementsForWorker(workerId).first().single()
        assertNotNull(history.settlement.reversedAt)
        assertEquals("日期选错", history.settlement.reversalReason)
        assertEquals(10L, history.lines.single().quantity)
        assertTrue(history.settlement.revision > 1)

        repository.archiveWorkItem(workItemId)
        assertTrue(repository.observeWorkItems().first().isEmpty())
        assertEquals("长裤", repository.observeEntriesForWorker(workerId).first().first { it.entry.entryType == EntryType.WORK }.entry.description)
    }

    @Test
    fun permanentDeletionRemovesAllWorkerDataAndStaleBackupCannotRestoreIt() = runBlocking {
        repository.addWorkEntry(
            operationId = "work-operation",
            workerId = workerId,
            workItemId = workItemId,
            date = date,
            quantity = 10,
            unitPriceMicros = 125_000,
            note = "",
        )
        repository.settle("settlement-operation", workerId, date, date)
        val staleBackup = repository.backupSnapshot()

        assertTrue(runCatching { repository.permanentlyDeleteWorker(workerId, "错误姓名") }.isFailure)
        repository.permanentlyDeleteWorker(workerId, "测试工人")

        var snapshot = repository.backupSnapshot()
        assertTrue(snapshot.workers.isEmpty())
        assertTrue(snapshot.entries.isEmpty())
        assertTrue(snapshot.settlements.isEmpty())
        assertTrue(snapshot.settlementLines.isEmpty())
        assertEquals(listOf(workerId), snapshot.purgedWorkers.map { it.id })

        repository.restoreBackup(staleBackup)
        snapshot = repository.backupSnapshot()
        assertTrue(snapshot.workers.isEmpty())
        assertTrue(snapshot.entries.isEmpty())
        assertEquals(listOf(workerId), snapshot.purgedWorkers.map { it.id })
    }
}
