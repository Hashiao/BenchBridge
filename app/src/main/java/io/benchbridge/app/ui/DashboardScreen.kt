package io.benchbridge.app.ui

import io.benchbridge.app.i18n.L10n

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
    val locale = Locale.forLanguageTag(L10n.tag)
    val stamp = report?.optLong("started_at_ms")?.takeIf { it > 0 }?.let {
        SimpleDateFormat("MM-dd HH:mm", locale).format(Date(it))
    }
    BoxWithConstraints(Modifier.fillMaxSize().testTag(if (disk) "storage_page" else "ram_page")) {
        val compact = maxHeight < 440.dp
        val rowDense = if (disk) storage.cases.size > 4 else ram.kinds.size > 4
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = if (compact) 4.dp else 8.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp), modifier = Modifier.width(48.dp)) { Text(L10n.t("m_572cf45ba436")) }
                Column(Modifier.weight(1f)) {
                    if (!compact) Text(L10n.display("BenchBridge"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(L10n.display(if (disk) L10n.t("m_7e67761835cd") else if (ram.cacheMatrix) L10n.t("m_df9062b60024") else L10n.t("m_306cecd130cb")), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                if (onDetails != null) TextButton(onClick = onDetails, enabled = !running, modifier = Modifier.testTag("result_details")) { Text(L10n.t("m_979a332955c8")) }
                if (onSettings != null) IconButton(onClick = onSettings, enabled = !running,
                    modifier = Modifier.testTag(if (disk) "storage_settings" else "ram_settings")) {
                    Icon(painterResource(R.drawable.ic_settings), L10n.t("m_1a948ecc9480"))
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(L10n.display(modelLabel), Modifier.weight(1f).testTag("result_device"), style = MaterialTheme.typography.labelLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(L10n.display("API $api"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    if (disk) {
                        Text(L10n.display(storage.fileLabel), style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("storage_result_file"))
                        Text(L10n.t("m_b64bd273c6ea", storage.rounds, BenchmarkFormat.duration(storage.durationMs), if (storage.direct) "Direct" else "Buffered"),
                            style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag("storage_result_timing"))
                    } else if (ram.cacheMatrix) {
                        Text(L10n.display(if(ram.curveMode)if(compact)L10n.t("m_848b82159aca")else L10n.t("m_038ea40471f9")else L10n.t("m_3300bd599b46", if (ram.automaticThreads) L10n.t("m_da0900279835") else L10n.t("m_989de77d7077", ram.threads))), style = MaterialTheme.typography.labelMedium,maxLines=1)
                        Text(L10n.display(if(ram.curveMode)if(ram.singleCurveSample)L10n.t("m_1b4527ef3747")else if(ram.curveProtocol==CacheProbe.METHOD)L10n.t("m_66c9701d89aa", ram.curveSteps)else L10n.t("m_dec5d5569b23")else L10n.t("m_8af5c0e0f4cf", ram.rounds, ram.latencyRounds, ram.statisticLabel)), style = MaterialTheme.typography.labelSmall,maxLines=1)
                    } else {
                        Text(L10n.t("m_d9728b3b2178", BenchmarkFormat.duration(ram.durationMs), ram.statisticLabel), style = MaterialTheme.typography.labelMedium)
                        if (!compact) Text(L10n.t("m_6b9ed24135e3"), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if(!ram.curveMode || ram.curveIncludeRam || disk)Box {
                    TextButton(onClick = { unitsOpen = true }, modifier = Modifier.testTag("result_unit"),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                        Text(L10n.display(if(!disk&&ram.curveMode)"RAM $unit ▾"else"$unit ▾"),fontSize=if(compact&&ram.curveMode)10.sp else 14.sp)
                    }
                    DropdownMenu(expanded = unitsOpen, onDismissRequest = { unitsOpen = false }) {
                        (if (disk) listOf("MB/s", "GB/s", "IOPS", "µs") else listOf("MB/s", "GB/s")).forEach { option ->
                            DropdownMenuItem(text = { Text(L10n.display(option)) }, onClick = { unit = option; unitsOpen = false }, modifier = Modifier.testTag("unit_$option"))
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
                            Text(L10n.t("m_aa92ccbc4662"), Modifier.weight(1f), fontSize = 10.sp, lineHeight = 13.sp,
                                maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(L10n.t("m_534cb3fa8fbf"), Modifier.weight(1.2f), textAlign = TextAlign.End, style = MaterialTheme.typography.labelLarge)
                            Text(L10n.t("m_5c783c467965"), Modifier.weight(1.2f), textAlign = TextAlign.End, style = MaterialTheme.typography.labelLarge)
                        }
                        storage.cases.forEachIndexed { index, case ->
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Row(Modifier.fillMaxWidth().weight(1f).testTag("storage_row_$index"), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                                    if (compact || rowDense) {
                                        Text(L10n.display("${if (case.random) "RND" else "SEQ"} ${BenchmarkFormat.kib(case.blockKiB)}"),
                                            fontSize = 10.sp, lineHeight = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                                            modifier = Modifier.testTag("storage_row_block_${case.id}"))
                                    } else {
                                        Text(L10n.display(if (case.random) L10n.t("m_1006a8f10ade") else L10n.t("m_3a78d2b3457d")), fontWeight = FontWeight.SemiBold,
                                            fontSize = 13.sp, lineHeight = 17.sp, maxLines = 1)
                                        Text(L10n.display(case.blockLabel), fontSize = 12.sp, lineHeight = 16.sp,
                                            modifier = Modifier.testTag("storage_row_block_${case.id}"), maxLines = 1)
                                    }
                                    Text(L10n.display("Q${case.queue}T${case.threads}"), fontSize = if (rowDense || compact) 10.sp else 12.sp,
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
                                        direction !in storage.directions -> L10n.t("m_3005eadbbbcd")
                                        samples.any { it.optString("status") == "UNSUPPORTED" } -> L10n.t("m_7c5378606570")
                                        samples.any { it.optString("status") == "FAILED" } -> L10n.t("m_28384d7afd2e")
                                        samples.any { it.optString("status") == "INTERRUPTED" } -> L10n.t("m_f9d19345a067")
                                        else -> "—"
                                    }
                                    Column(Modifier.weight(1.2f).fillMaxHeight().padding(start = 7.dp, top = 5.dp, bottom = 5.dp),
                                        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.End) {
                                        ScoreNumber(value?.let { "%.2f".format(Locale.US, it) } ?: empty,
                                            value != null, "storage_${case.id}_$direction", if (rowDense) 22 else 28,
                                            Modifier.fillMaxWidth().weight(1f, fill = false))
                                        val completed = samples.count { it.optString("status") == "COMPLETED" && it.optBoolean("verified") }
                                        if (value != null && completed < storage.rounds) Text(L10n.t("m_6fa2cf18a5a6", completed, storage.rounds), fontSize = 9.sp,
                                            lineHeight = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                        Text(L10n.display(if (unit == "µs") L10n.t("m_0e1ea0269d47") else L10n.t("m_83d947e93231", unit)),
                            fontSize = 10.sp, lineHeight = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else if (ram.cacheMatrix) {
                        if(ram.curveMode)CacheCurveBoard(report,ram,unit,compact)else MemoryMatrix(report, ram, unit, compact)
                    } else {
                        ram.kinds.forEachIndexed { index, code ->
                            val kind = RamKind.entries.first { it.code == code }
                            val stats = report?.let { RamResults.statistics(it, code) }
                            val latency = kind == RamKind.LATENCY
                            val scale = if (latency || unit == "GB/s") 1.0 else 1000.0
                            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Row(Modifier.fillMaxWidth().weight(1f).testTag("ram_row_$code"), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(if (compact) 1.4f else 1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(L10n.display(kind.title + if (compact && code == 2) L10n.t("m_cced402618e3") else ""),
                                        fontSize = if (compact) 12.sp else 14.sp, lineHeight = if (compact) 16.sp else 20.sp,
                                        fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    Text(L10n.display("${BenchmarkFormat.bytes(ram.bytes(code))} · T${ram.threads(code)}" + if (compact) L10n.t("m_fe4cd9cc4017", ram.rounds(code)) else ""),
                                        fontSize = if (compact) 10.sp else 11.sp, lineHeight = if (compact) 13.sp else 15.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                    if (!compact) Text(L10n.t("m_3131760519d0", ram.rounds(code), if (code == 2) L10n.t("m_cced402618e3") else ""), fontSize = 10.sp,
                                        lineHeight = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                }
                                Column(Modifier.weight(1.7f).fillMaxHeight().padding(start = 8.dp,
                                    top = if (compact) 2.dp else 6.dp, bottom = if (compact) 2.dp else 6.dp),
                                    verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.End) {
                                    ScoreNumber(stats?.let { "%.2f".format(Locale.US, it.score * scale) } ?: "—", stats != null,
                                        "result_$code", if (rowDense || compact) 32 else 42, Modifier.fillMaxWidth().weight(1f, fill = false))
                                    Text(L10n.display(if (latency) "ns" else unit), fontSize = 11.sp, lineHeight = 14.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
            val problem = error ?: report?.takeUnless { it.isNull("error") }?.optString("error")
            if (!problem.isNullOrBlank()) Text(L10n.display(problem), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("dashboard_error"))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(L10n.display("BenchBridge ${(report?.optString("app_version") ?: BuildConfig.VERSION_NAME).substringBefore('-')}"),
                    fontSize = 10.sp, lineHeight = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(L10n.display(stamp ?: if (disk) storage.directionLabel else if(ram.curveMode)L10n.t("m_f0e1147ac22e")else L10n.t("m_6112d84e742d", ram.kinds.size * if (ram.cacheMatrix) 4 else 1)), fontSize = 10.sp, lineHeight = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun ScoreNumber(text: String, numeric: Boolean, tag: String, maximum: Int, modifier: Modifier, align: TextAlign = TextAlign.End, minimum: Int = 12) {
    BasicText(L10n.display(text), modifier.testTag(tag), maxLines = 1,
        style = MaterialTheme.typography.headlineLarge.copy(fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Bold, textAlign = align, lineHeight = TextUnit.Unspecified,
            color = if (numeric) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant),
        autoSize = TextAutoSize.StepBased(minFontSize = minimum.sp, maxFontSize = (if (numeric) maximum else minOf(maximum, 22)).sp, stepSize = 1.sp))
}

/** 每格的参数来自本次执行计划，不从当前设置反推。 / Cell parameters come from the recorded plan, never the current settings. */
@Composable
private fun ColumnScope.MemoryMatrix(report: JSONObject?, config: RamConfig, unit: String, compact: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(L10n.t("m_ab033612a2da"), Modifier.width(38.dp), fontSize = 10.sp)
        listOf(L10n.t("m_74c9c9420522"), L10n.t("m_56e7ec7ec949"), L10n.t("m_18045b8c40f1"), L10n.t("m_d373809ab86b")).forEachIndexed { index, title ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(L10n.display(title), fontSize = 11.sp, lineHeight = 15.sp, maxLines = 1)
                Text(L10n.display(if (index == 2) "ns" else unit), fontSize = 9.sp, lineHeight = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    MemoryPlanner.levels.forEach { level ->
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth().weight(1f).testTag("matrix_row_$level"), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(38.dp)) {
                if (level != "RAM") Text(L10n.display("CPU"), fontSize = 9.sp, lineHeight = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(L10n.display(level), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            MemoryPlanner.columns.forEach { kind ->
                val cell = report?.let { RamResults.cell(it, level, kind) }
                val plan = cell?.optJSONObject("plan")
                val stats = report?.let { RamResults.statistics(it, kind, level) }
                val scale = if (kind == 5 || unit == "GB/s") 1.0 else 1000.0
                Column(Modifier.weight(1f).padding(horizontal = 2.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 5.dp)) {
                    ScoreNumber(stats?.let { "%.2f".format(Locale.US, it.score * scale) } ?: "—", stats != null,
                        "matrix_${level}_$kind", 18, Modifier.fillMaxWidth(), TextAlign.Center, 9)
                    val hint = when {
                        plan != null -> (if(plan.optString("binding_mode")=="system_scheduled")L10n.t("m_d0e5a537ba17")else"")+"T${plan.optInt("threads")}"
                        kind !in config.kinds -> L10n.t("m_3005eadbbbcd")
                        cell?.optString("state") == "UNSUPPORTED" -> L10n.t("m_c098e854e60b")
                        cell?.optString("state") == "FAILED" -> L10n.t("m_28384d7afd2e")
                        cell?.optString("state") == "CALIBRATING" -> L10n.t("m_05eccf84ad3a")
                        else -> ""
                    }
                    if (!compact) Text(L10n.display(hint), fontSize = 9.sp, lineHeight = 11.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    BasicText(L10n.display(plan?.let {
                        (if (compact) "$hint · " else "") + BenchmarkFormat.bytes(it.optLong("working_set_bytes"))
                    } ?: if (compact) hint else ""),
                        Modifier.fillMaxWidth().testTag("matrix_plan_${level}_$kind"), maxLines = 1,
                        style = MaterialTheme.typography.labelSmall.copy(textAlign = TextAlign.Center, lineHeight = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant),
                        autoSize = TextAutoSize.StepBased(7.sp, if (compact) 9.sp else 10.sp))
                    if (stats != null && stats.count < config.rounds(kind)) Text(L10n.t("m_8bd8dc289edf", stats.count, config.rounds(kind)), fontSize = 8.sp, lineHeight = 10.sp)
                }
            }
        }
    }
    Text(L10n.t("m_d82e260a405e"), fontSize = 9.sp, lineHeight = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun DashboardStatus(disk: Boolean, report: JSONObject?, running: Boolean) {
    val state = report?.optString("state") ?: if (running) "RUNNING" else "READY"
    val completed = report?.optInt("completed_rounds") ?: 0
    val total = report?.optInt("total_rounds") ?: 0
    val phase = when (report?.optString("phase")) {
        "PROBING" -> L10n.t("m_0028fe7600eb"); "INITIALIZING" -> L10n.t("m_554ec133ab30"); "PREPARING" -> L10n.t("m_ddcf6e77b0ee")
        "WARMING" -> L10n.t("m_2051831c1a77"); "MEASURING" -> L10n.t("m_c7d9b81a063e"); "FLUSHING" -> L10n.t("m_7ecff3ff4b2d")
        "VALIDATING" -> L10n.t("m_41b0af8c5c9f"); "COOLING" -> L10n.t("m_4f5df723d99d"); "CLEANING" -> L10n.t("m_de29ff706893"); "CALIBRATING" -> L10n.t("m_45230cc916f6"); "CACHE_PROBING" -> L10n.t("m_8a839ce77df0"); else -> L10n.t("m_ddcf6e77b0ee")
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        val current = if (!disk && report?.has("current_level") == true) "${report.optString("current_level")} · " else ""
        val probe=report?.optJSONObject("cache_probe")
        val curve=report?.optJSONObject("config")?.optBoolean("cache_curve")==true
        val statusText=when {
            running && curve && report.optString("phase")=="CACHE_PROBING" -> L10n.t("m_4eedec34b59e", probe?.optInt("current_cpu_id"), BenchmarkFormat.bytes(probe?.optLong("current_working_set_bytes")?:0))
            running -> L10n.t("m_4a52073e7910", current, phase, completed, total)
            curve && state=="PARTIAL" -> if(report!=null&&RamResults.stateLabel(report)==L10n.t("m_d3174a42c726"))
                L10n.t("m_d3174a42c726") else L10n.t("m_cc78d365d7b6")
            else -> RamResults.stateLabel(state)
        }
        Text(L10n.display(statusText),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.testTag((if (disk) "storage_state_" else "run_state_") + state))
        val probing=curve && (total==0 || report?.optString("phase")=="CACHE_PROBING")
        val done=if(probing)probe?.optInt("completed_points")?:0 else report?.optInt("processed_rounds",completed)?:completed
        val planned=if(probing)probe?.optInt("planned_points")?:0 else total
        if (running) LinearProgressIndicator(progress = { (done.toFloat()/planned.coerceAtLeast(1)).coerceIn(0f,1f) }, modifier = Modifier.width(72.dp).height(3.dp))
        else if (report != null) Text(L10n.display(if(probing)L10n.t("m_33ff1e3cf705", done, planned)else L10n.t("m_9c12ee84a862", completed, total)), style = MaterialTheme.typography.labelSmall)
    }
}
