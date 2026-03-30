package com.ikkoaudio.androidstartupoptimizationframework.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.ikkoaudio.startup.core.StartupTask
import com.ikkoaudio.startup.core.tracer.TaskTrace

@Composable
fun StartupDashboard(
    tasks: List<StartupTask>,
    traces: List<TaskTrace>,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = "Startup DAG",
                style = MaterialTheme.typography.headlineSmall,
            )
        }
        // Keys must be unique across the whole LazyColumn (tasks and traces share the same ids).
        items(tasks, key = { "dag-${it.id}" }) { task ->
            DagTaskCard(task)
        }
        item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
        item {
            Text(
                text = "Timings (wall time per task)",
                style = MaterialTheme.typography.headlineSmall,
            )
        }
        items(traces, key = { "trace-${it.id}" }) { trace ->
            TraceRow(trace)
        }
    }
}

@Composable
internal fun DagTaskCard(task: StartupTask) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = task.id,
                style = MaterialTheme.typography.titleMedium,
            )
            val deps = task.dependencies.joinToString(", ").ifBlank { "∅" }
            Text(
                text = "depends on: $deps",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "main=${task.runOnMainThread}, needWait=${task.needWait}, priority=${task.priority}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
internal fun TraceRow(trace: TaskTrace) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Text(
            text = "${trace.id}  —  ${trace.costMs} ms",
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = FontFamily.Monospace,
        )
    }
}
