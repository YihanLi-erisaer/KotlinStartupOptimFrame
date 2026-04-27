package com.ikkoaudio.startup.core

/**
 * Ids of [BeforeFirstFrame][ExecutionPhase.BeforeFirstFrame] tasks that are either matched by
 * [isCritical] or are a (transitive) **dependency** of such a task **within the same phase**.
 * Used by [StartupManager.startBeforeFirstFrameUntilCritical] so splash / routing can wait only
 * for this subset; run the rest of the phase (if any) with
 * [StartupManager.startPhase] using [satisfiedFromEarlier] = first run’s [PhaseResult.successTaskIds].
 */
fun computeBeforeFirstFrameCriticalClosure(
    allTasks: List<StartupTask>,
    isCritical: (StartupTask) -> Boolean,
): Set<String> {
    val bff = allTasks.filter { it.executionPhase == ExecutionPhase.BeforeFirstFrame }
    val byId = bff.associateBy { it.id }
    val seeds = bff.filter(isCritical)
    if (seeds.isEmpty()) return emptySet()
    val closure = LinkedHashSet<String>()
    val queue = ArrayDeque<StartupTask>()
    for (t in seeds) {
        if (closure.add(t.id)) queue.add(t)
    }
    while (queue.isNotEmpty()) {
        val t = queue.removeFirst()
        for (d in t.dependencies) {
            val dep = byId[d] ?: continue
            if (closure.add(dep.id)) queue.add(dep)
        }
    }
    return closure
}
