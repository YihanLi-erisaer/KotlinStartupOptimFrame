package com.ikkoaudio.startup.runtime

import com.ikkoaudio.startup.core.StartupTask

/**
 * Overrides scheduling preference among ready tasks with the same dependency readiness.
 */
class PriorityTask(
    private val delegate: StartupTask,
    override val priority: Int,
) : StartupTask {
    override val id: String = delegate.id
    override val dependencies: List<String> = delegate.dependencies
    override val runOnMainThread: Boolean = delegate.runOnMainThread
    override val needWait: Boolean = delegate.needWait

    override suspend fun run() {
        delegate.run()
    }
}
