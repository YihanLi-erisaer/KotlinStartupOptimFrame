package com.ikkoaudio.startup.core

/**
 * A unit of work executed during app startup. Implementations are collected via [StartupTaskProvider]
 * and scheduled by [StartupManager] according to [dependencies] (a DAG).
 */
interface StartupTask {
    val id: String
    val dependencies: List<String>
    val runOnMainThread: Boolean

    /**
     * When true, this task is part of the **critical** [ExecutionPhase.BeforeFirstFrame] path for
     * [StartupManager.startBeforeFirstFrameUntilCritical] (and optional app helpers that run “critical” work first,
     * then the remainder of the phase). The default predicate uses [needWait] together with transitive in-phase
     * dependencies. Callers can wait for splash / routing when this subset finishes, and run the rest of the
     * same phase in a follow-up [StartupManager.startPhase] with [satisfiedFromEarlier] set.
     */
    val needWait: Boolean

    /** Higher runs earlier among tasks that share the same dependency readiness (tie-break in topo sort). */
    val priority: Int get() = 0

    /**
     * When this task is allowed to run. [StartupManager.start] runs all phases back-to-back in one coroutine;
     * use [StartupManager.startPhase] from the app (e.g. after the first frame / on main idle) to decouple from layout.
     */
    val executionPhase: ExecutionPhase get() = ExecutionPhase.BeforeFirstFrame

    suspend fun run()
}
