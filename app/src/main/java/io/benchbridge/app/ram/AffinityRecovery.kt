package io.benchbridge.app.ram

import io.benchbridge.app.hardware.CpuCore
import io.benchbridge.app.hardware.CpuTopology
import org.json.JSONArray
import org.json.JSONObject

/** 仅在真实绑定失败后排除核心；正常设备不增加探测或更改计划。
 * Exclude CPUs only after affinity failures; leave healthy plans and sampling unchanged. */
class AffinityRecovery(private val topology: CpuTopology) {
    val rejected = linkedSetOf<Int>()
    fun members(cpu: Int): List<CpuCore> {
        val core = topology.allowedCores.firstOrNull { it.id == cpu } ?: return emptyList()
        return topology.allowedCores.filter { groupKey(it) == groupKey(core) }.sortedBy { it.id }
    }
    fun replacement(cpu: Int, reserved: Set<Int> = emptySet()): Int? =
        (listOf(cpu) + members(cpu).map { it.id }).distinct().firstOrNull { it !in rejected && it !in reserved }

    fun resolve(plan: MemoryPlan, config: RamConfig, budget: Long): MemoryPlan? {
        if (plan.systemScheduled || plan.cpus.none { it in rejected }) return plan
        val used = plan.cpus.filter { it !in rejected }.toMutableSet()
        val ids = plan.cpus.map { cpu ->
            if (cpu !in rejected) cpu else replacement(cpu, used)?.also { used += it } ?: return null
        }
        return MemoryPlanner.plan(topology, config, plan.level, plan.kind, ids.sorted(), budget)
    }

    fun reject(sample: JSONObject, requested: List<Int>): Boolean {
        if (!isAffinityFailure(sample)) return false
        val workers = sample.optJSONObject("affinity_diagnostics")?.optJSONArray("workers")
        val identified = if (workers == null) emptyList() else (0 until workers.length()).map { workers.getJSONObject(it) }
            .filter { !it.isNull("failure_reason") && it.optString("failure_reason").isNotEmpty() }
            .map { it.optInt("requested_cpu", -1) }.filter { it in requested }
        rejected += identified.ifEmpty { requested }
        return true
    }
    fun toJson() = JSONObject().put("method", "verified-same-group-recovery-v1").put("rejected_cpu_ids", JSONArray(rejected.toList()))

    companion object {
        fun groupKey(core: CpuCore): String = if (core.maxKhz == 0L && core.capacity == 0L && core.part.isEmpty() && core.frequencyDomain.isEmpty())
            "cpu${core.id}" else "${core.part}:${core.capacity}:${core.maxKhz}:${core.frequencyDomain}"
        fun isAffinityFailure(sample: JSONObject): Boolean {
            if (sample.optString("status") != "FAILED") return false
            val error = sample.optString("error")
            if (error in setOf("AFFINITY_QUERY_FAILED", "AFFINITY_CPU_NOT_ALLOWED", "AFFINITY_VERIFY_FAILED", "AFFINITY_CPU_MIGRATED",
                    "PROBE_CPU_NOT_ALLOWED", "PROBE_AFFINITY_FAILED") || error.startsWith("AFFINITY_SET_FAILED:")) return true
            val workers = sample.optJSONObject("affinity_diagnostics")?.optJSONArray("workers") ?: return false
            return error == "PROBE_VERIFY_FAILED" && (0 until workers.length()).any {
                workers.getJSONObject(it).optString("failure_reason") == "observed_cpu_migrated"
            }
        }
    }
}
