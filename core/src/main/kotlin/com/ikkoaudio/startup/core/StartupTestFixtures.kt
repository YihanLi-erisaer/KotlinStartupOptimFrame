package com.ikkoaudio.startup.core

/**
 * Creates a throwaway [StartupTaskRegistry], configures it, and passes it to [block] so tests and
 * `@Parameterized` code avoid mutating the global [TaskRegistry].
 */
inline fun <R> withTestRegistry(
    crossinline configure: StartupTaskRegistry.() -> Unit,
    block: (StartupTaskRegistry) -> R,
): R {
    val registry = StartupTaskRegistry()
    registry.configure()
    return block(registry)
}
