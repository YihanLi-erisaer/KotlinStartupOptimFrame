package com.ikkoaudio.startup.runtime

import com.ikkoaudio.startup.core.StartupTask
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Fails fast if [delegate] does not finish within [timeoutMs]. On timeout, throws [TimeoutCancellationException].
 */
class TimeoutTask(
    private val delegate: StartupTask,
    private val timeoutMs: Long,
) : StartupTask {
    override val id: String = delegate.id
    override val dependencies: List<String> = delegate.dependencies
    override val runOnMainThread: Boolean = delegate.runOnMainThread
    override val needWait: Boolean = delegate.needWait
    override val priority: Int = delegate.priority

    override suspend fun run() {
        withTimeout(timeoutMs) {
            delegate.run()
        }
    }
}
