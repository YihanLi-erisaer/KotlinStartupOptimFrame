package com.ikkoaudio.startup.core

import com.ikkoaudio.startup.core.diagnostics.AndroidTraceTaskRunInterceptor
import com.ikkoaudio.startup.core.diagnostics.CompositeTaskTimingSink
import com.ikkoaudio.startup.core.diagnostics.InMemoryTaskTraceStore
import com.ikkoaudio.startup.core.diagnostics.TaskRunInterceptor
import com.ikkoaudio.startup.core.diagnostics.TaskTimingSink
import com.ikkoaudio.startup.core.tracer.StartupTracer
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private sealed class TaskOutcome {
    data object Success : TaskOutcome()
    data class Failed(val error: Throwable) : TaskOutcome()
}

class StartupManager(
    private val tasks: List<StartupTask>,
    private val dispatcher: StartupDispatcher = StartupDispatcher(),
    private val maxParallelIo: Int? = null,
    private val taskTiming: TaskTimingSink = StartupTracer,
    private val runInterceptor: TaskRunInterceptor = TaskRunInterceptor.None,
) {
    private val ioPermits: Semaphore? =
        maxParallelIo?.coerceIn(1, 10_000)?.let { Semaphore(it) }

    /**
     * Same as the primary constructor with [StartupManager.tasks] = [registry].collectTasks().
     * Useful when wiring from DI or a test-scoped [StartupTaskRegistry] instead of the global [TaskRegistry].
     */
    constructor(
        registry: StartupTaskRegistry,
        dispatcher: StartupDispatcher = StartupDispatcher(),
        maxParallelIo: Int? = null,
        taskTiming: TaskTimingSink = StartupTracer,
        runInterceptor: TaskRunInterceptor = TaskRunInterceptor.None,
    ) : this(registry.collectTasks(), dispatcher, maxParallelIo, taskTiming, runInterceptor)

    init {
        val dup = tasks.groupBy { it.id }.filter { it.value.size > 1 }.keys
        require(dup.isEmpty()) { "Duplicate startup task ids: ${dup.joinToString()}" }
        validateDependencyPhases(tasks)
    }

    /**
     * Optional factory for release defaults: in-memory + Logcat in debug, Android systrace sections in debug.
     * Override as needed in your app module.
     */
    companion object {
        fun defaultInterceptor(debug: Boolean): TaskRunInterceptor =
            if (debug) AndroidTraceTaskRunInterceptor() else TaskRunInterceptor.None
    }

    suspend fun start(
        printDag: Boolean = true,
        sink: (String) -> Unit = { println(it) },
    ): FullStartupResult = coroutineScope {
        taskTiming.reset()
        var satisfied: Set<String> = emptySet()
        var failed: Set<String> = emptySet()
        val byPhase = linkedMapOf<ExecutionPhase, PhaseResult>()
        for (phase in ExecutionPhase.entries) {
            if (tasks.none { it.executionPhase == phase }) continue
            val pr = startPhase(
                phase = phase,
                satisfiedFromEarlier = satisfied,
                failedFromEarlier = failed,
                printDag = printDag,
                sink = sink,
                clearTracer = false,
            )
            byPhase[phase] = pr
            satisfied = satisfied + pr.successTaskIds
            failed = failed + pr.failures.map { it.taskId }.toSet()
        }
        val summary = FullStartupResult(byPhase)
        if (summary.isOverallSuccess) {
            printInMemoryTracesTo(sink)
        } else {
            sink("\n===== Startup: some tasks failed =====")
            summary.allFailures.forEach { sink("FAILED ${it.taskId}: ${it.error.message}") }
            if (hasAnyInMemoryTraces()) printInMemoryTracesTo(sink)
        }
        summary
    }

    /**
     * Runs only the [BeforeFirstFrame][ExecutionPhase.BeforeFirstFrame] **critical closure**:
     * tasks in that phase for which [isCritical] is true, plus all of their (transitive) **in-phase** dependencies.
     * When this [PhaseResult] is returned, those tasks have finished; any remaining
     * [BeforeFirstFrame] work must be started with
     * `startPhase(BeforeFirstFrame, satisfiedFromEarlier = this.successTaskIds, …)`.
     *
     * @param isCritical Defaults to [StartupTask.needWait] so you can “wait for the splash / router” only
     * what you marked; override with a custom lambda if you need a different policy.
     */
    suspend fun startBeforeFirstFrameUntilCritical(
        isCritical: (StartupTask) -> Boolean = { it.needWait },
        satisfiedFromEarlier: Set<String> = emptySet(),
        failedFromEarlier: Set<String> = emptySet(),
        printDag: Boolean = false,
        sink: (String) -> Unit = { println(it) },
        clearTracer: Boolean = false,
    ): PhaseResult {
        val closure = computeBeforeFirstFrameCriticalClosure(tasks, isCritical)
        if (closure.isEmpty()) {
            return PhaseResult(
                successTaskIds = emptySet(),
                failures = emptyList(),
                skipped = emptyList(),
            )
        }
        return startPhase(
            phase = ExecutionPhase.BeforeFirstFrame,
            satisfiedFromEarlier = satisfiedFromEarlier,
            failedFromEarlier = failedFromEarlier,
            printDag = printDag,
            sink = sink,
            clearTracer = clearTracer,
            onlyInPhaseTaskIds = closure,
        )
    }

    private fun hasAnyInMemoryTraces(): Boolean = when (val t = taskTiming) {
        is InMemoryTaskTraceStore -> t.snapshot().isNotEmpty()
        is CompositeTaskTimingSink -> t.sinks.any { (it as? InMemoryTaskTraceStore)?.snapshot()?.isNotEmpty() == true }
        else -> false
    }

    private fun printInMemoryTracesTo(sink: (String) -> Unit) {
        when (val t = taskTiming) {
            is InMemoryTaskTraceStore -> t.printTo(sink)
            is CompositeTaskTimingSink -> t.sinks.filterIsInstance<InMemoryTaskTraceStore>().firstOrNull()?.printTo(sink)
            else -> {}
        }
    }

    suspend fun startPhase(
        phase: ExecutionPhase,
        satisfiedFromEarlier: Set<String> = emptySet(),
        failedFromEarlier: Set<String> = emptySet(),
        printDag: Boolean = false,
        sink: (String) -> Unit = { println(it) },
        clearTracer: Boolean = false,
        onlyInPhaseTaskIds: Set<String>? = null,
    ): PhaseResult = supervisorScope {
        if (clearTracer) taskTiming.reset()

        val (sorted, planSkipped) = planRunnableTasksInPhase(
            phase = phase,
            allTasks = tasks,
            satisfiedFromEarlier = satisfiedFromEarlier,
            failedTaskIds = failedFromEarlier,
            onlyInPhaseTaskIds = onlyInPhaseTaskIds,
        )
        if (printDag) printDAG(sorted, sink)
        if (sorted.isEmpty()) {
            return@supervisorScope PhaseResult(
                successTaskIds = emptySet(),
                failures = emptyList(),
                skipped = planSkipped,
            )
        }

        val phaseFailures = Collections.synchronizedList(mutableListOf<TaskFailure>())
        val phaseRuntimeSkipped = Collections.synchronizedList(
            planSkipped.toMutableList(),
        )
        val outcomes = ConcurrentHashMap<String, TaskOutcome>()
        for (id in satisfiedFromEarlier) {
            outcomes[id] = TaskOutcome.Success
        }

        val jobById = LinkedHashMap<String, Job>()

        sorted.forEach { task ->
            val job = launch {
                for (depId in task.dependencies) {
                    if (depId in satisfiedFromEarlier) continue
                    jobById[depId]!!.join()
                    when (val st = outcomes[depId]) {
                        is TaskOutcome.Failed -> {
                            phaseRuntimeSkipped.add(
                                SkippedTask(
                                    taskId = task.id,
                                    reason = "dependency $depId failed: ${st.error.message}",
                                ),
                            )
                            return@launch
                        }
                        TaskOutcome.Success -> {}
                        null -> error("Missing outcome for dependency $depId of task ${task.id}")
                    }
                }

                val startMs = System.currentTimeMillis()
                try {
                    runInterceptor.intercept(task) {
                        if (task.runOnMainThread) {
                            dispatcher.execute(task)
                        } else {
                            if (ioPermits != null) {
                                ioPermits.withPermit { dispatcher.execute(task) }
                            } else {
                                dispatcher.execute(task)
                            }
                        }
                    }
                    outcomes[task.id] = TaskOutcome.Success
                    val costMs = System.currentTimeMillis() - startMs
                    taskTiming.onTaskEnd(task.id, costMs)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    outcomes[task.id] = TaskOutcome.Failed(e)
                    phaseFailures.add(TaskFailure(task.id, e))
                }
            }
            jobById[task.id] = job
        }

        jobById.values.joinAll()

        val successIds = sorted.mapNotNull { t ->
            if (outcomes[t.id] == TaskOutcome.Success) t.id else null
        }.toSet()

        PhaseResult(
            successTaskIds = successIds,
            failures = phaseFailures.toList(),
            skipped = phaseRuntimeSkipped,
        )
    }
}
