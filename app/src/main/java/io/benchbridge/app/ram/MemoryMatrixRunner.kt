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
                         private val cancel: (String) -> Unit, private val publish: (Boolean) -> Unit,
                         private val runtime: () -> JSONObject = { JSONObject() },
                         private val samplePinned: (MemoryPlan, Int, Int) -> JSONObject = { plan, warmup, duration ->
                             JSONObject(RamNative.runPinnedRound(handle, plan.kind, plan.workingSets.toLongArray(), plan.cpus.toIntArray(),
                                 warmup, duration, 0xB16B00B5L, plan.stride)) },
                         private val sampleSystem: (MemoryPlan, Int, Int) -> JSONObject = { plan, warmup, duration ->
                             JSONObject(RamNative.runRound(handle, plan.kind, plan.bytes, 1, warmup, duration, 0xB16B00B5L)) },
                         private val sampleCurve: (Int, Long, Int, Long) -> JSONObject = { cpu, bytes, stride, seed ->
                             JSONObject(if(config.singleCurveSample)RamNative.runLatencyPointOnce(handle,cpu,bytes,stride,seed)
                                 else RamNative.runLatencyPoint(handle,cpu,bytes,stride,seed)) }) {
    private val power = context.getSystemService(PowerManager::class.java)
    private var complete = 0
    private var processed = 0
    private var failed = false
    private val cells = JSONArray()
    private val effective = JSONArray()
    private val affinity = AffinityRecovery(topology)

    private fun rejectAffinity(sample: JSONObject, cpus: List<Int>): Boolean = affinity.reject(sample, cpus).also {
        if (it) report.put("affinity_recovery", affinity.toJson())
    }

    private fun shouldStop(): Boolean {
        if (thermal() >= PowerManager.THERMAL_STATUS_SEVERE) cancel("RUN_THERMAL：设备需要降温")
        return cancelled()
    }
    private fun median(values: List<Double>): Double = values.sorted().let { if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2 }
    private fun recordFailure(sample: JSONObject, plan: JSONObject) {
        if (sample.optString("status") != "FAILED") return
        sample.put("runtime_at_failure", runtime())
        report.put("failure_context", JSONObject().put("phase", report.optString("phase"))
            .put("current_round", report.optInt("current_round")).put("calibration_candidate", report.optInt("calibration_candidate"))
            .put("plan", plan).put("sample", sample))
        Log.e("BenchBridge", "RAM_DIAGNOSTIC ${report.optString("run_id")} ${sample.optString("error")}；完整现场见导出 JSON / full evidence in exported JSON")
    }
    private fun run(plan: MemoryPlan, warmup: Int, duration: Int): JSONObject =
        (if (plan.systemScheduled) sampleSystem(plan, warmup, duration) else samplePinned(plan, warmup, duration)).apply {
        put("level", plan.level).put("kind", plan.kind).put("thermal_after", thermal()).put("screen_interactive_after", power.isInteractive)
        put("binding_mode", if (plan.systemScheduled) "system_scheduled" else "pinned_verified")
        if (plan.systemScheduled) put("requested_cpus", JSONArray()).put("core_binding", false)
    }

    private fun calibrate(cell: JSONObject, candidates: List<MemoryPlan>): MemoryPlan? {
        val calibration = cell.optJSONObject("calibration") ?: JSONObject().put("method", "repeated-median-v1")
            .put("trial_ms", config.calibrationMs).put("tie_percent", 3).put("candidates", JSONArray()).also { cell.put("calibration", it) }
        val records = calibration.getJSONArray("candidates")
        val valid = mutableListOf<Pair<MemoryPlan, Double>>()
        val tested = mutableSetOf<List<Int>>()
        candidates.forEachIndexed { index, requested ->
            if (shouldStop()) return null
            report.put("phase", "CALIBRATING").put("calibration_candidate", index + 1).put("calibration_candidates", candidates.size)
            var next = affinity.resolve(requested, config, memoryBudget)
            while (next != null && tested.add(next.cpus)) {
                val plan = next
                val entry = JSONObject().put("plan", plan.toJson()).put("samples", JSONArray())
                if (plan.cpus != requested.cpus) entry.put("requested_plan", requested.toJson())
                val values = mutableListOf<Double>()
                records.put(entry); publish(false)
                var attempts = 0
                var bindingFailed = false
                while (attempts < 2 || attempts == 2 && values.size == 2 && abs(values[0] - values[1]) / values.average() > 0.15) {
                    if (shouldStop()) return null
                    val sample = run(plan, minOf(config.calibrationMs, 80), config.calibrationMs)
                    entry.getJSONArray("samples").put(sample); attempts++
                    if (sample.optString("status") == "COMPLETED" && sample.optBoolean("verified")) values += RamResults.value(sample)
                    else {
                        entry.put("error", sample.optString("error", "CALIBRATION_INCOMPLETE")).put("status", "FAILED")
                        recordFailure(sample, plan.toJson())
                        bindingFailed = rejectAffinity(sample, plan.cpus)
                        if (!bindingFailed && sample.optString("error").contains("VERIFY")) {
                            cell.put("state", "FAILED").put("reason", sample.optString("error"))
                            publish(true); error(sample.optString("error"))
                        }
                        publish(true)
                        break
                    }
                }
                if (!bindingFailed && values.size >= 2) {
                    val score = median(values)
                    val relativeRange = (values.max() - values.min()) / values.average()
                    entry.put("median", score).put("relative_range", relativeRange).put("valid_trials", values.size)
                    // 三次仍明显波动的组合不参与选择。 / Exclude combinations still unstable after three trials.
                    if (relativeRange <= 0.30) { valid += plan to score; entry.put("status", "VALID") }
                    else entry.put("status", "UNSTABLE")
                } else entry.put("status", if (bindingFailed) "AFFINITY_UNAVAILABLE" else "FAILED")
                publish(false)
                next = if (bindingFailed) affinity.resolve(requested, config, memoryBudget) else null
            }
        }
        var selected: MemoryPlan? = null
        var selectedScore = 0.0
        for ((plan, score) in valid.filter { it.first.cpus.none(affinity.rejected::contains) }) {
            val better = if (plan.kind == 5) score < selectedScore * 0.97 else score > selectedScore * 1.03
            val tied = selected != null && abs(score - selectedScore) <= selectedScore * 0.03
            if (selected == null || better || tied && plan.cpus.size < selected.cpus.size) { selected = plan; selectedScore = score }
        }
        selected?.let { cell.getJSONObject("calibration").put("selected_plan", it.toJson()).put("selected_median", selectedScore) }
        return selected
    }

    private fun probe() {
        if (shouldStop()) return
        report.put("phase", "CACHE_PROBING")
        CacheProbe.run(topology, memoryBudget, ::shouldStop,
            sample = { cpu, bytes, stride, seed -> sampleCurve(cpu, bytes, stride, seed).also {
                    recordFailure(it, JSONObject().put("cpu_ids", JSONArray(listOf(cpu))).put("working_set_bytes", bytes).put("node_stride_bytes", stride))
                } },
            progress = { probe ->
                report.put("cache_probe", probe)
                if (affinity.rejected.isNotEmpty()) report.put("affinity_recovery", affinity.toJson())
                publish(probe.optBoolean("checkpoint"))
            }, config = config, previous = report.optJSONObject("cache_probe"), affinity = affinity)
        publish(true)
    }

    fun execute(): String {
        report.put("topology", topology.toJson()).put("cells", cells).put("effective_plan", effective)
            .put("matrix_columns", JSONArray(MemoryPlanner.columns)).put("matrix_levels", JSONArray(config.scoredLevels))
        if(config.curveMode)report.put("stage_order",JSONArray(if(config.curveIncludeRam)listOf("RAM","cache_curve")else listOf("cache_curve")))
        for (level in config.scoredLevels) for (kind in MemoryPlanner.columns.filter(config.kinds::contains)) {
            cells.put(JSONObject().put("level", level).put("kind", kind).put("state", "PENDING"))
        }
        publish(true)
        // 组合测试先保存 RAM 四项，长时间扫描期间即可查看；纯曲线及旧协议保持原执行路径。
        // Persist RAM scores before the long combined sweep; retain curve-only and legacy execution paths.
        val ramFirst = config.curveMode && config.curveIncludeRam
        if (!ramFirst && (config.curveMode || topology.allowedCores.any { cpu -> (1..3).any { topology.cache(cpu.id, it) == null } })) probe()
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
            var restarts = 0
            while (!shouldStop()) {
                report.put("current_round", 0)
                val pinned = calibrate(cell, candidates)
                if (shouldStop()) break
                val bindingUnavailable = affinity.rejected.isNotEmpty() && candidates.all { affinity.resolve(it, config, memoryBudget) == null }
                val plan = pinned ?: if (level == "RAM" && bindingUnavailable) MemoryPlanner.systemPlan(config, kind, memoryBudget) else null
                if (plan == null) {
                    cell.put("state", "FAILED").put("reason", "未取得稳定且绑核有效的校准结果")
                    failed = true; processed += rounds
                    report.put("processed_rounds", processed); publish(true)
                    break
                }
                if (plan.systemScheduled) {
                    cell.put("binding_notice", "无法可靠固定核心，使用系统调度 · T1")
                    val flags = report.optJSONArray("quality_flags") ?: JSONArray().also { report.put("quality_flags", it) }
                    if ((0 until flags.length()).none { flags.optString(it) == "system_scheduling_fallback" }) flags.put("system_scheduling_fallback")
                }
                val warmup = if (level == "RAM") config.warmupMs else minOf(config.warmupMs, 250)
                val duration = if (level == "RAM") config.durationMs else minOf(config.durationMs, 1000)
                val selected = plan.toJson().put("rounds", rounds).put("duration_ms", duration).put("warmup_ms", warmup)
                cell.put("plan", selected).put("state", "RUNNING"); effective.put(selected)
                publish(true)
                var cellCompleted = 0
                var cellProcessed = 0
                var retryBinding = false
                for (round in 1..rounds) {
                    if (shouldStop()) break
                    report.put("phase", "PREPARING").put("current_round", round); publish(false)
                    val before = thermal()
                    val sample = run(plan, warmup, duration).put("round", round).put("thermal_before", before)
                    report.getJSONArray("rounds").put(sample)
                    processed++; cellProcessed++
                    if (sample.optString("status") == "COMPLETED" && sample.optBoolean("verified")) { complete++; cellCompleted++ }
                    else if (!cancelled()) {
                        recordFailure(sample, selected)
                        retryBinding = !plan.systemScheduled && rejectAffinity(sample, plan.cpus)
                        if (!retryBinding) { failed = true; cell.put("reason", sample.optString("error", "ROUND_FAILED")) }
                    }
                    report.put("completed_rounds", complete).put("processed_rounds", processed)
                    cell.put("completed_rounds", cellCompleted); publish(true)
                    Log.i("BenchBridge", "MATRIX_ROUND ${report.getString("run_id")} $level kind=$kind round=$round ${sample.optString("status")}")
                    if (sample.optString("status") != "COMPLETED") break
                    var rest = if (level == "RAM") config.cooldownMs else minOf(config.cooldownMs, 200)
                    report.put("phase", "COOLING"); publish(false)
                    while (rest > 0 && !shouldStop()) { val step = minOf(rest, 50); Thread.sleep(step.toLong()); rest -= step }
                }
                if (retryBinding) {
                    // 不混算不同核心/调度模式；旧轮次留作诊断，新计划重测完整轮次。
                    // Never mix cores or scheduling modes: archive old rounds and restart the full score.
                    val discarded = report.optJSONArray("discarded_rounds") ?: JSONArray().also { report.put("discarded_rounds", it) }
                    val kept = JSONArray(); val all = report.getJSONArray("rounds")
                    for (r in 0 until all.length()) {
                        val sample = all.getJSONObject(r)
                        if (sample.optString("level") == level && sample.optInt("kind") == kind)
                            discarded.put(sample.put("scored", false).put("discard_reason", "affinity_plan_changed"))
                        else kept.put(sample)
                    }
                    report.put("rounds", kept)
                    complete -= cellCompleted; processed -= cellProcessed
                    report.put("completed_rounds", complete).put("processed_rounds", processed)
                    effective.remove(effective.length() - 1); cell.remove("plan")
                    cell.put("completed_rounds", 0).put("affinity_restarts", ++restarts).put("state", "CALIBRATING")
                    publish(true)
                    if (restarts <= topology.allowedCores.size) continue
                    failed = true; cell.put("reason", "核心绑定持续变化，已停止该项")
                }
                cell.put("state", if (!retryBinding && cellCompleted == rounds) "COMPLETED" else if (cancelled()) "CANCELLED" else "FAILED")
                break
            }
            publish(true)
        }
        if (ramFirst) probe()
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
