package com.ikkoaudio.androidstartupoptimizationframework.benchmark

import com.ikkoaudio.startup.core.StartupTask

/**
 * 模拟常见的「单协程、固定顺序」启动：所有初始化在同一调用链上串行执行，
 * 即使 [InitNetworkTask] 与 [InitCacheTask] 彼此无依赖也不会并行。
 */
suspend fun runNaiveJetpackStyleStartup(tasks: List<StartupTask>) {
    val byId = tasks.associateBy { it.id }
    val sequentialOrder = listOf("logger", "network", "cache", "database")
    for (id in sequentialOrder) {
        val task = byId[id] ?: error("Missing startup task id=$id (expected demo graph)")
        task.run()
    }
}
