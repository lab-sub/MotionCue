package app.motioncue.sdk

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Late/duplicate SDK callbacks cannot resume a timed-out or cancelled request. */
suspend fun <T> awaitSdk(
    register: (success: (T) -> Unit, failure: (Exception) -> Unit) -> Unit,
): T {
    val result = withTimeoutOrNull(3_000L) {
        SdkResult(suspendCancellableCoroutine<T> { continuation ->
            val completed = AtomicBoolean(false)
            continuation.invokeOnCancellation { completed.set(true) }
            val success: (T) -> Unit = { value ->
                if (completed.compareAndSet(false, true)) continuation.resume(value)
            }
            val failure: (Exception) -> Unit = { error ->
                if (completed.compareAndSet(false, true)) continuation.resumeWithException(error)
            }
            try {
                register(success, failure)
            } catch (error: Exception) {
                failure(error)
            }
        })
    } ?: throw TimeoutException("SDK 응답 시간 초과(3초)")
    return result.value
}

private data class SdkResult<T>(val value: T)
