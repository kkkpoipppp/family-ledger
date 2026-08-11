package com.familyledger.app.data

import com.familyledger.app.domain.calculateWorkAmount
import org.json.JSONArray
import org.json.JSONObject

object BackupCodec {
    private const val FORMAT = "family-ledger-backup"
    private const val SCHEMA_VERSION = 3

    fun encode(backup: LedgerBackup, pretty: Boolean = true): String = JSONObject().apply {
        put("format", FORMAT)
        put("schemaVersion", SCHEMA_VERSION)
        put("exportedAt", System.currentTimeMillis())
        put("workers", JSONArray().apply { backup.workers.forEach { put(it.toJson()) } })
        put("workItems", JSONArray().apply { backup.workItems.forEach { put(it.toJson()) } })
        put("entries", JSONArray().apply { backup.entries.forEach { put(it.toJson()) } })
        put("settlements", JSONArray().apply { backup.settlements.forEach { put(it.toJson()) } })
        put("settlementLines", JSONArray().apply { backup.settlementLines.forEach { put(it.toJson()) } })
        put("purgedWorkers", JSONArray().apply { backup.purgedWorkers.forEach { put(it.toJson()) } })
    }.let { root -> if (pretty) root.toString(2) else root.toString() }

    fun decode(json: String): LedgerBackup {
        val root = JSONObject(json)
        require(root.getString("format") == FORMAT) { "这不是家庭工账备份文件" }
        val schemaVersion = root.getInt("schemaVersion")
        require(schemaVersion in 1..SCHEMA_VERSION) { "备份版本暂不支持" }

        val backup = LedgerBackup(
            workers = root.getJSONArray("workers").mapObjects { it.toWorker() },
            workItems = root.getJSONArray("workItems").mapObjects { it.toWorkItem() },
            entries = root.getJSONArray("entries").mapObjects { it.toEntry() },
            settlements = root.getJSONArray("settlements").mapObjects { it.toSettlement() },
            settlementLines = root.optJSONArray("settlementLines")
                ?.mapObjects { it.toSettlementLine() }
                .orEmpty(),
            purgedWorkers = root.optJSONArray("purgedWorkers")
                ?.mapObjects { it.toPurgedWorker() }
                .orEmpty(),
        )
        validate(backup)
        return backup
    }

    private fun validate(backup: LedgerBackup) {
        require(backup.workers.map { it.id }.distinct().size == backup.workers.size) { "备份中有重复工人" }
        require(backup.workItems.map { it.id }.distinct().size == backup.workItems.size) { "备份中有重复项目" }
        require(backup.entries.map { it.id }.distinct().size == backup.entries.size) { "备份中有重复流水" }
        require(backup.settlements.map { it.id }.distinct().size == backup.settlements.size) { "备份中有重复结算" }
        require(backup.settlementLines.map { it.id }.distinct().size == backup.settlementLines.size) {
            "备份中有重复结算明细"
        }
        require(backup.purgedWorkers.map { it.id }.distinct().size == backup.purgedWorkers.size) {
            "备份中有重复永久删除标记"
        }
        val workerIds = backup.workers.mapTo(mutableSetOf()) { it.id }
        val workItemIds = backup.workItems.mapTo(mutableSetOf()) { it.id }
        val settlementIds = backup.settlements.mapTo(mutableSetOf()) { it.id }
        require(backup.entries.all { it.workerId in workerIds }) { "备份中的流水缺少对应工人" }
        require(backup.settlements.all { it.workerId in workerIds }) { "备份中的结算缺少对应工人" }
        require(backup.entries.all { it.workItemId == null || it.workItemId in workItemIds }) {
            "备份中的流水缺少对应做工项目"
        }
        require(backup.entries.all { it.settlementId == null || it.settlementId in settlementIds }) {
            "备份中的付款缺少对应结算"
        }
        require(backup.settlementLines.all { it.settlementId in settlementIds }) {
            "备份中的结算明细缺少对应结算"
        }
        val purgedWorkerIds = backup.purgedWorkers.mapTo(mutableSetOf()) { it.id }
        require(backup.purgedWorkers.all {
            it.id.isNotBlank() && it.purgedAt >= 0 && it.originDeviceId.isNotBlank()
        }) { "备份中有无效永久删除标记" }
        require(backup.workers.none { it.id in purgedWorkerIds }) { "备份中仍包含已永久删除工人" }
        require(backup.entries.none { it.workerId in purgedWorkerIds }) { "备份中仍包含已永久删除流水" }
        require(backup.settlements.none { it.workerId in purgedWorkerIds }) { "备份中仍包含已永久删除结算" }
        require(backup.workers.all { it.id.isNotBlank() && it.name.isNotBlank() && it.revision >= 1 }) {
            "备份中有无效工人资料"
        }
        require(backup.workItems.all { it.id.isNotBlank() && it.name.isNotBlank() && it.unit.isNotBlank() && it.defaultUnitPriceMicros >= 0 && it.revision >= 1 }) {
            "备份中有无效做工项目"
        }
        require(backup.settlements.all { it.startEpochDay <= it.endEpochDay && it.settledPaymentMicros > 0 && it.revision >= 1 }) {
            "备份中有无效结算记录"
        }
        require(backup.settlementLines.all {
            it.id.isNotBlank() && it.description.isNotBlank() && it.quantity > 0 &&
                it.unitSnapshot.isNotBlank() && it.amountMicros > 0
        }) { "备份中有无效结算明细" }
        require(backup.entries.all(::isValidEntry)) { "备份中有无效流水" }
    }

