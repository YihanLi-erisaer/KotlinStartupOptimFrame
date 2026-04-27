package com.ikkoaudio.startup.core.diagnostics

class CompositeTaskTimingSink(
    val sinks: List<TaskTimingSink>,
) : TaskTimingSink {
    override fun onTaskEnd(taskId: String, costMs: Long) {
        sinks.forEach { it.onTaskEnd(taskId, costMs) }
    }

    override fun reset() {
        sinks.forEach { it.reset() }
    }
}
