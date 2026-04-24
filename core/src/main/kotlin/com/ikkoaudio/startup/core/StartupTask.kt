package com.ikkoaudio.startup.core

/**
 * A unit of work executed during app startup. Implementations are collected via [StartupTaskProvider]
 * and scheduled by [StartupManager] according to [dependencies] (a DAG).
 */
interface StartupTask {
    val id: String
    val dependencies: List<String>
    val runOnMainThread: Boolean

    /** If true, host code may treat this task as part of the critical path (e.g. splash). */
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
