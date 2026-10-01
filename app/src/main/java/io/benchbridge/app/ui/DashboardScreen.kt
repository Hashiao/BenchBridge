package io.benchbridge.app.ui

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import io.benchbridge.app.BenchmarkFormat
import io.benchbridge.app.BuildConfig
import io.benchbridge.app.R
import io.benchbridge.app.ram.*
import io.benchbridge.app.storage.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

/** 成绩页无需滚动，行高由可用视口决定。 / A non-scrolling result board with rows sized to the available viewport. */
@Composable
internal fun ResultDashboard(
    disk: Boolean, report: JSONObject?, ramConfig: RamConfig, storageConfig: StorageConfig,
    running: Boolean, error: String?, onSettings: (() -> Unit)?, onDetails: (() -> Unit)?,
    onBack: (() -> Unit)? = null,
) {
    val ram = report?.takeUnless { disk }?.let { RamConfig.fromJson(it.getJSONObject("config").toString()) } ?: ramConfig
    val storage = report?.takeIf { disk }?.let { StorageConfig.fromJson(it.getJSONObject("config").toString()) } ?: storageConfig
    var unit by rememberSaveable(disk, ram.cacheMatrix) { mutableStateOf(if (!disk && ram.cacheMatrix) "GB/s" else "MB/s") }
    var unitsOpen by remember { mutableStateOf(false) }
    val device = report?.optJSONObject("device")
    val manufacturer = device?.optString("manufacturer") ?: Build.MANUFACTURER
    val model = device?.optString("model") ?: Build.MODEL
    val modelLabel = if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model"
    val api = device?.optInt("api", Build.VERSION.SDK_INT) ?: Build.VERSION.SDK_INT
    val locale = LocalLocale.current.platformLocale
    val stamp = report?.optLong("started_at_ms")?.takeIf { it > 0 }?.let {
        SimpleDateFormat("MM-dd HH:mm", locale).format(Date(it))
    }
    BoxWithConstraints(Modifier.fillMaxSize().testTag(if (disk) "storage_page" else "ram_page")) {
        val compact = maxHeight < 440.dp
        val rowDense = if (disk) storage.cases.size > 4 else ram.kinds.size > 4
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = if (compact) 4.dp else 8.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp), modifier = Modifier.width(48.dp)) { Text("返回") }
                Column(Modifier.weight(1f)) {
                    if (!compact) Text("BenchBridge", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (disk) "ROM · 存储" else if (ram.cacheMatrix) "缓存与内存" else "RAM · 内存", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                if (onDetails != null) TextButton(onClick = onDetails, enabled = !running, modifier = Modifier.testTag("result_details")) { Text("详情") }
                if (onSettings != null) IconButton(onClick = onSettings, enabled = !running,
                    modifier = Modifier.testTag(if (disk) "storage_settings" else "ram_settings")) {
                    Icon(painterResource(R.drawable.ic_settings), "测试设置")
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(modelLabel, Modifier.weight(1f).testTag("result_device"), style = MaterialTheme.typography.labelLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("API $api", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    if (disk) {
                        Text(storage.fileLabel, style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("storage_result_file"))
                        Text("${storage.rounds} 次 / 方向 · ${BenchmarkFormat.duration(storage.durationMs)} · ${if (storage.direct) "Direct" else "Buffered"}",
                            style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag("storage_result_timing"))
                    } else if (ram.cacheMatrix) {
                        Text("${if (ram.automaticThreads) "自动校准线程" else "带宽 T${ram.threads}"} · 延迟 T1", style = MaterialTheme.typography.labelMedium)
                        Text("带宽 ${ram.rounds} 次 · 延迟 ${ram.latencyRounds} 次 · 中位数", style = MaterialTheme.typography.labelSmall)
                    } else {
                        Text("每轮 ${BenchmarkFormat.duration(ram.durationMs)} · 中位数", style = MaterialTheme.typography.labelMedium)
                        if (!compact) Text("工作集、线程和次数见各项", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Box {
                    TextButton(onClick = { unitsOpen = true }, modifier = Modifier.testTag("result_unit"),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) { Text("$unit ▾") }
                    DropdownMenu(expanded = unitsOpen, onDismissRequest = { unitsOpen = false }) {
                        (if (disk) listOf("MB/s", "GB/s", "IOPS", "µs") else listOf("MB/s", "GB/s")).forEach { option ->
                            DropdownMenuItem(text = { Text(option) }, onClick = { unit = option; unitsOpen = false }, modifier = Modifier.testTag("unit_$option"))
                        }
                    }
                }
            }
            DashboardStatus(disk, report, running)
            Surface(Modifier.fillMaxWidth().weight(1f).testTag("result_board"), shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 1.dp) {
                Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = if (compact) 4.dp else 10.dp)) {
                    if (disk) {
                        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("块 / Q / T", Modifier.weight(1f), fontSize = 10.sp, lineHeight = 13.sp,
                                maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("读取", Modifier.weight(1.2f), textAlign = TextAlign.End, style = MaterialTheme.typography.labelLarge)
                            Text("写入", Modifier.weight(1.2f), textAlign = TextAlign.End, style = MaterialTheme.typography.labelLarge)
                        }
                        storage.cases.forEachIndexed { index, case ->
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Row(Modifier.fillMaxWidth().weight(1f).testTag("storage_row_$index"), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                                    if (compact || rowDense) {
                                        Text("${if (case.random) "RND" else "SEQ"} ${BenchmarkFormat.kib(case.blockKiB)}",
                                            fontSize = 10.sp, lineHeight = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                                            modifier = Modifier.testTag("storage_row_block_${case.id}"))
                                    } else {
                                        Text(if (case.random) "RND 随机" else "SEQ 顺序", fontWeight = FontWeight.SemiBold,
                                            fontSize = 13.sp, lineHeight = 17.sp, maxLines = 1)
                                        Text(case.blockLabel, fontSize = 12.sp, lineHeight = 16.sp,
                                            modifier = Modifier.testTag("storage_row_block_${case.id}"), maxLines = 1)
                                    }
                                    Text("Q${case.queue}T${case.threads}", fontSize = if (rowDense || compact) 10.sp else 12.sp,
                                        lineHeight = if (rowDense || compact) 13.sp else 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                }
                                for (direction in listOf("read", "write")) {
                                    val best = report?.let { StorageResults.best(it, case.id, direction) }
                                    val samples = report?.let { StorageResults.samples(it, case.id, direction) }.orEmpty()
                                    val value = best?.let { when (unit) {
                                        "GB/s" -> StorageResults.mbps(it) / 1000
                                        "IOPS" -> it.getLong("operations").toDouble() * 1e9 / it.getLong("elapsed_ns")
                                        "µs" -> it.getDouble("latency_mean_ns") / 1000
                                        else -> StorageResults.mbps(it)
                                    } }
                                    val empty = when {
                                        direction !in storage.directions -> "未选"
                                        samples.any { it.optString("status") == "UNSUPPORTED" } -> "不支持"
                                        samples.any { it.optString("status") == "FAILED" } -> "失败"
                                        samples.any { it.optString("status") == "INTERRUPTED" } -> "中断"
                                        else -> "—"
                                    }
                                    Column(Modifier.weight(1.2f).fillMaxHeight().padding(start = 7.dp, top = 5.dp, bottom = 5.dp),
                                        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.End) {
                                        ScoreNumber(value?.let { "%.2f".format(Locale.US, it) } ?: empty,
                                            value != null, "storage_${case.id}_$direction", if (rowDense) 22 else 28,
                                            Modifier.fillMaxWidth().weight(1f, fill = false))
                                        val completed = samples.count { it.optString("status") == "COMPLETED" && it.optBoolean("verified") }
                                        if (value != null && completed < storage.rounds) Text("$completed / ${storage.rounds} 次", fontSize = 9.sp,
                                            lineHeight = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                        Text(if (unit == "µs") "平均延迟 · 吞吐最佳轮次" else "最高完整轮次 · $unit",
                            fontSize = 10.sp, lineHeight = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else if (ram.cacheMatrix) {
                        MemoryMatrix(report, ram, unit, compact)
                    } else {
                        ram.kinds.forEachIndexed { index, code ->
                            val kind = RamKind.entries.first { it.code == code }
                            val stats = report?.let { RamResults.statistics(it, code) }
                            val latency = kind == RamKind.LATENCY
                            val scale = if (latency || unit == "GB/s") 1.0 else 1000.0
                            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Row(Modifier.fillMaxWidth().weight(1f).testTag("ram_row_$code"), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(if (compact) 1.4f else 1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(kind.title + if (compact && code == 2) " · 读写合计" else "",
                                        fontSize = if (compact) 12.sp else 14.sp, lineHeight = if (compact) 16.sp else 20.sp,
                                        fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    Text("${BenchmarkFormat.bytes(ram.bytes(code))} · T${ram.threads(code)}" + if (compact) " · ${ram.rounds(code)}次" else "",
                                        fontSize = if (compact) 10.sp else 11.sp, lineHeight = if (compact) 13.sp else 15.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                    if (!compact) Text("${ram.rounds(code)} 次${if (code == 2) " · 读写合计" else ""}", fontSize = 10.sp,
                                        lineHeight = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                }
                                Column(Modifier.weight(1.7f).fillMaxHeight().padding(start = 8.dp,
                                    top = if (compact) 2.dp else 6.dp, bottom = if (compact) 2.dp else 6.dp),
                                    verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.End) {
                                    ScoreNumber(stats?.let { "%.2f".format(Locale.US, it.median * scale) } ?: "—", stats != null,
                                        "result_$code", if (rowDense || compact) 32 else 42, Modifier.fillMaxWidth().weight(1f, fill = false))
                                    Text(if (latency) "ns" else unit, fontSize = 11.sp, lineHeight = 14.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
            val problem = error ?: report?.takeUnless { it.isNull("error") }?.optString("error")
            if (!problem.isNullOrBlank()) Text(problem, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("dashboard_error"))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("BenchBridge ${(report?.optString("app_version") ?: BuildConfig.VERSION_NAME).substringBefore('-')}",
                    fontSize = 10.sp, lineHeight = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stamp ?: if (disk) storage.directionLabel else "${ram.kinds.size * if (ram.cacheMatrix) 4 else 1} 项测试", fontSize = 10.sp, lineHeight = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ScoreNumber(text: String, numeric: Boolean, tag: String, maximum: Int, modifier: Modifier, align: TextAlign = TextAlign.End, minimum: Int = 12) {
    BasicText(text, modifier.testTag(tag), maxLines = 1,
        style = MaterialTheme.typography.headlineLarge.copy(fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Bold, textAlign = align, lineHeight = TextUnit.Unspecified,
            color = if (numeric) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant),
        autoSize = TextAutoSize.StepBased(minFontSize = minimum.sp, maxFontSize = (if (numeric) maximum else minOf(maximum, 22)).sp, stepSize = 1.sp))
}

/** 每格的参数来自本次执行计划，不从当前设置反推。 / Cell parameters come from the recorded plan, never the current settings. */
@Composable
private fun ColumnScope.MemoryMatrix(report: JSONObject?, config: RamConfig, unit: String, compact: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text("层级", Modifier.width(38.dp), fontSize = 10.sp)
        listOf("连续读", "连续写", "延迟", "拷贝").forEachIndexed { index, title ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 1)
                Text(if (index == 2) "ns" else unit, fontSize = 9.sp, lineHeight = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    MemoryPlanner.levels.forEach { level ->
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth().weight(1f).testTag("matrix_row_$level"), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(38.dp)) {
                if (level != "RAM") Text("CPU", fontSize = 9.sp, lineHeight = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(level, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            MemoryPlanner.columns.forEach { kind ->
                val cell = report?.let { RamResults.cell(it, level, kind) }
                val plan = cell?.optJSONObject("plan")
                val stats = report?.let { RamResults.statistics(it, kind, level) }
                val scale = if (kind == 5 || unit == "GB/s") 1.0 else 1000.0
                Column(Modifier.weight(1f).padding(horizontal = 2.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 5.dp)) {
                    ScoreNumber(stats?.let { "%.2f".format(Locale.US, it.median * scale) } ?: "—", stats != null,
                        "matrix_${level}_$kind", 18, Modifier.fillMaxWidth(), TextAlign.Center, 9)
                    val hint = when {
                        plan != null -> "T${plan.optInt("threads")}"
                        kind !in config.kinds -> "未选"
                        cell?.optString("state") == "UNSUPPORTED" -> "未确认"
                        cell?.optString("state") == "FAILED" -> "失败"
                        cell?.optString("state") == "CALIBRATING" -> "校准中"
                        else -> ""
                    }
                    if (!compact) Text(hint, fontSize = 9.sp, lineHeight = 11.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    BasicText(plan?.let {
                        (if (compact) "$hint · " else "") + BenchmarkFormat.bytes(it.optLong("working_set_bytes"))
                    } ?: if (compact) hint else "",
                        Modifier.fillMaxWidth().testTag("matrix_plan_${level}_$kind"), maxLines = 1,
                        style = MaterialTheme.typography.labelSmall.copy(textAlign = TextAlign.Center, lineHeight = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant),
                        autoSize = TextAutoSize.StepBased(7.sp, if (compact) 9.sp else 10.sp))
                    if (stats != null && stats.count < config.rounds(kind)) Text("${stats.count}/${config.rounds(kind)}次", fontSize = 8.sp, lineHeight = 10.sp)
                }
            }
        }
    }
    Text("T = 线程 · 总工作集 · 拷贝计读写合计", fontSize = 9.sp, lineHeight = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun DashboardStatus(disk: Boolean, report: JSONObject?, running: Boolean) {
    val state = report?.optString("state") ?: if (running) "RUNNING" else "READY"
    val completed = report?.optInt("completed_rounds") ?: 0
    val total = report?.optInt("total_rounds") ?: 0
    val phase = when (report?.optString("phase")) {
        "PROBING" -> "检查支持"; "INITIALIZING" -> "准备文件"; "PREPARING" -> "准备"
        "WARMING" -> "预热"; "MEASURING" -> "测量"; "FLUSHING" -> "同步写入"
        "VALIDATING" -> "校验"; "COOLING" -> "间隔"; "CLEANING" -> "回收文件"; "CALIBRATING" -> "校准"; "CACHE_PROBING" -> "扫描缓存"; else -> "准备"
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        val current = if (!disk && report?.has("current_level") == true) "${report.optString("current_level")} · " else ""
        Text(if (running) "$current$phase · $completed / $total 轮" else RamResults.stateLabel(state),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.testTag((if (disk) "storage_state_" else "run_state_") + state))
        if (running) LinearProgressIndicator(progress = { (report?.optInt("processed_rounds", completed) ?: completed).toFloat() / total.coerceAtLeast(1) }, modifier = Modifier.width(72.dp).height(3.dp))
        else if (report != null) Text("$completed / $total 轮", style = MaterialTheme.typography.labelSmall)
    }
}
