package com.familyledger.app.sync

import android.annotation.SuppressLint
import android.content.Context
import androidx.core.content.edit
import com.familyledger.app.data.BackupCodec
import com.familyledger.app.data.LedgerBackup
import com.familyledger.app.data.LedgerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

private const val MAX_RESPONSE_CHARS = 20 * 1024 * 1024
private const val MIN_SYNC_KEY_LENGTH = 16

data class CloudSyncState(
    val isConfigured: Boolean = false,
    val endpoint: String = "",
    val isSyncing: Boolean = false,
    val lastSuccessAt: Long? = null,
    val lastError: String? = null,
)

internal data class CloudSyncConfig(
    val endpoint: String,
    val syncKey: String,
)

class CloudSyncManager internal constructor(
    context: Context,
    private val repository: LedgerRepository,
    private val client: CloudSyncClient = CloudSyncClient(),
) {
    private val preferences = context.getSharedPreferences("ledger_cloud_sync", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val _state = MutableStateFlow(loadState())
    val state = _state.asStateFlow()

    fun hasConfiguration(): Boolean = loadConfig() != null

    @SuppressLint("ApplySharedPref", "UseKtx")
    suspend fun configure(endpoint: String, syncKey: String) {
        val cleanEndpoint = normalizeEndpoint(endpoint)
        mutex.withLock {
            val existing = loadConfig()
            val cleanKey = syncKey.trim().ifEmpty { existing?.syncKey.orEmpty() }
            require(cleanKey.length >= MIN_SYNC_KEY_LENGTH) { "家庭同步码至少需要16个字符" }
            val candidate = CloudSyncConfig(cleanEndpoint, cleanKey)
            _state.value = _state.value.copy(isSyncing = true, lastError = null)
            try {
                synchronize(candidate)
                check(
                    preferences.edit()
                        .putString(KEY_ENDPOINT, candidate.endpoint)
                        .putString(KEY_SYNC_KEY, candidate.syncKey)
                        .commit(),
                ) { "云同步设置保存失败" }
                markSuccess(candidate.endpoint)
            } catch (error: Throwable) {
                _state.value = CloudSyncState(
                    isConfigured = existing != null,
                    endpoint = existing?.endpoint.orEmpty(),
                    lastSuccessAt = preferences.getLong(KEY_LAST_SUCCESS, 0L).takeIf { it > 0L },
                    lastError = error.message?.takeIf { it.isNotBlank() } ?: "云同步测试失败",
                )
                throw error
            }
        }
    }

    @SuppressLint("ApplySharedPref", "UseKtx")
    suspend fun disable() = mutex.withLock {
        preferences.edit().remove(KEY_ENDPOINT).remove(KEY_SYNC_KEY).commit()
        _state.value = CloudSyncState(lastSuccessAt = preferences.getLong(KEY_LAST_SUCCESS, 0L).takeIf { it > 0L })
    }

    suspend fun syncNow(): LedgerBackup {
        return mutex.withLock {
            val config = requireNotNull(loadConfig()) { "请先填写云同步地址和家庭同步码" }
            _state.value = _state.value.copy(isSyncing = true, lastError = null)
            try {
                val merged = synchronize(config)
                markSuccess(config.endpoint)
                merged
            } catch (error: Throwable) {
                val message = error.message?.takeIf { it.isNotBlank() } ?: "云同步失败"
                _state.value = _state.value.copy(isSyncing = false, lastError = message)
                throw error
            }
        }
    }

    suspend fun settle(
        operationId: String,
        workerId: String,
        startEpochDay: Long,
        endEpochDay: Long,
    ): LedgerBackup {
        return performRemoteMutation { config, local ->
            client.settle(
                config = config,
                deviceId = repository.currentDeviceId,
                backup = local,
                operationId = operationId,
                workerId = workerId,
                startEpochDay = startEpochDay,
                endEpochDay = endEpochDay,
            )
        }
    }

    suspend fun reverseSettlement(operationId: String, settlementId: String, reason: String): LedgerBackup {
        return performRemoteMutation { config, local ->
            client.reverseSettlement(
                config = config,
                deviceId = repository.currentDeviceId,
                backup = local,
                operationId = operationId,
                settlementId = settlementId,
                reason = reason,
            )
        }
    }

    suspend fun purgeWorker(workerId: String): LedgerBackup {
        return performRemoteMutation { config, local ->
            client.purgeWorker(
                config = config,
                deviceId = repository.currentDeviceId,
                backup = local,
                workerId = workerId,
            )
        }
    }

    private suspend fun performRemoteMutation(
        mutation: suspend (CloudSyncConfig, LedgerBackup) -> LedgerBackup,
    ): LedgerBackup = mutex.withLock {
        val config = requireNotNull(loadConfig()) { "请先开启云同步" }
        _state.value = _state.value.copy(isSyncing = true, lastError = null)
        try {
            val merged = mutation(config, repository.backupSnapshot())
            repository.restoreBackup(merged)
            markSuccess()
            merged
        } catch (error: Throwable) {
            val message = error.message?.takeIf { it.isNotBlank() } ?: "云端操作失败"
            _state.value = _state.value.copy(isSyncing = false, lastError = message)
            throw error
        }
    }

    private suspend fun synchronize(config: CloudSyncConfig): LedgerBackup {
        val local = repository.backupSnapshot()
        val merged = client.sync(config, repository.currentDeviceId, local)
        repository.restoreBackup(merged)
        return merged
    }

    private fun markSuccess(endpoint: String = loadConfig()?.endpoint.orEmpty()) {
        val now = System.currentTimeMillis()
        preferences.edit { putLong(KEY_LAST_SUCCESS, now) }
        _state.value = _state.value.copy(
            isConfigured = true,
            endpoint = endpoint,
            isSyncing = false,
            lastSuccessAt = now,
            lastError = null,
        )
    }

    private fun loadConfig(): CloudSyncConfig? {
        val endpoint = preferences.getString(KEY_ENDPOINT, null)?.trim().orEmpty()
        val key = preferences.getString(KEY_SYNC_KEY, null)?.trim().orEmpty()
        return if (endpoint.isNotEmpty() && key.length >= MIN_SYNC_KEY_LENGTH) CloudSyncConfig(endpoint, key) else null
    }

    private fun loadState(): CloudSyncState {
        val config = loadConfig()
        return CloudSyncState(
            isConfigured = config != null,
            endpoint = config?.endpoint.orEmpty(),
            lastSuccessAt = preferences.getLong(KEY_LAST_SUCCESS, 0L).takeIf { it > 0L },
        )
    }

    private fun normalizeEndpoint(value: String): String {
        val clean = value.trim().trimEnd('/')
        val parsed = runCatching { URL(clean) }.getOrNull()
        require(parsed != null && parsed.protocol.equals("https", ignoreCase = true) && !parsed.host.isNullOrBlank()) {
            "云同步地址必须是完整的 https 地址"
        }
        return clean
    }

    companion object {
        private const val KEY_ENDPOINT = "endpoint"
        private const val KEY_SYNC_KEY = "sync_key"
        private const val KEY_LAST_SUCCESS = "last_success"

        @Volatile
        private var instance: CloudSyncManager? = null

        fun getInstance(context: Context): CloudSyncManager =
            instance ?: synchronized(this) {
                instance ?: CloudSyncManager(
                    context = context.applicationContext,
                    repository = LedgerRepository.getInstance(context.applicationContext),
                ).also { instance = it }
            }
    }
}

internal class CloudSyncHttpException(
    val statusCode: Int,
    message: String,
) : IOException(message)

internal fun Throwable.isRetryableCloudSyncFailure(): Boolean =
    when (this) {
        is CloudSyncHttpException -> statusCode == 408 || statusCode == 425 || statusCode == 429 || statusCode >= 500
        is IOException -> true
        else -> false
    }

internal open class CloudSyncClient {
    open suspend fun sync(config: CloudSyncConfig, deviceId: String, backup: LedgerBackup): LedgerBackup =
        request(config, "sync", deviceId, backup)

    open suspend fun settle(
        config: CloudSyncConfig,
        deviceId: String,
        backup: LedgerBackup,
        operationId: String,
        workerId: String,
        startEpochDay: Long,
        endEpochDay: Long,
    ): LedgerBackup = request(
        config = config,
        action = "settle",
        deviceId = deviceId,
        backup = backup,
        extra = JSONObject().apply {
            put("operationId", operationId)
            put("workerId", workerId)
            put("startEpochDay", startEpochDay)
            put("endEpochDay", endEpochDay)
        },
    )

    open suspend fun reverseSettlement(
        config: CloudSyncConfig,
        deviceId: String,
        backup: LedgerBackup,
        operationId: String,
        settlementId: String,
        reason: String,
    ): LedgerBackup = request(
        config = config,
        action = "reverseSettlement",
        deviceId = deviceId,
        backup = backup,
        extra = JSONObject().apply {
            put("operationId", operationId)
            put("settlementId", settlementId)
            put("reason", reason)
        },
    )

    open suspend fun purgeWorker(
        config: CloudSyncConfig,
        deviceId: String,
        backup: LedgerBackup,
        workerId: String,
    ): LedgerBackup = request(
        config = config,
        action = "purgeWorker",
        deviceId = deviceId,
        backup = backup,
        extra = JSONObject().apply { put("workerId", workerId) },
    )

    private suspend fun request(
        config: CloudSyncConfig,
        action: String,
        deviceId: String,
        backup: LedgerBackup,
        extra: JSONObject = JSONObject(),
    ): LedgerBackup = withContext(Dispatchers.IO) {
        val request = JSONObject().apply {
            put("protocolVersion", 1)
            put("action", action)
            put("deviceId", deviceId)
            put("backupJson", BackupCodec.encode(backup, pretty = false))
            extra.keys().forEach { key -> put(key, extra.get(key)) }
        }.toString()
        require(request.length <= MAX_RESPONSE_CHARS) { "本地账本过大，请先导出备份并联系维护人员" }

        val connection = (URL(config.endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-Ledger-Sync-Key", config.syncKey)
        }
        try {
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(request) }
            val status = connection.responseCode
            val responseText = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.use(InputStream::readBoundedText)
                .orEmpty()
            val response = runCatching { JSONObject(responseText) }.getOrNull()
            if (status !in 200..299) {
                throw CloudSyncHttpException(
                    statusCode = status,
                    message = response?.optString("message")?.takeIf { it.isNotBlank() } ?: "云端返回错误（$status）",
                )
            }
            require(response?.optBoolean("ok") == true) {
                response?.optString("message")?.takeIf { it.isNotBlank() } ?: "云端响应不完整"
            }
            BackupCodec.decode(response.getString("backupJson"))
        } finally {
            connection.disconnect()
        }
    }
}

private fun InputStream.readBoundedText(): String {
    val result = StringBuilder()
    bufferedReader(Charsets.UTF_8).use { reader ->
        val buffer = CharArray(8_192)
        while (true) {
            val count = reader.read(buffer)
            if (count < 0) break
            require(result.length + count <= MAX_RESPONSE_CHARS) { "云端响应过大" }
            result.append(buffer, 0, count)
        }
    }
    return result.toString()
}
