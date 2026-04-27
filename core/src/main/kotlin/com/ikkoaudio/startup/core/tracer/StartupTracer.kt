package com.ikkoaudio.startup.core.tracer

import com.ikkoaudio.startup.core.diagnostics.InMemoryTaskTraceStore

/**
 * Default process-wide in-memory [TaskTrace] store for demos and ad-hoc debugging.
 * For production, prefer a dedicated [com.ikkoaudio.startup.core.diagnostics.InMemoryTaskTraceStore] instance
 * passed to [com.ikkoaudio.startup.core.StartupManager], or a [com.ikkoaudio.startup.core.diagnostics.CompositeTaskTimingSink].
 */
object StartupTracer : InMemoryTaskTraceStore() {
    @Deprecated("Use onTaskEnd", ReplaceWith("onTaskEnd(id, costMs)"))
    fun record(id: String, costMs: Long) = onTaskEnd(id, costMs)

    @Deprecated("Use reset", ReplaceWith("reset()"))
    fun clear() = reset()
}
