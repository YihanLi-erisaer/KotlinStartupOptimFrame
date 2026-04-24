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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ikkoaudio.startup.core.StartupTask
import com.ikkoaudio.startup.core.tracer.TaskTrace

data class StartupComparisonResult(
    val naiveSequentialTotalMs: Long,
    val frameworkTotalMs: Long,
    val tasks: List<StartupTask>,
    val frameworkTraces: List<TaskTrace>,
)

@Composable
fun StartupComparisonScreen(
    result: StartupComparisonResult,
    modifier: Modifier = Modifier,
) {
    val saved = result.naiveSequentialTotalMs - result.frameworkTotalMs
    val savedPct =
        if (result.naiveSequentialTotalMs > 0) {
            saved * 100f / result.naiveSequentialTotalMs.toFloat()
        } else {
            0f
        }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = "启动耗时对照（模拟延迟）",
                style = MaterialTheme.typography.headlineSmall,
            )
        }
        item {
            Text(
                text = "任务图：logger →（network ∥ cache）→ database。顺序方式串行；框架侧：BeforeFirstFrame 仅 logger，AfterFirstFrame 并行 network/cache，Idle 上 database，并在阶段间等待帧与主线程 Idle。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            ComparisonMetricCard(
                title = "传统顺序初始化（单协程固定顺序）",
                subtitle = "模拟常见写法：逐一 suspend，不根据 DAG 并行",
                totalMs = result.naiveSequentialTotalMs,
                accent = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
        item {
            ComparisonMetricCard(
                title = "启动优化框架（分阶段 + DAG + 帧/Idle 间隙）",
                subtitle = "runPhasedStartup：BeforeFirstFrame → 2×Choreographer 帧 → AfterFirstFrame → main Idle → Idle",
                totalMs = result.frameworkTotalMs,
                accent = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                ),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = "总耗时差值",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = if (saved >= 0) {
                            "节省约 ${saved} ms（约 ${"%.0f".format(savedPct)}%）"
                        } else {
                            "框架多 ${-saved} ms（调度开销或测量噪声；任务过短时可能出现）"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
        item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
        item {
            Text(
                text = "DAG 结构",
                style = MaterialTheme.typography.titleLarge,
            )
        }
        items(result.tasks, key = { "cmp-dag-${it.id}" }) { task ->
            DagTaskCard(task)
        }
        item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
        item {
            Text(
                text = "框架各任务耗时（墙钟）",
                style = MaterialTheme.typography.titleLarge,
            )
        }
        items(result.frameworkTraces, key = { "cmp-tr-${it.id}" }) { trace ->
            TraceRow(trace)
        }
        item {
            Text(
                text = "按 delay 设计值：串行理论下限约 850 ms（100+200+250+300）；并行关键路径约 650 ms（100+max(200,250)+300）。实测因线程调度会略有浮动。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun ComparisonMetricCard(
    title: String,
    subtitle: String,
    totalMs: Long,
    accent: Color,
    contentColor: Color,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = accent),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = contentColor,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = contentColor.copy(alpha = 0.85f),
                modifier = Modifier.padding(vertical = 4.dp),
            )
            Text(
                text = "总耗时：$totalMs ms",
                style = MaterialTheme.typography.headlineMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = contentColor,
            )
        }
    }
}
