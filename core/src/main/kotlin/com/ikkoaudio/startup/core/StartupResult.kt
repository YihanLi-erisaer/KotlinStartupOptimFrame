package com.ikkoaudio.startup.core

data class TaskFailure(
    val taskId: String,
    val error: Throwable,
)

data class SkippedTask(
    val taskId: String,
    val reason: String,
)

/**
 * Outcome of a single [ExecutionPhase] run (or of [StartupManager.start] as a whole).
 */
data class PhaseResult(
    val successTaskIds: Set<String>,
    val failures: List<TaskFailure>,
    val skipped: List<SkippedTask>,
) {
    val hasTaskFailures: Boolean get() = failures.isNotEmpty()
}

/**
 * Cumulative [StartupManager.start] / multi-phase [startPhase] result.
 */
data class FullStartupResult(
    val phaseResults: Map<ExecutionPhase, PhaseResult>,
) {
    val allSuccesses: Set<String> get() = phaseResults.values.flatMap { it.successTaskIds }.toSet()
    val allFailures: List<TaskFailure> get() = phaseResults.values.flatMap { it.failures }
    val allSkipped: List<SkippedTask> get() = phaseResults.values.flatMap { it.skipped }
    val isOverallSuccess: Boolean get() = allFailures.isEmpty()
}
