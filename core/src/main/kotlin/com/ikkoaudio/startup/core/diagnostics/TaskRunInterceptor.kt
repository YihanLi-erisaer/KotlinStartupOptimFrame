package com.ikkoaudio.startup.core.diagnostics

import com.ikkoaudio.startup.core.StartupTask

/**
 * Observes or wraps the **suspend** body that runs a single [StartupTask] (e.g. Android systrace sections).
 * Compose multiple interceptors in app code, or add a [CompositeTaskRunInterceptor] if needed.
 */
fun interface TaskRunInterceptor {
    suspend fun intercept(
        task: StartupTask,
        run: suspend () -> Unit,
    )

    companion object {
        val None = TaskRunInterceptor { _, run -> run() }
    }
}
