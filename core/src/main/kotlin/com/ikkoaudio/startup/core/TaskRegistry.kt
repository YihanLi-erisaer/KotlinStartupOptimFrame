package com.ikkoaudio.startup.core

object TaskRegistry {
    private val providers = mutableListOf<StartupTaskProvider>()

    fun register(provider: StartupTaskProvider) {
        providers.add(provider)
    }

    fun clear() {
        providers.clear()
    }

    fun collectTasks(): List<StartupTask> = providers.flatMap { it.provide() }
}
