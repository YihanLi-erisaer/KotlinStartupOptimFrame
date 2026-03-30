package com.ikkoaudio.startup.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class StartupDispatcher {

    fun dispatch(scope: CoroutineScope, task: StartupTask): Job {
        val dispatcher = if (task.runOnMainThread) Dispatchers.Main.immediate else Dispatchers.IO
        return scope.launch(dispatcher) {
            task.run()
        }
    }
}
