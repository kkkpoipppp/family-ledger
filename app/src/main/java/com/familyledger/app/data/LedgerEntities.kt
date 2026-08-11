package com.familyledger.app.data

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

object EntryType {
    const val WORK = "WORK"
    const val ADVANCE = "ADVANCE"
    const val PAYMENT = "PAYMENT"
    const val ADJUSTMENT = "ADJUSTMENT"
}

object GarmentType {
    const val TOP = "上衣"
    const val PANTS = "裤子"
    const val OTHER = "其他"
}

object LengthType {
    const val LONG = "长款"
    const val SHORT = "短款"
    const val NOT_APPLICABLE = "不区分"
}

@Entity(
    tableName = "workers",
    indices = [Index(value = ["name"])],
)
data class WorkerEntity(
    @PrimaryKey val id: String,
    val name: String,
    val note: String = "",
    val createdAt: Long,
    val updatedAt: Long,
    val revision: Long = 1,
    val originDeviceId: String,
    val isDeleted: Boolean = false,
)

@Entity(tableName = "purged_workers")
data class PurgedWorkerEntity(
    @PrimaryKey val id: String,
    val purgedAt: Long,
    val originDeviceId: String,
)

@Entity(
    tableName = "work_items",
    indices = [Index(value = ["name"])],
)
data class WorkItemEntity(
    @PrimaryKey val id: String,
    val name: String,
    val garmentType: String,
    val lengthType: String,
    val processName: String = "",
    val unit: String,
    val defaultUnitPriceMicros: Long = 0,
    val sortOrder: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
    val revision: Long = 1,
    val originDeviceId: String,
    val isDeleted: Boolean = false,
)

@Entity(
    tableName = "settlements",
    foreignKeys = [
        ForeignKey(
            entity = WorkerEntity::class,
            parentColumns = ["id"],
            childColumns = ["workerId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index(value = ["workerId"]), Index(value = ["startEpochDay", "endEpochDay"])],
)
data class SettlementEntity(
    @PrimaryKey val id: String,
    val workerId: String,
    val startEpochDay: Long,
    val endEpochDay: Long,
    val earnedMicros: Long,
    val advancesMicros: Long,
    val paymentsMicros: Long,
    val adjustmentsMicros: Long,
    val balanceMicros: Long,
    val settledPaymentMicros: Long,
    val reversedAt: Long? = null,
    @ColumnInfo(defaultValue = "''") val reversalReason: String = "",
    val reversalEntryId: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val revision: Long = 1,
    val originDeviceId: String,
    val isDeleted: Boolean = false,
)

@Entity(
    tableName = "settlement_lines",
    foreignKeys = [
        ForeignKey(
            entity = SettlementEntity::class,
            parentColumns = ["id"],
            childColumns = ["settlementId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index(value = ["settlementId"])],
)
data class SettlementLineEntity(
    @PrimaryKey val id: String,
    val settlementId: String,
    val workItemId: String? = null,
    val description: String,
    val garmentTypeSnapshot: String,
    val lengthTypeSnapshot: String,
    val processNameSnapshot: String,
    val quantity: Long,
    val unitSnapshot: String,
    val amountMicros: Long,
)

@Entity(
    tableName = "ledger_entries",
    foreignKeys = [
        ForeignKey(
            entity = WorkerEntity::class,
            parentColumns = ["id"],
            childColumns = ["workerId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["workerId"]),
        Index(value = ["entryEpochDay"]),
        Index(value = ["workItemId"]),
        Index(value = ["settlementId"]),
    ],
)
data class LedgerEntryEntity(
    @PrimaryKey val id: String,
    val workerId: String,
    val entryType: String,
    val entryEpochDay: Long,
    val description: String,
    val workItemId: String? = null,
    val garmentTypeSnapshot: String = "",
    val lengthTypeSnapshot: String = "",
    val processNameSnapshot: String = "",
    val quantity: Long? = null,
    val unitSnapshot: String = "",
    val unitPriceMicros: Long = 0,
    val amountMicros: Long,
    val note: String = "",
    val settlementId: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val revision: Long = 1,
    val originDeviceId: String,
    val isDeleted: Boolean = false,
)

data class WorkerBalanceRow(
    @Embedded val worker: WorkerEntity,
    @ColumnInfo(name = "balanceMicros") val balanceMicros: Long,
    @ColumnInfo(name = "entryCount") val entryCount: Long,
)

data class LedgerEntryRow(
    @Embedded val entry: LedgerEntryEntity,
    @ColumnInfo(name = "workerName") val workerName: String,
)

data class SettlementTotalsRow(
    val earnedMicros: Long,
    val advancesMicros: Long,
    val paymentsMicros: Long,
    val adjustmentsMicros: Long,
    val balanceMicros: Long,
)

data class WorkSummaryRow(
    val workItemId: String?,
    val description: String,
    val garmentTypeSnapshot: String,
    val lengthTypeSnapshot: String,
    val processNameSnapshot: String,
    val quantity: Long,
    val unitSnapshot: String,
    val amountMicros: Long,
)

data class SettlementPreviewData(
    val totals: SettlementTotalsRow,
    val workSummary: List<WorkSummaryRow>,
)

data class SettlementWithLines(
    @Embedded val settlement: SettlementEntity,
    @Relation(parentColumn = "id", entityColumn = "settlementId")
    val lines: List<SettlementLineEntity>,
)
