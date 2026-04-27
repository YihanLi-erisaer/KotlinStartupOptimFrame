package com.ikkoaudio.startup.core.diagnostics

/**
 * Receives per-task wall-clock timing after a successful [com.ikkoaudio.startup.core.StartupTask.run].
 * [reset] clears in-memory or batch state ([StartupManager] calls it at the start of a run when needed).
 */
interface TaskTimingSink {
    fun onTaskEnd(taskId: String, costMs: Long)
    fun reset() = Unit
}
