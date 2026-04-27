package com.ikkoaudio.androidstartupoptimizationframework.startup

import android.os.Build
import android.os.Looper
import android.view.Choreographer
import androidx.activity.ComponentActivity
import com.ikkoaudio.androidstartupoptimizationframework.R
import com.ikkoaudio.startup.core.ExecutionPhase
import com.ikkoaudio.startup.core.StartupManager
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * When to run [ExecutionPhase.BeforeFirstFrame]: all tasks in one [StartupManager.startPhase], or
 * [StartupManager.startBeforeFirstFrameUntilCritical] then the remainder (see [runPhasedStartup]).
 */
enum class BeforeFirstFrameMode {
    /** Single [StartupManager.startPhase] for the whole [BeforeFirstFrame] phase. */
    Full,

    /**
     * [StartupManager.startBeforeFirstFrameUntilCritical] (default: [com.ikkoaudio.startup.core.StartupTask.needWait]),
     * then a second [StartupManager.startPhase] for remaining tasks in that phase.
     */
    CriticalThenRemaining,
}

/**
 * **First frame** and **one-shot** behavior:
 * - For **“fully drawn”** / [android.app.Activity.reportFullyDrawn], use [onAfterVsync] after [Choreographer] frames
 *   (default implementation).
 * - For **Window / decor** hooks, set [onWindowReady] to run on the main thread at the start of
 *   [runPhasedStartup] (e.g. [android.view.View.getViewTreeObserver] or [androidx.core.view.ViewKt.doOnPreDraw]).
 * - To also align with [androidx.lifecycle.ProcessLifecycleOwner], add an observer in [android.app.Application]
 *   and avoid calling [runPhasedStartup] from more than one place, or use [PhasedStartupRunPolicy].
 */
enum class PhasedStartupRunPolicy {
    /** Run every time (e.g. benchmarks). */
    EveryTime,
    /** At most one run per process. */
    AtMostOncePerProcess,
    /** At most one run per [ComponentActivity] instance ([android.view.View] tag on the decor). */
    AtMostOncePerActivity,
}

/**
 * [Choreographer] and [Looper.myQueue] are main-looper; helpers use [Dispatchers.Main.immediate].  
 * [android.os.MessageQueue.addIdleHandler] can wait indefinitely if the main thread never idles; use
 * [idleHandlerTimeoutMs] to **also** continue after a bounded delay (not equivalent to true idle when the UI is
 * always busy).
 */
suspend fun runPhasedStartup(
    activity: ComponentActivity,
    manager: StartupManager,
    runPolicy: PhasedStartupRunPolicy = PhasedStartupRunPolicy.EveryTime,
    beforeFirstFrameMode: BeforeFirstFrameMode = BeforeFirstFrameMode.Full,
    idleHandlerTimeoutMs: Long? = 2_000L,
    onAfterVsync: (ComponentActivity) -> Unit = { a ->
        if (Build.VERSION.SDK_INT >= 19) {
            a.reportFullyDrawn()
        }
    },
    onWindowReady: (ComponentActivity) -> Unit = { },
) {
    if (!enterRunIfAllowedByPolicy(activity, runPolicy)) return

    withContext(Dispatchers.Main.immediate) {
        onWindowReady(activity)
    }

    var satisfied: Set<String> = emptySet()
    var failed: Set<String> = emptySet()

    fun mergeFrom(pr: com.ikkoaudio.startup.core.PhaseResult) {
        satisfied = satisfied + pr.successTaskIds
        failed = failed + pr.failures.map { it.taskId }.toSet()
    }

    suspend fun runPhaseOnIo(phase: ExecutionPhase) {
        val pr = manager.startPhase(
            phase = phase,
            satisfiedFromEarlier = satisfied,
            failedFromEarlier = failed,
            printDag = false,
            clearTracer = false,
        )
        mergeFrom(pr)
    }

    ensureActive()
    withContext(Dispatchers.Default) {
        when (beforeFirstFrameMode) {
            BeforeFirstFrameMode.Full -> {
                runPhaseOnIo(ExecutionPhase.BeforeFirstFrame)
            }
            BeforeFirstFrameMode.CriticalThenRemaining -> {
                val c = manager.startBeforeFirstFrameUntilCritical(
                    isCritical = { it.needWait },
                    satisfiedFromEarlier = satisfied,
                    failedFromEarlier = failed,
                    printDag = false,
                    clearTracer = false,
                )
                mergeFrom(c)
                val rest = manager.startPhase(
                    phase = ExecutionPhase.BeforeFirstFrame,
                    satisfiedFromEarlier = satisfied,
                    failedFromEarlier = failed,
                    printDag = false,
                    clearTracer = false,
                )
                mergeFrom(rest)
            }
        }
    }
    ensureActive()
    awaitChoreographerFrames(2)
    ensureActive()
    withContext(Dispatchers.Main.immediate) {
        onAfterVsync(activity)
    }
    ensureActive()
    withContext(Dispatchers.Default) {
        runPhaseOnIo(ExecutionPhase.AfterFirstFrame)
    }
    ensureActive()
    awaitMainLooperIdle(idleHandlerTimeoutMs)
    ensureActive()
    withContext(Dispatchers.Default) {
        runPhaseOnIo(ExecutionPhase.Idle)
    }
}

private val processPhasedRunOnce: AtomicBoolean = AtomicBoolean(false)

private fun enterRunIfAllowedByPolicy(
    activity: ComponentActivity,
    runPolicy: PhasedStartupRunPolicy,
): Boolean = when (runPolicy) {
    PhasedStartupRunPolicy.EveryTime -> true
    PhasedStartupRunPolicy.AtMostOncePerProcess -> processPhasedRunOnce.compareAndSet(false, true)
    PhasedStartupRunPolicy.AtMostOncePerActivity -> {
        val v = activity.window.decorView
        @Suppress("UNCHECKED_CAST")
        val b = (v.getTag(R.id.phased_startup_ran_once) as? AtomicBoolean) ?: run {
            val n = AtomicBoolean(false)
            v.setTag(R.id.phased_startup_ran_once, n)
            n
        }
        b.compareAndSet(false, true)
    }
}

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
 * Resumes on main **idle** or when [timeoutMs] elapses on the main [android.os.Handler] (whichever first).
 * If you pass `null` for [timeoutMs], only the idle handler is used (possible long wait if the main thread
 * never goes idle).
 */
private suspend fun awaitMainLooperIdle(
    timeoutMs: Long? = 2_000L,
) = withContext(Dispatchers.Main.immediate) {
    if (timeoutMs == null) {
        suspendCoroutine { cont ->
            Looper.myQueue().addIdleHandler {
                cont.resume(Unit)
                false
            }
        }
    } else {
        suspendCancellableCoroutine { cont ->
            var done = false
            val handler = android.os.Handler(Looper.getMainLooper())
            val timeoutRunnable = Runnable {
                if (!cont.isActive) return@Runnable
                if (done) return@Runnable
                done = true
                cont.resume(Unit)
            }
            handler.postDelayed(timeoutRunnable, timeoutMs)
            Looper.myQueue().addIdleHandler {
                if (done || !cont.isActive) return@addIdleHandler false
                done = true
                handler.removeCallbacks(timeoutRunnable)
                cont.resume(Unit)
                false
            }
            cont.invokeOnCancellation {
                handler.removeCallbacks(timeoutRunnable)
            }
        }
    }
}
