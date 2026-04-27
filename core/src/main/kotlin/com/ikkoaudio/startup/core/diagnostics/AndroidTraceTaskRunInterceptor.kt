package com.ikkoaudio.startup.core.diagnostics

import android.os.Trace
import com.ikkoaudio.startup.core.StartupTask

/**
 * Wraps each task body with [android.os.Trace.beginSection] / [endSection] for system tracing (systrace/Perfetto).
 * Section names are truncated to 127 bytes as required by [android.os.Trace].
 */
class AndroidTraceTaskRunInterceptor(
    private val prefix: String = "Startup#",
) : TaskRunInterceptor {
    override suspend fun intercept(
        task: StartupTask,
        run: suspend () -> Unit,
    ) {
        val label = (prefix + task.id).asTraceSectionLabel()
        Trace.beginSection(label)
        try {
            run()
        } finally {
            Trace.endSection()
        }
    }

    private fun String.asTraceSectionLabel(): String {
        val max = 127
        // UTF-16 safety: use codepoint boundary approximately via CharSequence
        if (this.length <= max) return this
        return substring(0, max)
    }
}