    private fun isValidEntry(entry: LedgerEntryEntity): Boolean {
        if (entry.id.isBlank() || entry.description.isBlank() || entry.revision < 1) return false
        return when (entry.entryType) {
            EntryType.WORK -> {
                val quantity = entry.quantity ?: return false
                calculateWorkAmount(quantity, entry.unitPriceMicros) == entry.amountMicros
            }
            EntryType.ADVANCE, EntryType.PAYMENT -> entry.amountMicros < 0
            EntryType.ADJUSTMENT -> entry.amountMicros != 0L
            else -> false
        }
    }

    private fun WorkerEntity.toJson() = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("note", note)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        put("revision", revision)
        put("originDeviceId", originDeviceId)
        put("isDeleted", isDeleted)
    }

    private fun WorkItemEntity.toJson() = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("garmentType", garmentType)
        put("lengthType", lengthType)
        put("processName", processName)
        put("unit", unit)
        put("defaultUnitPriceMicros", defaultUnitPriceMicros)
        put("sortOrder", sortOrder)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        put("revision", revision)
        put("originDeviceId", originDeviceId)
        put("isDeleted", isDeleted)
    }

    private fun LedgerEntryEntity.toJson() = JSONObject().apply {
        put("id", id)
        put("workerId", workerId)
        put("entryType", entryType)
        put("entryEpochDay", entryEpochDay)
        put("description", description)
        put("workItemId", workItemId ?: JSONObject.NULL)
        put("garmentTypeSnapshot", garmentTypeSnapshot)
        put("lengthTypeSnapshot", lengthTypeSnapshot)
        put("processNameSnapshot", processNameSnapshot)
        put("quantity", quantity ?: JSONObject.NULL)
        put("unitSnapshot", unitSnapshot)
        put("unitPriceMicros", unitPriceMicros)
        put("amountMicros", amountMicros)
        put("note", note)
        put("settlementId", settlementId ?: JSONObject.NULL)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        put("revision", revision)
        put("originDeviceId", originDeviceId)
        put("isDeleted", isDeleted)
    }

    private fun SettlementEntity.toJson() = JSONObject().apply {
        put("id", id)
        put("workerId", workerId)
        put("startEpochDay", startEpochDay)
        put("endEpochDay", endEpochDay)
        put("earnedMicros", earnedMicros)
        put("advancesMicros", advancesMicros)
        put("paymentsMicros", paymentsMicros)
        put("adjustmentsMicros", adjustmentsMicros)
        put("balanceMicros", balanceMicros)
        put("settledPaymentMicros", settledPaymentMicros)
        put("reversedAt", reversedAt ?: JSONObject.NULL)
        put("reversalReason", reversalReason)
        put("reversalEntryId", reversalEntryId ?: JSONObject.NULL)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        put("revision", revision)
        put("originDeviceId", originDeviceId)
        put("isDeleted", isDeleted)
    }

    private fun SettlementLineEntity.toJson() = JSONObject().apply {
        put("id", id)
        put("settlementId", settlementId)
        put("workItemId", workItemId ?: JSONObject.NULL)
        put("description", description)
        put("garmentTypeSnapshot", garmentTypeSnapshot)
        put("lengthTypeSnapshot", lengthTypeSnapshot)
        put("processNameSnapshot", processNameSnapshot)
        put("quantity", quantity)
        put("unitSnapshot", unitSnapshot)
        put("amountMicros", amountMicros)
    }

    private fun PurgedWorkerEntity.toJson() = JSONObject().apply {
        put("id", id)
        put("purgedAt", purgedAt)
        put("originDeviceId", originDeviceId)
    }

    private fun JSONObject.toWorker() = WorkerEntity(
        id = getString("id"),
        name = getString("name"),
        note = optString("note"),
        createdAt = getLong("createdAt"),
        updatedAt = getLong("updatedAt"),
        revision = optLong("revision", 1),
        originDeviceId = optString("originDeviceId", "restored"),
        isDeleted = optBoolean("isDeleted", false),
    )

    private fun JSONObject.toWorkItem() = WorkItemEntity(
        id = getString("id"),
        name = getString("name"),
        garmentType = getString("garmentType"),
        lengthType = getString("lengthType"),
        processName = optString("processName"),
        unit = getString("unit"),
        defaultUnitPriceMicros = optLong("defaultUnitPriceMicros", 0),
        sortOrder = optInt("sortOrder", Int.MAX_VALUE),
        createdAt = getLong("createdAt"),
        updatedAt = getLong("updatedAt"),
        revision = optLong("revision", 1),
        originDeviceId = optString("originDeviceId", "restored"),
        isDeleted = optBoolean("isDeleted", false),
    )

    private fun JSONObject.toEntry() = LedgerEntryEntity(
        id = getString("id"),
        workerId = getString("workerId"),
        entryType = getString("entryType"),
        entryEpochDay = getLong("entryEpochDay"),
        description = getString("description"),
        workItemId = nullableString("workItemId"),
        garmentTypeSnapshot = optString("garmentTypeSnapshot"),
        lengthTypeSnapshot = optString("lengthTypeSnapshot"),
        processNameSnapshot = optString("processNameSnapshot"),
        quantity = nullableLong("quantity"),
        unitSnapshot = optString("unitSnapshot"),
        unitPriceMicros = optLong("unitPriceMicros", 0),
        amountMicros = getLong("amountMicros"),
        note = optString("note"),
        settlementId = nullableString("settlementId"),
        createdAt = getLong("createdAt"),
        updatedAt = getLong("updatedAt"),
        revision = optLong("revision", 1),
        originDeviceId = optString("originDeviceId", "restored"),
        isDeleted = optBoolean("isDeleted", false),
    )

    private fun JSONObject.toSettlement() = SettlementEntity(
        id = getString("id"),
        workerId = getString("workerId"),
        startEpochDay = getLong("startEpochDay"),
        endEpochDay = getLong("endEpochDay"),
        earnedMicros = getLong("earnedMicros"),
        advancesMicros = getLong("advancesMicros"),
        paymentsMicros = getLong("paymentsMicros"),
        adjustmentsMicros = getLong("adjustmentsMicros"),
        balanceMicros = getLong("balanceMicros"),
        settledPaymentMicros = getLong("settledPaymentMicros"),
        reversedAt = nullableLong("reversedAt"),
        reversalReason = optString("reversalReason"),
        reversalEntryId = nullableString("reversalEntryId"),
        createdAt = getLong("createdAt"),
        updatedAt = getLong("updatedAt"),
        revision = optLong("revision", 1),
        originDeviceId = optString("originDeviceId", "restored"),
        isDeleted = optBoolean("isDeleted", false),
    )

    private fun JSONObject.toSettlementLine() = SettlementLineEntity(
        id = getString("id"),
        settlementId = getString("settlementId"),
        workItemId = nullableString("workItemId"),
        description = getString("description"),
        garmentTypeSnapshot = optString("garmentTypeSnapshot"),
        lengthTypeSnapshot = optString("lengthTypeSnapshot"),
        processNameSnapshot = optString("processNameSnapshot"),
        quantity = getLong("quantity"),
        unitSnapshot = getString("unitSnapshot"),
        amountMicros = getLong("amountMicros"),
    )

    private fun JSONObject.toPurgedWorker() = PurgedWorkerEntity(
        id = getString("id"),
        purgedAt = getLong("purgedAt"),
        originDeviceId = getString("originDeviceId"),
    )

    private fun JSONObject.nullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else getString(key)

    private fun JSONObject.nullableLong(key: String): Long? =
        if (!has(key) || isNull(key)) null else getLong(key)

    private inline fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> =
        List(length()) { index -> transform(getJSONObject(index)) }
}
