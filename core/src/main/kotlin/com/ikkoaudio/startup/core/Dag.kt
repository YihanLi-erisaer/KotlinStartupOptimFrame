package com.ikkoaudio.startup.core

import java.util.PriorityQueue

fun sortTasks(tasks: List<StartupTask>): List<StartupTask> {
    if (tasks.isEmpty()) return emptyList()

    val ids = tasks.map { it.id }.toSet()
    val duplicates = tasks.groupBy { it.id }.filter { it.value.size > 1 }.keys
    require(duplicates.isEmpty()) {
        "Duplicate startup task ids: ${duplicates.joinToString()}"
    }

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
