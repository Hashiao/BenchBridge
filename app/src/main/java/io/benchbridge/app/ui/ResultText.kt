package io.benchbridge.app.ui

import io.benchbridge.app.i18n.L10n

import io.benchbridge.app.BenchmarkFormat
import io.benchbridge.app.ram.*
import io.benchbridge.app.storage.*
import io.benchbridge.app.compute.*
import java.util.Locale
import org.json.JSONObject

/** 剪贴板仅包含成绩摘要，避免超过 Binder 容量。 / Copy score summaries to stay within Binder transaction limits. */
internal fun resultText(report: JSONObject): String = buildString {
    val disk = report.optString("kind") == "storage_benchmark"
    val compute=report.optString("kind")=="compute_benchmark"
    appendLine(L10n.display("BenchBridge ${report.optString("app_version").take(80)} · ${if(compute)"GPGPU"else if (disk) "ROM" else "RAM"}"))
    report.optJSONObject("device")?.let {
        appendLine(L10n.display("${it.optString("manufacturer").take(128)} ${it.optString("model").take(256)} · API ${it.optInt("api")}"))
    }
    appendLine(L10n.t("m_26d5509dd6d3", RamResults.stateLabel(report), report.optInt("completed_rounds"), report.optInt("total_rounds")))
    if(compute){
        val config=ComputeConfig.fromJson(report.getJSONObject("config").toString());appendLine(L10n.display(config.summary))
        ComputeKind.entries.filter { it.code in config.kinds }.forEach { kind ->
            appendLine(L10n.display(kind.title))
            listOf("cpu","gpu").filter(config.targets::contains).forEach { target ->
                val score=ComputeResults.median(report,kind.code,target)?.let { "%.2f ${kind.unit}".format(Locale.US,it) }?:"—"
                appendLine(L10n.t("m_b15fe673c089", target.uppercase(), score, ComputeResults.samples(report,kind.code,target).size, config.rounds))
            }
        }
    }else if (disk) {
        val config = StorageConfig.fromJson(report.getJSONObject("config").toString())
        appendLine(L10n.display(config.summary))
        config.cases.forEach { case ->
            appendLine(L10n.display("${case.title} · ${case.subtitle}"))
            for (direction in listOf("read", "write")) {
                val samples = StorageResults.valid(report, case.id, direction)
                val score = StorageResults.best(report, case.id, direction)?.let { "%.2f MB/s".format(Locale.US, StorageResults.mbps(it)) } ?: "—"
                appendLine(L10n.t("m_af813a02175f", if (direction == "read") L10n.t("m_534cb3fa8fbf") else L10n.t("m_5c783c467965"), score, samples.size, config.rounds))
            }
        }
        appendLine(L10n.t("m_2569b76b560b"))
    } else {
        val config = RamConfig.fromJson(report.getJSONObject("config").toString())
        appendLine(L10n.t("m_d9728b3b2178", BenchmarkFormat.duration(config.durationMs), config.statisticLabel))
        if(config.curveMode)report.optJSONObject("cache_probe")?.let { probe->
            appendLine(L10n.t("m_7a0386744906", probe.optString("state")))
            val groups=probe.optJSONArray("groups")
            if(groups!=null)for(i in 0 until groups.length()) {
                val group=groups.getJSONObject(i);val edges=group.optJSONArray("transitions")
                val pointLabel=if(probe.optString("method")==io.benchbridge.app.ram.CacheProbe.FAST_METHOD)L10n.t("m_2e3aaaa98fc3")else L10n.t("m_5189bd4ff280")
                appendLine(L10n.display("CPU ${group.optInt("cpu_id")} · ${group.optInt("stable_points")} / ${group.optJSONArray("points")?.length()?:0} $pointLabel"))
                appendLine(L10n.display(group.optJSONObject("analysis")?.optString("summary")?:L10n.t("m_0f4c06ec156c")))
                if(edges!=null)for(j in 0 until edges.length()) {
                    val edge=edges.getJSONObject(j)
                    appendLine(L10n.t("m_4bc92707958d", BenchmarkFormat.bytes(edge.getLong("lower_bytes")), BenchmarkFormat.bytes(edge.getLong("upper_bytes"))))
                }
            }
        }
        config.scoredLevels.forEach { level ->
        config.kinds.forEach { code ->
            val kind = RamKind.entries.first { it.code == code }
            val stats = RamResults.statistics(report, code, level)
            val score = stats?.let { "%.2f %s".format(Locale.US, it.score * if (code == 5) 1 else 1000, if (code == 5) "ns" else "MB/s") } ?: "—"
            appendLine(L10n.display("$level ${kind.title}${if (code == 2) L10n.t("m_3bce2ed4fd1e") else ""}：$score"))
            val cell = RamResults.cell(report, level, code)
            val plan = cell?.optJSONObject("plan")
            if (plan != null) appendLine(L10n.t("m_fae55aca1c26", BenchmarkFormat.bytes(plan.optLong("working_set_bytes")), plan.optInt("threads"), RamResults.bindingLabel(plan), stats?.count ?: 0, config.rounds(code)))
            else if (!config.cacheMatrix) appendLine(L10n.t("m_1c66ff83323b", BenchmarkFormat.bytes(config.bytes(code)), config.threads(code), stats?.count ?: 0, config.rounds(code)))
            else appendLine(L10n.display(cell?.optString("reason").orEmpty()))
        }
        }
    }
    if (!report.isNull("error")) appendLine(L10n.display(report.optString("error").take(1000)))
    append(L10n.t("m_7a839c76d79a", report.optString("run_id").take(36)))
}
