package com.ikkoaudio.androidstartupoptimizationframework

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.ikkoaudio.androidstartupoptimizationframework.benchmark.runNaiveJetpackStyleStartup
import com.ikkoaudio.androidstartupoptimizationframework.startup.runPhasedStartup
import com.ikkoaudio.androidstartupoptimizationframework.ui.StartupComparisonResult
import com.ikkoaudio.androidstartupoptimizationframework.ui.StartupComparisonScreen
import com.ikkoaudio.startup.core.StartupManager
import com.ikkoaudio.startup.core.TaskRegistry
import com.ikkoaudio.startup.core.diagnostics.CompositeTaskTimingSink
import com.ikkoaudio.startup.core.diagnostics.LogcatTaskTimingSink
import com.ikkoaudio.startup.core.tracer.StartupTracer
import kotlin.system.measureTimeMillis

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                val lifecycle = LocalLifecycleOwner.current.lifecycle
                var comparison by remember { mutableStateOf<StartupComparisonResult?>(null) }

                LaunchedEffect(lifecycle) {
                    lifecycle.repeatOnLifecycle(Lifecycle.State.CREATED) {
                        val naiveMs = measureTimeMillis {
                            runNaiveJetpackStyleStartup(TaskRegistry.collectTasks())
                        }
                        StartupTracer.reset()
                        val frameworkMs = measureTimeMillis {
                            runPhasedStartup(
                                this@MainActivity,
                                StartupManager(
                                    tasks = TaskRegistry.collectTasks(),
                                    maxParallelIo = 4,
                                    taskTiming = CompositeTaskTimingSink(
                                        listOf(
                                            StartupTracer,
                                            LogcatTaskTimingSink(enabled = { BuildConfig.DEBUG }),
                                        ),
                                    ),
                                    runInterceptor = StartupManager.defaultInterceptor(
                                        BuildConfig.DEBUG,
                                    ),
                                ),
                            )
                        }
                        val traces = StartupTracer.snapshot()
                        val tasksForUi = TaskRegistry.collectTasks()
                        comparison = StartupComparisonResult(
                            naiveSequentialTotalMs = naiveMs,
                            frameworkTotalMs = frameworkMs,
                            tasks = tasksForUi,
                            frameworkTraces = traces,
                        )
                    }
                }

                when (val c = comparison) {
                    null -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                    else -> StartupComparisonScreen(result = c)
                }
            }
        }
    }
}
