package com.notokens.monolog.ai

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiFallbackTest {
    @Test fun successfulOnlineAnswerSkipsOfflineModel() = runBlocking {
        var offlineCalled = false
        val result = withAiFallback({ "online" }, { offlineCalled = true; "offline" })
        assertEquals("online", result)
        assertFalse(offlineCalled)
    }

    @Test fun connectionAndQuotaFailuresUseOfflineModel() = runBlocking {
        for (error in listOf(IOException("No connection"), IOException("Quota exceeded"))) {
            assertEquals("offline", withAiFallback({ throw error }, { "offline" }))
        }
    }

    @Test fun cancelledRequestDoesNotStartOfflineGeneration() = runBlocking {
        var offlineCalled = false
        var cancelled = false
        try {
            withAiFallback({ throw CancellationException() }, { offlineCalled = true; "offline" })
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
        assertFalse(offlineCalled)
    }
}
