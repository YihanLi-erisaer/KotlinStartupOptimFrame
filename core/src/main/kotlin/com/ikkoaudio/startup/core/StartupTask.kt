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

    suspend fun run()
}
