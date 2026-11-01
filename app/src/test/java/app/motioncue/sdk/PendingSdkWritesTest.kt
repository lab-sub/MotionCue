package app.motioncue.sdk

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeoutException

class PendingSdkWritesTest {
    @Test fun confirmedRestoreDoesNotBlockAStopStartInTheSameOwnerCommand() = runBlocking {
        val settlements = ArrayDeque<suspend () -> Unit>()
        val writes = PendingSdkWrites { settlements.addLast(it) }
        writes.await<Unit> { success, _ -> success(Unit) }
        assertFalse(writes.hasPending)
        settlements.removeFirst().invoke()
        assertFalse(writes.hasPending)
    }

    @Test fun timedOutRestoreStillBlocksNewOwnershipAfterASuccessfulRetry() = runBlocking {
        val settlements = ArrayDeque<suspend () -> Unit>()
        val writes = PendingSdkWrites { settlements.addLast(it) }
        lateinit var lateRestore: (Unit) -> Unit
        try {
            writes.await<Unit> { success, _ -> lateRestore = success }
            fail("Expected restoration timeout")
        } catch (_: TimeoutException) {
            assertTrue(writes.hasPending)
        }

        // A retry can finish first, but cannot release the older restoration's ownership gate.
        writes.await<Unit> { success, _ -> success(Unit) }
        settlements.removeFirst().invoke()
        assertTrue("Do not start a new session/intervention yet", writes.hasPending)

        lateRestore(Unit)
        assertTrue("Completion must acquire the owner's mutex before opening the gate", writes.hasPending)
        settlements.removeFirst().invoke()
        assertFalse(writes.hasPending)
    }

    @Test fun lateOriginalWriteCompensationIsAlsoTrackedUntilItsCallbackSettles() = runBlocking {
        val settlements = ArrayDeque<suspend () -> Unit>()
        val writes = PendingSdkWrites { settlements.addLast(it) }
        var physicalValue = 0
        var compensations = 0
        lateinit var lateWrite: (Unit) -> Unit
        lateinit var finishCompensation: (Unit) -> Unit
        val originalWrite = async(start = CoroutineStart.UNDISPATCHED) {
            writes.await<Unit>(onSettled = {
                compensations++
                writes.await<Unit> { success, _ ->
                    physicalValue = 0
                    finishCompensation = success
                }
            }) { success, _ -> lateWrite = success }
        }
        originalWrite.cancelAndJoin() // Cancellation does not cancel the actual SDK operation.
        writes.await<Unit> { success, _ -> physicalValue = 0; success(Unit) }
        settlements.removeFirst().invoke()
        assertTrue(writes.hasPending)

        physicalValue = 7
        lateWrite(Unit)
        val compensation = async(start = CoroutineStart.UNDISPATCHED) { settlements.removeFirst().invoke() }
        assertEquals(0, physicalValue)
        assertTrue("Compensating restoration remains a pending mutation", writes.hasPending)
        finishCompensation(Unit)
        compensation.await()
        settlements.removeFirst().invoke()
        assertFalse(writes.hasPending)

        lateWrite(Unit) // A duplicate callback must neither restore again nor affect a new gate.
        settlements.removeFirst().invoke()
        assertEquals(1, compensations)
        assertFalse(writes.hasPending)
    }

    @Test fun synchronousFailureSettlesOnlyItsOwnRequest() = runBlocking {
        val settlements = ArrayDeque<suspend () -> Unit>()
        val writes = PendingSdkWrites { settlements.addLast(it) }
        lateinit var earlier: (Unit) -> Unit
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            writes.await<Unit> { success, _ -> earlier = success }
        }
        first.cancelAndJoin()
        try {
            writes.await<Unit> { _, _ -> throw IllegalStateException("service unavailable") }
            fail("Expected SDK failure")
        } catch (_: IllegalStateException) {
            settlements.removeFirst().invoke()
            assertTrue(writes.hasPending)
        }
        earlier(Unit)
        settlements.removeFirst().invoke()
        assertFalse(writes.hasPending)
    }
}
