package com.ikkoaudio.startup.core.diagnostics

import android.util.Log

/**
 * Forwards [onTaskEnd] to [Log]. Use with [enabled] to turn off in release.
 */
class LogcatTaskTimingSink(
    private val tag: String = "StartupTask",
    private val enabled: () -> Boolean = { true },
) : TaskTimingSink {
    override fun onTaskEnd(taskId: String, costMs: Long) {
        if (!enabled()) return
        Log.d(tag, "$taskId -> ${costMs}ms")
    }
}
