package com.familyledger.app.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class CloudSyncFailureTest {
    @Test
    fun transientNetworkAndServerFailuresAreRetried() {
        assertTrue(IOException("offline").isRetryableCloudSyncFailure())
        assertTrue(CloudSyncHttpException(408, "timeout").isRetryableCloudSyncFailure())
        assertTrue(CloudSyncHttpException(429, "busy").isRetryableCloudSyncFailure())
        assertTrue(CloudSyncHttpException(503, "unavailable").isRetryableCloudSyncFailure())
    }

    @Test
    fun invalidConfigurationAndDataFailuresAreNotRetriedForever() {
        assertFalse(CloudSyncHttpException(400, "bad data").isRetryableCloudSyncFailure())
        assertFalse(CloudSyncHttpException(401, "bad key").isRetryableCloudSyncFailure())
        assertFalse(IllegalArgumentException("invalid backup").isRetryableCloudSyncFailure())
    }
}
