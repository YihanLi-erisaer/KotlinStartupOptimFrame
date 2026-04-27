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
 * [Choreographer.getInstance] and [Looper.myQueue] are tied to the **main** thread’s looper.
 * [awaitChoreographerFrames] and [awaitMainLooperIdle] therefore **always** `withContext(Dispatchers.Main.immediate)`,
 * so it is **safe to call** [runPhasedStartup] from **any** dispatcher; do not assume callers already run on main.
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
    awaitChoreographerFrames(2)
    ensureActive()
    withContext(Dispatchers.Default) {
        runPhaseOnIo(ExecutionPhase.AfterFirstFrame)
    }
    ensureActive()
    awaitMainLooperIdle()
    ensureActive()
    withContext(Dispatchers.Default) {
        runPhaseOnIo(ExecutionPhase.Idle)
    }
}

/**
 * Waits for [count] vsync-pulse frame callbacks. **Must** run on the main looper: this function
 * enforces that by dispatching to [Dispatchers.Main.immediate].
 */
private suspend fun awaitChoreographerFrames(count: Int) = withContext(Dispatchers.Main.immediate) {
    require(count > 0)
    val choreographer = Choreographer.getInstance()
    repeat(count) {
        suspendCoroutine { cont ->
            choreographer.postFrameCallback { cont.resume(Unit) }
        }
    }
}

/**
 * Resumes after the main message queue is idle. **Must** use the main looper’s [android.os.MessageQueue];
 * enforced via [Dispatchers.Main.immediate].
 */
private suspend fun awaitMainLooperIdle() = withContext(Dispatchers.Main.immediate) {
    suspendCoroutine { cont ->
        Looper.myQueue().addIdleHandler {
            cont.resume(Unit)
            false
        }
    }
}
