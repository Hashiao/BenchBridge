package io.benchbridge.app.ui

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
    appendLine("BenchBridge ${report.optString("app_version").take(80)} · ${if(compute)"GPGPU"else if (disk) "ROM" else "RAM"}")
    report.optJSONObject("device")?.let {
        appendLine("${it.optString("manufacturer").take(128)} ${it.optString("model").take(256)} · API ${it.optInt("api")}")
    }
    appendLine("${RamResults.stateLabel(report.optString("state"))} · ${report.optInt("completed_rounds")} / ${report.optInt("total_rounds")} 轮")
    if(compute){
        val config=ComputeConfig.fromJson(report.getJSONObject("config").toString());appendLine(config.summary)
        ComputeKind.entries.filter { it.code in config.kinds }.forEach { kind ->
            appendLine(kind.title)
            listOf("cpu","gpu").filter(config.targets::contains).forEach { target ->
                val score=ComputeResults.median(report,kind.code,target)?.let { "%.2f ${kind.unit}".format(Locale.US,it) }?:"—"
                appendLine("${target.uppercase()}：$score · ${ComputeResults.samples(report,kind.code,target).size}/${config.rounds}次")
            }
        }
    }else if (disk) {
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
        if(config.curveMode)report.optJSONObject("cache_probe")?.let { probe->
            appendLine("缓存曲线 · 工作集大小 / 延迟 ns · ${probe.optString("state")}")
            val groups=probe.optJSONArray("groups")
            if(groups!=null)for(i in 0 until groups.length()) {
                val group=groups.getJSONObject(i);val edges=group.optJSONArray("transitions")
                appendLine("CPU ${group.optInt("cpu_id")} · ${group.optInt("stable_points")} / ${group.optJSONArray("points")?.length()?:0} 稳定点")
                if(edges!=null)for(j in 0 until edges.length()) {
                    val edge=edges.getJSONObject(j)
                    appendLine("阶跃候选：${BenchmarkFormat.bytes(edge.getLong("lower_bytes"))}–${BenchmarkFormat.bytes(edge.getLong("upper_bytes"))}")
                }
            }
        }
        config.scoredLevels.forEach { level ->
        config.kinds.forEach { code ->
            val kind = RamKind.entries.first { it.code == code }
            val stats = RamResults.statistics(report, code, level)
            val score = stats?.let { "%.2f %s".format(Locale.US, it.median * if (code == 5) 1 else 1000, if (code == 5) "ns" else "MB/s") } ?: "—"
            appendLine("$level ${kind.title}${if (code == 2) "（读写合计）" else ""}：$score")
            val cell = RamResults.cell(report, level, code)
            val plan = cell?.optJSONObject("plan")
            if (plan != null) appendLine("${BenchmarkFormat.bytes(plan.optLong("working_set_bytes"))} · T${plan.optInt("threads")} · CPU${plan.optJSONArray("cpu_ids")} · ${stats?.count ?: 0}/${config.rounds(code)}次")
            else if (!config.cacheMatrix) appendLine("${BenchmarkFormat.bytes(config.bytes(code))} · T${config.threads(code)} · ${stats?.count ?: 0} / ${config.rounds(code)} 次")
            else appendLine(cell?.optString("reason").orEmpty())
        }
        }
    }
    if (!report.isNull("error")) appendLine(report.optString("error").take(1000))
    append("记录：${report.optString("run_id").take(36)}")
}
