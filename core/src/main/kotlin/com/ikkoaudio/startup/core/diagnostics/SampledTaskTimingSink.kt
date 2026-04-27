package com.ikkoaudio.startup.core.diagnostics

import java.util.concurrent.ThreadLocalRandom

/**
 * Forwards a fraction of [onTaskEnd] events to [delegate]. [sampleProbability] in `0.0`…`1.0` (1.0 = all).
 * [reset] is forwarded to [delegate] when present.
 */
class SampledTaskTimingSink(
    private val delegate: TaskTimingSink,
    private val sampleProbability: Double,
) : TaskTimingSink {
    init {
        require(sampleProbability in 0.0..1.0) { "sampleProbability must be 0.0..1.0" }
    }

    override fun onTaskEnd(taskId: String, costMs: Long) {
        if (ThreadLocalRandom.current().nextDouble() < sampleProbability) {
            delegate.onTaskEnd(taskId, costMs)
        }
    }

    override fun reset() {
        delegate.reset()
    }
}
