package com.ikkoaudio.startup.runtime

import com.ikkoaudio.startup.core.ExecutionPhase
import com.ikkoaudio.startup.core.StartupTask
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Runs [delegate] at most once (first successful completion). Safe for idempotent "init once" semantics.
 */
class LazyTask(private val delegate: StartupTask) : StartupTask {
    override val id: String = delegate.id
    override val dependencies: List<String> = delegate.dependencies
    override val runOnMainThread: Boolean = delegate.runOnMainThread
    override val needWait: Boolean = delegate.needWait
    override val priority: Int = delegate.priority
    override val executionPhase: ExecutionPhase = delegate.executionPhase

    private val mutex = Mutex()
    private var done: Boolean = false

    override suspend fun run() {
        mutex.withLock {
            if (done) return
            delegate.run()
            done = true
        }
    }
}
