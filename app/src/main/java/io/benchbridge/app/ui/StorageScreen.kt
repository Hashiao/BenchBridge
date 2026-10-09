package io.benchbridge.app.ui

import io.benchbridge.app.i18n.L10n

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
        Text(L10n.t("m_9c12ee84a862", report.optInt("completed_rounds"), total), modifier = Modifier.testTag("storage_state_${report.optString("state")}"))
        if (running) {
            val phase = when (report.optString("phase")) {
                "PROBING" -> L10n.t("m_4e44428bb127"); "INITIALIZING" -> L10n.t("m_91082a74792b"); "WARMING" -> L10n.t("m_2051831c1a77")
                "MEASURING" -> L10n.t("m_c7d9b81a063e"); "FLUSHING" -> L10n.t("m_7ecff3ff4b2d"); "VALIDATING" -> L10n.t("m_41b0af8c5c9f")
                "COOLING" -> L10n.t("m_4f5df723d99d"); "CLEANING" -> L10n.t("m_de29ff706893"); else -> L10n.t("m_ddcf6e77b0ee")
            }
            val active = report.optString("phase") in setOf("WARMING", "MEASURING", "FLUSHING", "VALIDATING")
            val config = StorageConfig.fromJson(report.getJSONObject("config").toString())
            val case = config.cases.firstOrNull { it.id == report.optString("current_case") }
            Text(L10n.display(if (active && case != null) L10n.t("m_b07854a3f0a3", phase, case.title, case.subtitle, if (report.optString("current_direction") == "write") L10n.t("m_5c783c467965") else L10n.t("m_534cb3fa8fbf"), report.optInt("current_round", 1), config.rounds) else phase),
                style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(progress = { processed.toFloat() / total.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
            report.optJSONObject("io_progress")?.let { progress ->
                if (report.optString("phase") == "INITIALIZING") Text(L10n.display("${BenchmarkFormat.bytes(progress.optLong("prepared_bytes"))} / ${BenchmarkFormat.mib(config.fileMiB)}"), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (!report.isNull("error")) Text(L10n.display(report.optString("error")), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        if (!running) {
            if (report.optJSONObject("cleanup")?.optString("state") == "CLEANED") Text(L10n.t("m_71b19ba2c1a1"), style = MaterialTheme.typography.bodySmall)
            else TextButton(onClick = { model.retryCleanup(report.getString("run_id")) }) { Text(L10n.t("m_8589564e49b5")) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StorageMatrix(report: JSONObject?, preview: StorageConfig?) {
    val config = report?.let { StorageConfig.fromJson(it.getJSONObject("config").toString()) } ?: preview ?: return
    var unit by rememberSaveable { mutableStateOf("MB/s") }
    var details by rememberSaveable(report?.optString("run_id")) { mutableStateOf(false) }
    SectionCard(if (report == null) L10n.t("m_d67921a192d8") else L10n.t("m_d807482dac5c")) {
        Text(L10n.display(config.fileLabel), style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("storage_result_file"))
        Text(L10n.display(config.timingLabel), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("storage_result_timing"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("MB/s", "GB/s", "IOPS", "µs").forEach { label -> FilterChip(unit == label, { unit = label }, { Text(L10n.display(label)) }) }
        }
        if (unit == "µs") Text(L10n.t("m_0e1ea0269d47"), style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth()) {
            Text(L10n.t("m_5c17b08c9dd4"), Modifier.weight(1.1f), style = MaterialTheme.typography.labelMedium)
            Text(L10n.t("m_534cb3fa8fbf"), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            Text(L10n.t("m_5c783c467965"), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
        }
        config.cases.forEach { case ->
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1.1f)) {
                    Text(L10n.display(case.modeLabel), style = MaterialTheme.typography.labelLarge)
                    Text(L10n.display(case.blockLabel), style = MaterialTheme.typography.labelMedium, modifier = Modifier.testTag("storage_row_block_${case.id}"))
                    Text(L10n.display(case.subtitle), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        direction !in config.directions -> L10n.t("m_3005eadbbbcd")
                        samples.any { it.optString("status") == "UNSUPPORTED" } -> L10n.t("m_7c5378606570")
                        samples.any { it.optString("status") == "FAILED" } -> L10n.t("m_28384d7afd2e")
                        samples.any { it.optString("status") == "INTERRUPTED" } -> L10n.t("m_f9d19345a067")
                        else -> L10n.t("m_e55f2ab3e631")
                    }
                    Text(L10n.display(value?.let { "%.2f".format(Locale.US, it) } ?: emptyLabel),
                        Modifier.weight(1f).testTag("storage_${case.id}_$direction"),
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        color = if (value != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Text(L10n.display("${if (config.direct) "Direct" else "Buffered"} · ${config.directionLabel}" + if (report == null) "" else L10n.t("m_e7add28c3dec")), style = MaterialTheme.typography.bodySmall)
        if (report != null) {
            TextButton(onClick = { details = !details }) { Text(L10n.display(if (details) L10n.t("m_0ae7312510f4") else L10n.t("m_b293459a5303"))) }
            if (details) config.cases.forEach { case -> config.directions.forEach { direction ->
                HorizontalDivider()
                Text(L10n.display("${case.title} ${case.subtitle} · ${if (direction == "write") L10n.t("m_5c783c467965") else L10n.t("m_534cb3fa8fbf")}"), style = MaterialTheme.typography.labelLarge)
                val samples = StorageResults.valid(report, case.id, direction)
                if (samples.isEmpty()) {
                    Text(L10n.display(StorageResults.samples(report, case.id, direction).firstOrNull()?.optString("error") ?: L10n.t("m_69c8006e3904")), style = MaterialTheme.typography.bodySmall)
                } else {
                    val values = samples.map(StorageResults::mbps).sorted()
                    val median = if (values.size % 2 == 1) values[values.size / 2] else (values[values.size / 2 - 1] + values[values.size / 2]) / 2
                    Text(L10n.t("m_6a5053f8d399").format(Locale.US, median) + (StorageResults.cv(samples)?.let { " · CV %.1f%%".format(Locale.US, it) } ?: ""), style = MaterialTheme.typography.bodySmall)
                    samples.forEach { sample ->
                        Text(L10n.display("#${sample.getInt("round")}  %.2f MB/s · P95 %.2f µs · P99 %.2f µs".format(Locale.US,
                            StorageResults.mbps(sample), sample.getLong("latency_p95_ns") / 1000.0, sample.getLong("latency_p99_ns") / 1000.0)), style = MaterialTheme.typography.bodySmall)
                        Text(L10n.t("m_42276cb4c441").format(Locale.US, sample.getDouble("application_qd_mean"),
                            sample.getInt("observed_application_qd_max"), sample.getLong("flush_ns_separate") / 1000000.0), style = MaterialTheme.typography.bodySmall)
                    }
                }
            } }
        }
    }
}
