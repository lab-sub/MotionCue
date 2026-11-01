package app.motioncue.sdk

/**
 * A timed-out/cancelled await does not cancel a vehicle mutation. Keep its ownership gate until
 * the SDK gives a terminal callback, including for restoration writes and restoration retries.
 * Submission, [hasPending], and dispatched settlement run under the owner's dispatcher/mutex.
 */
internal class PendingSdkWrites(
    private val dispatchSettlement: (suspend () -> Unit) -> Unit,
) {
    private val pending = mutableSetOf<Any>()
    val hasPending: Boolean get() = pending.isNotEmpty()

    suspend fun <T> await(
        onSettled: (suspend () -> Unit)? = null,
        register: (success: (T) -> Unit, failure: (Exception) -> Unit) -> Unit,
    ): T {
        val request = Any()
        val result = awaitSdk<T> { success, failure ->
            pending += request
            fun settled() {
                dispatchSettlement {
                    // Duplicate callbacks must not compensate twice or release another request's gate.
                    if (pending.remove(request)) onSettled?.invoke()
                }
            }
            try {
                register({ value -> success(value); settled() }, { error -> failure(error); settled() })
            } catch (error: Exception) {
                settled()
                throw error
            }
        }
        // A confirmed restore needs no compensation. Retire it while the owner still holds its
        // mutex so a stop/start in one command is not blocked by its own completed restoration.
        // Timed-out/cancelled awaits never reach this line and retain their gates.
        if (onSettled == null) pending.remove(request)
        return result
    }
}
