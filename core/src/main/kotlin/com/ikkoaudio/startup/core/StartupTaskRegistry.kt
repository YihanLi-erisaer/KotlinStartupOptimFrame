package com.ikkoaudio.startup.core

/**
 * Collects [StartupTaskProvider] instances. Prefer constructing your own in **unit tests** and passing
 * [collectTasks] into [StartupManager] so the global [TaskRegistry] is not touched.
 */
open class StartupTaskRegistry {
    private val providers = mutableListOf<StartupTaskProvider>()

    fun register(provider: StartupTaskProvider) {
        providers.add(provider)
    }

    fun clear() {
        providers.clear()
    }

    fun collectTasks(): List<StartupTask> = providers.flatMap { it.provide() }
}

/**
 * Default process-wide registry, typically populated from [android.app.Application.onCreate].
 * For test isolation, use a dedicated [StartupTaskRegistry] and [withTestRegistry] instead.
 */
object TaskRegistry : StartupTaskRegistry()
