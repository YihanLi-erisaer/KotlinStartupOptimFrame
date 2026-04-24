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
import com.ikkoaudio.androidstartupoptimizationframework.benchmark.runNaiveJetpackStyleStartup
import com.ikkoaudio.androidstartupoptimizationframework.ui.StartupComparisonResult
import com.ikkoaudio.androidstartupoptimizationframework.ui.StartupComparisonScreen
import com.ikkoaudio.androidstartupoptimizationframework.startup.runPhasedStartup
import com.ikkoaudio.startup.core.StartupManager
import com.ikkoaudio.startup.core.TaskRegistry
import com.ikkoaudio.startup.core.tracer.StartupTracer
import kotlin.system.measureTimeMillis

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                var comparison by remember { mutableStateOf<StartupComparisonResult?>(null) }

                LaunchedEffect(Unit) {
                    val naiveMs = measureTimeMillis {
                        runNaiveJetpackStyleStartup(TaskRegistry.collectTasks())
                    }
                    StartupTracer.clear()
                    val frameworkMs = measureTimeMillis {
                        runPhasedStartup(
                            this@MainActivity,
                            StartupManager(TaskRegistry.collectTasks()),
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
