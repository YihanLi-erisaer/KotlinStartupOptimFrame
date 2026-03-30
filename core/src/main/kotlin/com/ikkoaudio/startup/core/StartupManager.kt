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

    /**
     * Runs the task DAG: each task starts only after all [StartupTask.dependencies] have finished.
     * Independent branches may run in parallel. [sorted] order is topological (with [StartupTask.priority] tie-break);
     * jobs are launched in that order so dependency jobs always exist before dependents suspend on them.
     */
    suspend fun start(
        printDag: Boolean = true,
        sink: (String) -> Unit = { println(it) },
    ) = coroutineScope {
        StartupTracer.clear()
        val sorted = sortTasks(tasks)
        if (printDag) printDAG(sorted, sink)

        val jobById = LinkedHashMap<String, Job>()
        sorted.forEach { task ->
            val job = launch {
                task.dependencies.forEach { depId ->
                    jobById[depId]?.join()
                }
                val startMs = System.currentTimeMillis()
                val inner = dispatcher.dispatch(this@coroutineScope, task)
                inner.join()
                StartupTracer.record(task.id, System.currentTimeMillis() - startMs)
            }
            jobById[task.id] = job
        }

        jobById.values.joinAll()
        StartupTracer.print(sink)
    }
}
