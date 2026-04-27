package com.ikkoaudio.startup.core.diagnostics

import com.ikkoaudio.startup.core.tracer.TaskTrace
import java.util.Collections

/**
 * Thread-safe in-memory [TaskTrace] list, suitable for debug UI or local assertions.
 * Subclass or extend [com.ikkoaudio.startup.core.tracer.StartupTracer] (singleton) for a shared default.
 */
open class InMemoryTaskTraceStore : TaskTimingSink {
    private val traces = Collections.synchronizedList(mutableListOf<TaskTrace>())

    override fun onTaskEnd(taskId: String, costMs: Long) {
        traces.add(TaskTrace(taskId, costMs))
    }

    override fun reset() {
        traces.clear()
    }

    fun snapshot(): List<TaskTrace> = synchronized(traces) { traces.toList() }

    fun printTo(sink: (String) -> Unit = { println(it) }) {
        sink("\n===== Startup Trace =====")
        snapshot().forEach { sink("${it.id} -> ${it.costMs} ms") }
    }
}
