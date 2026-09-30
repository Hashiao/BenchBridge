package io.benchbridge.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.benchbridge.app.ram.RamResults
import io.benchbridge.app.BenchmarkFormat
import io.benchbridge.app.ram.RamUiState
import io.benchbridge.app.ram.RamViewModel
import io.benchbridge.app.storage.*
import java.util.Locale
import org.json.JSONObject

@Composable
internal fun StorageSummary(report: JSONObject, running: Boolean, model: RamViewModel) {
    SectionCard(RamResults.stateLabel(report.optString("state"))) {
        val processed = report.optInt("processed_rounds")
        val total = report.optInt("total_rounds", 1)
        Text("${report.optInt("completed_rounds")} / $total 轮", modifier = Modifier.testTag("storage_state_${report.optString("state")}"))
        if (running) {
            val phase = when (report.optString("phase")) {
                "PROBING" -> "检查 I/O 支持"; "INITIALIZING" -> "准备测试文件"; "WARMING" -> "预热"
                "MEASURING" -> "测量"; "FLUSHING" -> "同步写入"; "VALIDATING" -> "校验"
                "COOLING" -> "间隔"; "CLEANING" -> "回收文件"; else -> "准备"
            }
            val active = report.optString("phase") in setOf("WARMING", "MEASURING", "FLUSHING", "VALIDATING")
            val config = StorageConfig.fromJson(report.getJSONObject("config").toString())
            val case = config.cases.firstOrNull { it.id == report.optString("current_case") }
            Text(if (active && case != null) "$phase · ${case.title} · ${case.subtitle}\n${if (report.optString("current_direction") == "write") "写入" else "读取"} · 第 ${report.optInt("current_round", 1)} / ${config.rounds} 次" else phase,
                style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(progress = { processed.toFloat() / total.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
            report.optJSONObject("io_progress")?.let { progress ->
                if (report.optString("phase") == "INITIALIZING") Text("${BenchmarkFormat.bytes(progress.optLong("prepared_bytes"))} / ${BenchmarkFormat.mib(config.fileMiB)}", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (!report.isNull("error")) Text(report.optString("error"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        if (!running) {
            if (report.optJSONObject("cleanup")?.optString("state") == "CLEANED") Text("测试文件已回收", style = MaterialTheme.typography.bodySmall)
            else TextButton(onClick = { model.retryCleanup(report.getString("run_id")) }) { Text("重试回收测试文件") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StorageMatrix(report: JSONObject?, preview: StorageConfig?) {
    val config = report?.let { StorageConfig.fromJson(it.getJSONObject("config").toString()) } ?: preview ?: return
    var unit by rememberSaveable { mutableStateOf("MB/s") }
    var details by rememberSaveable(report?.optString("run_id")) { mutableStateOf(false) }
    SectionCard(if (report == null) "测试项目" else "读写结果") {
        Text(config.fileLabel, style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("storage_result_file"))
        Text(config.timingLabel, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("storage_result_timing"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("MB/s", "GB/s", "IOPS", "µs").forEach { label -> FilterChip(unit == label, { unit = label }, { Text(label) }) }
        }
        if (unit == "µs") Text("平均延迟 · 吞吐最佳轮次", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth()) {
            Text("项目 / 块大小", Modifier.weight(1.1f), style = MaterialTheme.typography.labelMedium)
            Text("读取", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            Text("写入", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
        }
        config.cases.forEach { case ->
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1.1f)) {
                    Text(case.modeLabel, style = MaterialTheme.typography.labelLarge)
                    Text(case.blockLabel, style = MaterialTheme.typography.labelMedium, modifier = Modifier.testTag("storage_row_block_${case.id}"))
                    Text(case.subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                for (direction in listOf("read", "write")) {
                    val best = report?.let { StorageResults.best(it, case.id, direction) }
                    val samples = report?.let { StorageResults.samples(it, case.id, direction) }.orEmpty()
                    val value = best?.let {
                        when (unit) {
                            "IOPS" -> it.getLong("operations").toDouble() * 1000000000 / it.getLong("elapsed_ns")
                            "µs" -> it.getDouble("latency_mean_ns") / 1000
                            "GB/s" -> StorageResults.mbps(it) / 1000
                            else -> StorageResults.mbps(it)
                        }
                    }
                    val emptyLabel = when {
                        direction !in config.directions -> "未选"
                        samples.any { it.optString("status") == "UNSUPPORTED" } -> "不支持"
                        samples.any { it.optString("status") == "FAILED" } -> "失败"
                        samples.any { it.optString("status") == "INTERRUPTED" } -> "中断"
                        else -> "待测"
                    }
                    Text(value?.let { "%.2f".format(Locale.US, it) } ?: emptyLabel,
                        Modifier.weight(1f).testTag("storage_${case.id}_$direction"),
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        color = if (value != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Text("${if (config.direct) "Direct" else "Buffered"} · ${config.directionLabel}" + if (report == null) "" else " · 最佳轮次", style = MaterialTheme.typography.bodySmall)
        if (report != null) {
            TextButton(onClick = { details = !details }) { Text(if (details) "收起详情" else "轮次与延迟详情") }
            if (details) config.cases.forEach { case -> config.directions.forEach { direction ->
                HorizontalDivider()
                Text("${case.title} ${case.subtitle} · ${if (direction == "write") "写入" else "读取"}", style = MaterialTheme.typography.labelLarge)
                val samples = StorageResults.valid(report, case.id, direction)
                if (samples.isEmpty()) {
                    Text(StorageResults.samples(report, case.id, direction).firstOrNull()?.optString("error") ?: "尚无完整轮次", style = MaterialTheme.typography.bodySmall)
                } else {
                    val values = samples.map(StorageResults::mbps).sorted()
                    val median = if (values.size % 2 == 1) values[values.size / 2] else (values[values.size / 2 - 1] + values[values.size / 2]) / 2
                    Text("中位数 %.2f MB/s".format(Locale.US, median) + (StorageResults.cv(samples)?.let { " · CV %.1f%%".format(Locale.US, it) } ?: ""), style = MaterialTheme.typography.bodySmall)
                    samples.forEach { sample ->
                        Text("#${sample.getInt("round")}  %.2f MB/s · P95 %.2f µs · P99 %.2f µs".format(Locale.US,
                            StorageResults.mbps(sample), sample.getLong("latency_p95_ns") / 1000.0, sample.getLong("latency_p99_ns") / 1000.0), style = MaterialTheme.typography.bodySmall)
                        Text("应用队列均值 %.1f / 最大 %d · 同步 %.1f ms".format(Locale.US, sample.getDouble("application_qd_mean"),
                            sample.getInt("observed_application_qd_max"), sample.getLong("flush_ns_separate") / 1000000.0), style = MaterialTheme.typography.bodySmall)
                    }
                }
            } }
        }
    }
}
