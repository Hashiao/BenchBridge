package io.benchbridge.app.ram

import android.content.Context
import android.os.PowerManager
import android.util.Log
import io.benchbridge.app.hardware.CpuTopology
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** 校准轮次与正式成绩分开保存。 / Calibration trials are retained separately from scored rounds. */
class MemoryMatrixRunner(private val context: Context, private val config: RamConfig, private val report: JSONObject,
                         private val handle: Long, private val topology: CpuTopology, private val memoryBudget: Long,
                         private val cancelled: () -> Boolean, private val thermal: () -> Int,
                         private val cancel: (String) -> Unit, private val publish: (Boolean) -> Unit) {
    private val power = context.getSystemService(PowerManager::class.java)
    private var complete = 0
    private var processed = 0
    private var failed = false
    private val cells = JSONArray()
    private val effective = JSONArray()

    private fun shouldStop(): Boolean {
        if (thermal() >= PowerManager.THERMAL_STATUS_SEVERE) cancel("RUN_THERMAL：设备需要降温")
        return cancelled()
    }
    private fun median(values: List<Double>): Double = values.sorted().let { if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2 }
    private fun run(plan: MemoryPlan, warmup: Int, duration: Int): JSONObject = JSONObject(RamNative.runPinnedRound(handle, plan.kind,
        plan.workingSets.toLongArray(), plan.cpus.toIntArray(), warmup, duration, 0xB16B00B5L, plan.stride)).apply {
        put("level", plan.level).put("kind", plan.kind).put("thermal_after", thermal()).put("screen_interactive_after", power.isInteractive)
    }

    private fun calibrate(cell: JSONObject, candidates: List<MemoryPlan>): MemoryPlan? {
        val records = JSONArray()
        cell.put("calibration", JSONObject().put("method", "repeated-median-v1").put("trial_ms", config.calibrationMs)
            .put("tie_percent", 3).put("candidates", records))
        var selected: MemoryPlan? = null
        var selectedScore = 0.0
        candidates.forEachIndexed { index, plan ->
            if (shouldStop()) return selected
            report.put("phase", "CALIBRATING").put("calibration_candidate", index + 1).put("calibration_candidates", candidates.size)
            val entry = JSONObject().put("plan", plan.toJson()).put("samples", JSONArray())
            val values = mutableListOf<Double>()
            records.put(entry); publish(false)
            var attempts = 0
            while (attempts < 2 || attempts == 2 && values.size == 2 && abs(values[0] - values[1]) / values.average() > 0.15) {
                if (shouldStop()) return selected
                val sample = run(plan, minOf(config.calibrationMs, 80), config.calibrationMs)
                entry.getJSONArray("samples").put(sample); attempts++
                if (sample.optString("status") == "COMPLETED" && sample.optBoolean("verified")) values += RamResults.value(sample)
                else {
                    entry.put("error", sample.optString("error", "CALIBRATION_INCOMPLETE"))
                    if (sample.optString("error").contains("VERIFY")) error(sample.optString("error"))
                    break
                }
            }
            if (values.size >= 2) {
                val score = median(values)
                val relativeRange = (values.max() - values.min()) / values.average()
                entry.put("median", score).put("relative_range", relativeRange).put("valid_trials", values.size)
                // 三次仍明显波动的组合不参与选择，避免用瞬时尖峰选线程。
                // Exclude combinations still unstable after three trials rather than selecting a transient spike.
                if (relativeRange <= 0.30) {
                    val better = if (plan.kind == 5) score < selectedScore * 0.97 else score > selectedScore * 1.03
                    val tied = selected != null && abs(score - selectedScore) <= selectedScore * 0.03
                    if (selected == null || better || tied && plan.cpus.size < selected!!.cpus.size) {
                        selected = plan; selectedScore = score
                    }
                    entry.put("status", "VALID")
                } else entry.put("status", "UNSTABLE")
            } else entry.put("status", "FAILED")
            publish(false)
        }
        selected?.let { cell.getJSONObject("calibration").put("selected_plan", it.toJson()).put("selected_median", selectedScore) }
        return selected
    }

    fun execute(): String {
        report.put("topology", topology.toJson()).put("cells", cells).put("effective_plan", effective)
            .put("matrix_columns", JSONArray(MemoryPlanner.columns)).put("matrix_levels", JSONArray(config.scoredLevels))
        for (level in config.scoredLevels) for (kind in MemoryPlanner.columns.filter(config.kinds::contains)) {
            cells.put(JSONObject().put("level", level).put("kind", kind).put("state", "PENDING"))
        }
        publish(true)
        if (config.curveMode || topology.allowedCores.any { cpu -> (1..3).any { topology.cache(cpu.id, it) == null } }) {
            report.put("phase", "CACHE_PROBING")
            CacheProbe.run(topology, memoryBudget, ::shouldStop,
                sample = { cpu, bytes, stride, seed ->
                    JSONObject(RamNative.runPinnedRound(handle, 5, longArrayOf(bytes), intArrayOf(cpu), 120, 120, seed, stride, 2))
                }, progress = { probe -> report.put("cache_probe", probe); publish(false) })
            publish(true)
        }
        for (i in 0 until cells.length()) {
            if (shouldStop()) break
            val cell = cells.getJSONObject(i)
            val level = cell.getString("level"); val kind = cell.getInt("kind")
            val rounds = config.rounds(kind)
            report.put("current_level", level).put("current_kind", kind).put("current_round", 0)
            cell.put("state", "CALIBRATING")
            val candidates = MemoryPlanner.candidates(topology, config, level, kind, memoryBudget)
            if (candidates.isEmpty()) {
                cell.put("state", "UNSUPPORTED").put("reason", MemoryPlanner.unsupportedReason(topology, config, level, kind))
                processed += rounds
                report.put("processed_rounds", processed); publish(true)
                continue
            }
            val plan = calibrate(cell, candidates)
            if (shouldStop()) break
            if (plan == null) {
                cell.put("state", "FAILED").put("reason", "未取得稳定且绑核有效的校准结果")
                failed = true; processed += rounds
                report.put("processed_rounds", processed); publish(true)
                continue
            }
            val warmup = if (level == "RAM") config.warmupMs else minOf(config.warmupMs, 250)
            val duration = if (level == "RAM") config.durationMs else minOf(config.durationMs, 1000)
            val selected = plan.toJson().put("rounds", rounds).put("duration_ms", duration).put("warmup_ms", warmup)
            cell.put("plan", selected).put("state", "RUNNING"); effective.put(selected)
            publish(true)
            var cellCompleted = 0
            for (round in 1..rounds) {
                if (shouldStop()) break
                report.put("phase", "PREPARING").put("current_round", round); publish(false)
                val before = thermal()
                val sample = run(plan, warmup, duration).put("round", round).put("thermal_before", before)
                report.getJSONArray("rounds").put(sample)
                processed++
                if (sample.optString("status") == "COMPLETED" && sample.optBoolean("verified")) { complete++; cellCompleted++ }
                else if (!cancelled()) { failed = true; cell.put("reason", sample.optString("error", "ROUND_FAILED")) }
                report.put("completed_rounds", complete).put("processed_rounds", processed)
                cell.put("completed_rounds", cellCompleted); publish(true)
                Log.i("BenchBridge", "MATRIX_ROUND ${report.getString("run_id")} $level kind=$kind round=$round ${sample.optString("status")}")
                if (sample.optString("status") != "COMPLETED") break
                var rest = if (level == "RAM") config.cooldownMs else minOf(config.cooldownMs, 200)
                report.put("phase", "COOLING"); publish(false)
                while (rest > 0 && !shouldStop()) { val step = minOf(rest, 50); Thread.sleep(step.toLong()); rest -= step }
            }
            cell.put("state", if (cellCompleted == rounds) "COMPLETED" else if (cancelled()) "CANCELLED" else "FAILED")
            publish(true)
        }
        return when {
            cancelled() -> "CANCELLED"
            failed -> "FAILED"
            config.curveMode && report.optJSONObject("cache_probe")?.optString("state") != "COMPLETED" -> "PARTIAL"
            complete == config.totalRounds -> "COMPLETED"
            processed == config.totalRounds -> "PARTIAL"
            else -> "INTERRUPTED"
        }
    }
}
