package app.motioncue.sdk

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.TimeoutException

class SdkCallbacksTest {
    @Test fun `nullable success and duplicate callbacks are safe`() = runBlocking {
        val result = awaitSdk<String?> { success, failure ->
            success(null)
            success("duplicate")
            failure(IllegalStateException("duplicate failure"))
        }
        assertNull(result)
    }

    @Test fun `synchronous sdk exception is delivered to caller`() = runBlocking {
        try {
            awaitSdk<Unit> { _, _ -> throw IllegalStateException("unavailable") }
            fail("Expected SDK exception")
        } catch (error: IllegalStateException) {
            assertEquals("unavailable", error.message)
        }
    }

    @Test fun `cancelled owner ignores late sdk completion`() = runBlocking {
        lateinit var complete: (Int) -> Unit
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            awaitSdk<Int> { success, _ -> complete = success }
        }
        pending.cancelAndJoin()
        complete(42)
        assertTrue(pending.isCancelled)
    }

    @Test fun `outer timeout remains cancellation`() = runBlocking {
        try {
            withTimeout(20L) { awaitSdk<Unit> { _, _ -> } }
            fail("Expected owner timeout")
        } catch (_: CancellationException) {
            // Owner cancellation must not be converted into an ordinary SDK failure.
        }
    }

    @Test fun `sdk timeout is ordinary failure without cancelling owner`() = runBlocking {
        try {
            awaitSdk<Unit> { _, _ -> }
            fail("Expected SDK timeout")
        } catch (_: TimeoutException) {
            assertEquals(7, awaitSdk<Int> { success, _ -> success(7) })
        }
    }
}
