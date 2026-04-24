package com.ikkoaudio.startup.core

import java.util.PriorityQueue

/**
 * Ensures every dependency runs in the same or an earlier [ExecutionPhase] (no depending on a "later" phase).
 */
fun validateDependencyPhases(tasks: List<StartupTask>) {
    val byId = tasks.associateBy { it.id }
    for (task in tasks) {
        for (depId in task.dependencies) {
            val dep = byId[depId] ?: continue
            require(dep.executionPhase.ordinal <= task.executionPhase.ordinal) {
                "Task '${task.id}' (${task.executionPhase}) cannot depend on '$depId' (${dep.executionPhase}): dependency must be same or earlier phase"
            }
        }
    }
}

/**
 * Topological order for tasks in [phase] only. In-degrees count only dependencies that are also in this phase;
 * dependencies listed in [completedTaskIds] are treated as already finished and do not add edges.
 */
fun sortTasksForPhase(
    phase: ExecutionPhase,
    allTasks: List<StartupTask>,
    completedTaskIds: Set<String>,
): List<StartupTask> {
    val phaseTasks = allTasks.filter { it.executionPhase == phase }
    if (phaseTasks.isEmpty()) return emptyList()

    val phaseIds = phaseTasks.map { it.id }.toSet()
    val map = phaseTasks.associateBy { it.id }
    val inDegree = mutableMapOf<String, Int>()
    val graph = mutableMapOf<String, MutableList<String>>()

    phaseTasks.forEach { task ->
        inDegree[task.id] = 0
        graph[task.id] = mutableListOf()
    }

    for (task in phaseTasks) {
        for (dep in task.dependencies) {
            when {
                dep in completedTaskIds -> { /* satisfied */ }
                dep in phaseIds -> {
                    graph.getValue(dep).add(task.id)
                    inDegree[task.id] = inDegree.getValue(task.id) + 1
                }
                else -> error("Task '${task.id}' depends on '$dep' which is neither completed nor in phase $phase")
            }
        }
    }

    val readyOrder = Comparator<String> { a, b ->
        val ta = map.getValue(a)
        val tb = map.getValue(b)
        when {
            ta.priority != tb.priority -> Integer.compare(tb.priority, ta.priority)
            else -> ta.id.compareTo(tb.id)
        }
    }
    val queue = PriorityQueue(readyOrder)
    inDegree.filter { it.value == 0 }.keys.forEach { queue.add(it) }

    val result = mutableListOf<StartupTask>()
    while (queue.isNotEmpty()) {
        val current = queue.poll()!!
        result.add(map.getValue(current))
        graph[current]?.forEach { dependentId ->
            val nextDegree = inDegree.getValue(dependentId) - 1
            inDegree[dependentId] = nextDegree
            if (nextDegree == 0) queue.add(dependentId)
        }
    }

    check(result.size == phaseTasks.size) {
        "Cycle detected in startup tasks for phase $phase (or invalid graph state)"
    }
    return result
}

fun sortTasks(tasks: List<StartupTask>): List<StartupTask> {
    if (tasks.isEmpty()) return emptyList()

    val duplicates = tasks.groupBy { it.id }.filter { it.value.size > 1 }.keys
    require(duplicates.isEmpty()) {
        "Duplicate startup task ids: ${duplicates.joinToString()}"
    }
    validateDependencyPhases(tasks)

    val map = tasks.associateBy { it.id }
    val inDegree = mutableMapOf<String, Int>()
    val graph = mutableMapOf<String, MutableList<String>>()

    tasks.forEach { task ->
        inDegree[task.id] = 0
        graph[task.id] = mutableListOf()
    }

    tasks.forEach { task ->
        task.dependencies.forEach { dep ->
            require(dep in map) { "Unknown dependency '$dep' for task '${task.id}'" }
            graph.getValue(dep).add(task.id)
            inDegree[task.id] = inDegree.getValue(task.id) + 1
        }
    }

    val readyOrder = Comparator<String> { a, b ->
        val ta = map.getValue(a)
        val tb = map.getValue(b)
        when {
            ta.priority != tb.priority -> Integer.compare(tb.priority, ta.priority)
            else -> ta.id.compareTo(tb.id)
        }
    }
    val queue = PriorityQueue(readyOrder)
    inDegree.filter { it.value == 0 }.keys.forEach { queue.add(it) }

    val result = mutableListOf<StartupTask>()
    while (queue.isNotEmpty()) {
        val current = queue.poll()!!
        result.add(map.getValue(current))
        graph[current]?.forEach { dependentId ->
            val nextDegree = inDegree.getValue(dependentId) - 1
            inDegree[dependentId] = nextDegree
            if (nextDegree == 0) queue.add(dependentId)
        }
    }

    check(result.size == tasks.size) {
        "Cycle detected in startup tasks (or unresolved graph state)"
    }
    return result
}

fun printDAG(tasks: List<StartupTask>, sink: (String) -> Unit = { println(it) }) {
    sink("\n===== DAG Structure =====")
    tasks.forEach { task ->
        if (task.dependencies.isEmpty()) {
            sink(task.id)
        } else {
            task.dependencies.forEach { dep ->
                sink("$dep -> ${task.id}")
            }
        }
    }
}
