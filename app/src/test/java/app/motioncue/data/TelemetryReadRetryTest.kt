package app.motioncue.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.TimeoutException

class TelemetryReadRetryTest {
    @Test fun `transient capability timeout retries before one successful registration`() = runBlocking {
        var reads = 0
        var waits = 0
        var registrations = 0
        val retries = mutableListOf<Int>()
        val supported = retryTelemetryRead({ true }, { next, _ -> retries += next }, { waits++ }) {
            if (++reads < 3) throw TimeoutException("warming up")
            true
        }
        if (supported) registrations++
        assertEquals(3, reads)
        assertEquals(2, waits)
        assertEquals(listOf(2, 3), retries)
        assertEquals(1, registrations)
    }

    @Test fun `unsupported capability is final without retry or registration`() = runBlocking {
        var reads = 0
        val result = retryTelemetryRead({ true }, { _, _ -> fail("No retry") }, { fail("No delay") }) {
            reads++
            false
        }
        assertFalse(result)
        assertEquals(1, reads)
    }

    @Test fun `three failed reads propagate and never register`() = runBlocking {
        var reads = 0
        try {
            retryTelemetryRead({ true }, { _, _ -> }, {}) {
                reads++
                throw TimeoutException("unavailable")
            }
            fail("Expected failure")
        } catch (_: TimeoutException) {
            assertEquals(3, reads)
        }
    }

    @Test fun `pause during retry wait prevents another SDK call`() = runBlocking {
        var reads = 0
        val task = async(start = CoroutineStart.UNDISPATCHED) {
            retryTelemetryRead({ true }, { _, _ -> }, { awaitCancellation() }) {
                reads++
                throw TimeoutException()
            }
        }
        task.cancelAndJoin()
        assertTrue(task.isCancelled)
        assertEquals(1, reads)
    }

    @Test fun `stale generation and owner cancellation are never retried`() = runBlocking {
        var reads = 0
        try {
            retryTelemetryRead({ false }, { _, _ -> fail("No retry") }, {}) { reads++ }
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            assertEquals(0, reads)
        }
        try {
            retryTelemetryRead({ true }, { _, _ -> fail("No retry") }, {}) {
                reads++
                throw CancellationException("owner stopped")
            }
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            assertEquals(1, reads)
        }
    }
}
