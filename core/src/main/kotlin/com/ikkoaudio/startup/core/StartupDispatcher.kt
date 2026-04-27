package com.ikkoaudio.startup.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StartupDispatcher {

    /**
     * Runs [task.run] in the correct [kotlinx.coroutines.CoroutineDispatcher]; exceptions propagate to the caller
     * (e.g. for [kotlinx.coroutines.supervisorScope] and failure collection).
     */
    suspend fun execute(task: StartupTask) {
        val d = if (task.runOnMainThread) Dispatchers.Main.immediate else Dispatchers.IO
        withContext(d) {
            task.run()
        }
    }

    fun dispatch(scope: CoroutineScope, task: StartupTask): Job {
        val dispatcher = if (task.runOnMainThread) Dispatchers.Main.immediate else Dispatchers.IO
        return scope.launch(dispatcher) {
            task.run()
        }
    }
}
