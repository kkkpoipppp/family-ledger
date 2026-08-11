package com.familyledger.app.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.familyledger.app.data.LedgerBackup
import com.familyledger.app.data.LedgerDatabase
import com.familyledger.app.data.LedgerRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class CloudSyncManagerTest {
    private lateinit var database: LedgerDatabase
    private lateinit var manager: CloudSyncManager
    private lateinit var client: FakeCloudSyncClient

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        client = FakeCloudSyncClient()
        manager = CloudSyncManager(
            context = context,
            repository = LedgerRepository(database, "test-device"),
            client = client,
        )
        manager.disable()
    }

    @After
    fun tearDown() = runBlocking {
        manager.disable()
        database.close()
    }

    @Test
    fun failedFirstConfigurationIsNotPersisted() = runBlocking {
        client.failure = IOException("offline")
        assertTrue(runCatching { manager.configure("https://bad.example/sync", "1234567890123456") }.isFailure)
        assertFalse(manager.hasConfiguration())
        assertFalse(manager.state.value.isConfigured)
    }

    @Test
    fun failedReplacementKeepsLastWorkingConfiguration() = runBlocking {
        manager.configure("https://working.example/sync", "1234567890123456")
        client.failure = IOException("offline")
        assertTrue(runCatching { manager.configure("https://bad.example/sync", "abcdefghijklmnop") }.isFailure)
        assertTrue(manager.hasConfiguration())
        assertEquals("https://working.example/sync", manager.state.value.endpoint)

        client.failure = null
        manager.syncNow()
        assertEquals("https://working.example/sync", client.lastEndpoint)
    }

    private class FakeCloudSyncClient : CloudSyncClient() {
        var failure: Throwable? = null
        var lastEndpoint: String? = null

        override suspend fun sync(
            config: CloudSyncConfig,
            deviceId: String,
            backup: LedgerBackup,
        ): LedgerBackup {
            lastEndpoint = config.endpoint
            failure?.let { throw it }
            return backup
        }
    }
}
