package com.ikkoaudio.startup.core.tracer

import java.util.Collections

data class TaskTrace(
    val id: String,
    val costMs: Long,
)

object StartupTracer {
    private val traces = Collections.synchronizedList(mutableListOf<TaskTrace>())

    fun record(id: String, costMs: Long) {
        traces.add(TaskTrace(id, costMs))
    }

    fun snapshot(): List<TaskTrace> = synchronized(traces) { traces.toList() }

    fun clear() {
        traces.clear()
    }

    fun print(sink: (String) -> Unit = { println(it) }) {
        sink("\n===== Startup Trace =====")
        snapshot().forEach { sink("${it.id} -> ${it.costMs} ms") }
    }
}
