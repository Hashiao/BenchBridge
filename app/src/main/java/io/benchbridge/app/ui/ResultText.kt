package io.benchbridge.app.ui

import io.benchbridge.app.BenchmarkFormat
import io.benchbridge.app.ram.*
import io.benchbridge.app.storage.*
import java.util.Locale
import org.json.JSONObject

/** 剪贴板仅包含成绩摘要，避免超过 Binder 容量。 / Copy score summaries to stay within Binder transaction limits. */
internal fun resultText(report: JSONObject): String = buildString {
    val disk = report.optString("kind") == "storage_benchmark"
    appendLine("BenchBridge ${report.optString("app_version").take(80)} · ${if (disk) "ROM" else "RAM"}")
    report.optJSONObject("device")?.let {
        appendLine("${it.optString("manufacturer").take(128)} ${it.optString("model").take(256)} · API ${it.optInt("api")}")
    }
    appendLine("${RamResults.stateLabel(report.optString("state"))} · ${report.optInt("completed_rounds")} / ${report.optInt("total_rounds")} 轮")
    if (disk) {
        val config = StorageConfig.fromJson(report.getJSONObject("config").toString())
        appendLine(config.summary)
        config.cases.forEach { case ->
            appendLine("${case.title} · ${case.subtitle}")
            for (direction in listOf("read", "write")) {
                val samples = StorageResults.valid(report, case.id, direction)
                val score = StorageResults.best(report, case.id, direction)?.let { "%.2f MB/s".format(Locale.US, StorageResults.mbps(it)) } ?: "—"
                appendLine("${if (direction == "read") "读取" else "写入"}：$score · ${samples.size} / ${config.rounds} 次")
            }
        }
        appendLine("主值：最高完整轮次")
    } else {
        val config = RamConfig.fromJson(report.getJSONObject("config").toString())
        appendLine("每轮 ${BenchmarkFormat.duration(config.durationMs)} · 中位数")
        config.kinds.forEach { code ->
            val kind = RamKind.entries.first { it.code == code }
            val stats = RamResults.statistics(report, code)
            val score = stats?.let { "%.2f %s".format(Locale.US, it.median * if (code == 5) 1 else 1000, if (code == 5) "ns" else "MB/s") } ?: "—"
            appendLine("${kind.title}${if (code == 2) "（读写合计）" else ""}：$score")
            appendLine("${BenchmarkFormat.bytes(config.bytes(code))} · T${config.threads(code)} · ${stats?.count ?: 0} / ${config.rounds(code)} 次")
        }
    }
    if (!report.isNull("error")) appendLine(report.optString("error").take(1000))
    append("记录：${report.optString("run_id").take(36)}")
}
