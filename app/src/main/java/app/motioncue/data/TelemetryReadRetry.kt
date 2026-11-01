package app.motioncue.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Only for read-only capability discovery. A successful unsupported result is final. */
internal suspend fun <T> retryTelemetryRead(
    isActive: () -> Boolean,
    onRetry: (attempt: Int, error: Exception) -> Unit,
    waitBeforeRetry: suspend () -> Unit = { delay(1_000L) },
    read: suspend () -> T,
): T {
    repeat(3) { attempt ->
        if (!isActive()) throw CancellationException("Live source stopped")
        try {
            return read()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (attempt == 2) throw error
            if (!isActive()) throw CancellationException("Live source stopped")
            onRetry(attempt + 2, error)
            waitBeforeRetry()
        }
    }
    error("Unreachable")
}
