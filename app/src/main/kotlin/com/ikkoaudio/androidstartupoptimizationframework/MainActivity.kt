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
import com.ikkoaudio.androidstartupoptimizationframework.ui.StartupDashboard
import com.ikkoaudio.startup.core.StartupManager
import com.ikkoaudio.startup.core.StartupTask
import com.ikkoaudio.startup.core.TaskRegistry
import com.ikkoaudio.startup.core.tracer.StartupTracer
import com.ikkoaudio.startup.core.tracer.TaskTrace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                var tasks by remember { mutableStateOf<List<StartupTask>?>(null) }
                var traces by remember { mutableStateOf<List<TaskTrace>?>(null) }

                LaunchedEffect(Unit) {
                    val collected = TaskRegistry.collectTasks()
                    val manager = StartupManager(collected)
                    withContext(Dispatchers.Default) {
                        manager.start(printDag = false)
                    }
                    tasks = collected
                    traces = StartupTracer.snapshot()
                }

                when {
                    tasks == null || traces == null -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                    else -> {
                        StartupDashboard(
                            tasks = tasks!!,
                            traces = traces!!,
                        )
                    }
                }
            }
        }
    }
}
