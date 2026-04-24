package com.ikkoaudio.startup.core

import com.ikkoaudio.startup.core.tracer.StartupTracer
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

class StartupManager(
    private val tasks: List<StartupTask>,
    private val dispatcher: StartupDispatcher = StartupDispatcher(),
) {
    init {
        val dup = tasks.groupBy { it.id }.filter { it.value.size > 1 }.keys
        require(dup.isEmpty()) { "Duplicate startup task ids: ${dup.joinToString()}" }
        validateDependencyPhases(tasks)
    }

    /**
     * Runs [ExecutionPhase] slices one after another in a single [coroutineScope] (no real “first frame” / idle wait).
     * Tracer is cleared once at the start; all phases are recorded; suitable for tests and for timing total work
     * without app lifecycle hooks. For production decoupling, call [startPhase] from the UI thread / lifecycle
     * with your own frame and idle gating.
     */
    suspend fun start(
        printDag: Boolean = true,
        sink: (String) -> Unit = { println(it) },
    ) = coroutineScope {
        StartupTracer.clear()
        var completed: Set<String> = emptySet()
        for (phase in ExecutionPhase.entries) {
            if (tasks.none { it.executionPhase == phase }) continue
            completed = startPhase(phase, completed, printDag = printDag, sink = sink, clearTracer = false)
        }
        StartupTracer.print(sink)
    }

    /**
     * Runs all tasks in [phase] whose dependencies are either in [completedTaskIds] (earlier phases) or
     * the same phase (DAG parallelism within the phase). Returns the union of [completedTaskIds] and ids
     * completed in this call.
     */
    suspend fun startPhase(
        phase: ExecutionPhase,
        completedTaskIds: Set<String> = emptySet(),
        printDag: Boolean = false,
        sink: (String) -> Unit = { println(it) },
        clearTracer: Boolean = false,
    ): Set<String> = coroutineScope {
        if (clearTracer) StartupTracer.clear()
        val phaseTasks = tasks.filter { it.executionPhase == phase }
        if (phaseTasks.isEmpty()) return@coroutineScope completedTaskIds

        val sorted = sortTasksForPhase(phase, tasks, completedTaskIds)
        if (printDag) printDAG(sorted, sink)

        val jobById = LinkedHashMap<String, Job>()
        sorted.forEach { task ->
            val job = launch {
                task.dependencies.forEach { depId ->
                    when {
                        depId in completedTaskIds -> { /* already finished in a previous phase */ }
                        else -> jobById[depId]?.join()
                            ?: error("Internal: missing job for '$depId' while starting '${task.id}' in phase $phase")
                    }
                }
                val startMs = System.currentTimeMillis()
                val inner = dispatcher.dispatch(this@coroutineScope, task)
                inner.join()
                StartupTracer.record(task.id, System.currentTimeMillis() - startMs)
            }
            jobById[task.id] = job
        }

        jobById.values.joinAll()
        completedTaskIds + phaseTasks.map { it.id }.toSet()
    }
}
