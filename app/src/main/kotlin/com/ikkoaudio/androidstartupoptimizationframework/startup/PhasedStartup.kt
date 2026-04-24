package com.ikkoaudio.androidstartupoptimizationframework.startup

import android.os.Looper
import android.view.Choreographer
import androidx.activity.ComponentActivity
import com.ikkoaudio.startup.core.ExecutionPhase
import com.ikkoaudio.startup.core.StartupManager
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Production-style execution: [ExecutionPhase.BeforeFirstFrame] first, then two
 * [Choreographer] frame callbacks, then [ExecutionPhase.AfterFirstFrame], then a main [Looper] idle
 * pass, then [ExecutionPhase.Idle].
 */
suspend fun runPhasedStartup(
    @Suppress("UNUSED_PARAMETER")
    activity: ComponentActivity,
    manager: StartupManager,
) {
    var completed = emptySet<String>()
    completed = withContext(Dispatchers.Default) {
        manager.startPhase(ExecutionPhase.BeforeFirstFrame, completed, printDag = false, clearTracer = false)
    }
    withContext(Dispatchers.Main.immediate) {
        awaitChoreographerFrames(2)
    }
    completed = withContext(Dispatchers.Default) {
        manager.startPhase(ExecutionPhase.AfterFirstFrame, completed, printDag = false, clearTracer = false)
    }
    withContext(Dispatchers.Main.immediate) {
        awaitMainLooperIdle()
    }
    withContext(Dispatchers.Default) {
        manager.startPhase(ExecutionPhase.Idle, completed, printDag = false, clearTracer = false)
    }
}

private suspend fun awaitChoreographerFrames(count: Int) {
    require(count > 0)
    val choreographer = Choreographer.getInstance()
    repeat(count) {
        suspendCoroutine { cont ->
            choreographer.postFrameCallback { cont.resume(Unit) }
        }
    }
}

private suspend fun awaitMainLooperIdle() = suspendCoroutine { cont ->
    Looper.myQueue().addIdleHandler {
        cont.resume(Unit)
        false
    }
}
