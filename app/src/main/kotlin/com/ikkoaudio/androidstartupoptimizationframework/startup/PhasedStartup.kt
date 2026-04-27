package com.ikkoaudio.androidstartupoptimizationframework.startup

import android.os.Looper
import android.view.Choreographer
import androidx.activity.ComponentActivity
import com.ikkoaudio.startup.core.ExecutionPhase
import com.ikkoaudio.startup.core.StartupManager
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Production-style execution: [ExecutionPhase.BeforeFirstFrame] first, then two
 * [Choreographer] frame callbacks, then [ExecutionPhase.AfterFirstFrame], then a main [Looper] idle
 * pass, then [ExecutionPhase.Idle].
 *
 * Call this from a scope that is **cancelled when the activity is destroyed** (e.g.
 * [androidx.lifecycle.lifecycleScope] or [androidx.lifecycle.repeatOnLifecycle]) so init work stops
 * when the UI goes away. [ComponentActivity] is kept for API symmetry and future hooks
 * (e.g. [android.app.Activity.reportFullyDrawn]).
 */
suspend fun runPhasedStartup(
    @Suppress("UNUSED_PARAMETER")
    activity: ComponentActivity,
    manager: StartupManager,
) {
    var satisfied: Set<String> = emptySet()
    var failed: Set<String> = emptySet()

    suspend fun runPhaseOnIo(phase: ExecutionPhase) {
        val pr = manager.startPhase(
            phase = phase,
            satisfiedFromEarlier = satisfied,
            failedFromEarlier = failed,
            printDag = false,
            clearTracer = false,
        )
        satisfied = satisfied + pr.successTaskIds
        failed = failed + pr.failures.map { it.taskId }.toSet()
    }

    ensureActive()
    withContext(Dispatchers.Default) {
        runPhaseOnIo(ExecutionPhase.BeforeFirstFrame)
    }
    ensureActive()
    withContext(Dispatchers.Main.immediate) {
        awaitChoreographerFrames(2)
    }
    ensureActive()
    withContext(Dispatchers.Default) {
        runPhaseOnIo(ExecutionPhase.AfterFirstFrame)
    }
    ensureActive()
    withContext(Dispatchers.Main.immediate) {
        awaitMainLooperIdle()
    }
    ensureActive()
    withContext(Dispatchers.Default) {
        runPhaseOnIo(ExecutionPhase.Idle)
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
