package io.benchbridge.app.ram

import io.benchbridge.app.hardware.CpuCore
import io.benchbridge.app.hardware.CpuTopology
import org.json.JSONArray
import org.json.JSONObject

data class MemoryPlan(val level: String, val kind: Int, val cpus: List<Int>, val workingSets: List<Long>,
                      val stride: Int, val cacheDomains: List<String>, val sources: List<String>) {
    val bytes: Long get() = workingSets.sum()
    val estimatedBytes: Long get() = bytes + (if (kind == 5) bytes / (stride / 4) else 0) + cpus.size * 2097152L + 32L * 1048576
    fun toJson() = JSONObject().put("level", level).put("kind", kind).put("cpu_ids", JSONArray(cpus))
        .put("threads", cpus.size).put("working_set_bytes", bytes).put("per_thread_working_set_bytes", JSONArray(workingSets))
        .put("node_stride_bytes", if (kind == 5) stride else 0).put("cache_domains", JSONArray(cacheDomains))
        .put("topology_sources", JSONArray(sources)).put("estimated_memory_bytes", estimatedBytes)
}

object MemoryPlanner {
    val levels = listOf("L1", "L2", "L3", "RAM")
    val columns = listOf(0, 1, 5, 2)
    private fun down(value: Long): Long = value / 256 * 256
    private fun up(value: Long): Long = (value + 255) / 256 * 256

    /** 同级缓存按共享域分配，所有工作线程的合计占用不得超过目标域。
     * Allocate by sharing domain; aggregate worker footprints must stay within the target cache. */
    fun plan(topology: CpuTopology, config: RamConfig, level: String, kind: Int, cpus: List<Int>, budget: Long): MemoryPlan? {
        if (cpus.isEmpty() || cpus.size > 16 || cpus.distinct().size != cpus.size || kind == 5 && cpus.size != 1) return null
        if (cpus.any { id -> topology.allowedCores.none { it.id == id } }) return null
        val cacheLevel = levels.indexOf(level) + 1
        if (cacheLevel !in 1..4) return null
        val sizes = mutableMapOf<Int, Long>()
        val domains = mutableListOf<String>()
        val sources = mutableListOf<String>()
        var stride = topology.cache(cpus.first(), 1)?.lineBytes ?: topology.dataLineBytes.takeIf { it in listOf(32,64,128,256) } ?: 64
        if (level == "RAM") {
            val requested = up(config.bytes(kind) / cpus.size)
            cpus.groupBy { topology.largestCache(it)?.id ?: "unknown:$it" }.forEach { (id, members) ->
                val cache = topology.largestCache(members.first())
                // 至少覆盖末级缓存两倍容量；按实际值报告扩大的工作集。
                // Cover at least twice the last-level capacity and report any working-set expansion.
                val perWorker = maxOf(requested, up((cache?.bytes ?: 0) * 2 / members.size))
                members.forEach { cpu ->
                    // 按 256 B 分配余数，保持用户指定的总工作集。 / Distribute aligned remainders without changing the requested total.
                    val units = config.bytes(kind) / 256
                    sizes[cpu] = if(config.expandRamWorkingSet) perWorker
                        else (units / cpus.size + if(cpus.indexOf(cpu) < units % cpus.size) 1 else 0) * 256
                }
                domains += id; sources += cache?.source ?: "last-level-size-unknown"
            }
        } else {
            if (cpus.any { topology.cache(it, cacheLevel) == null }) return null
            cpus.groupBy { topology.cache(it, cacheLevel)!!.id }.forEach { (id, members) ->
                val cache = topology.cache(members.first(), cacheLevel)!!
                val fraction = if (cacheLevel == 1) 0.5 else 0.75
                val perWorker = down((cache.bytes * fraction / members.size).toLong())
                if (perWorker < 1024) return null
                for (cpu in members) {
                    if (cacheLevel > 1) {
                        val lower = topology.cache(cpu, cacheLevel - 1) ?: return null
                        if (perWorker < lower.bytes * 2) return null
                    }
                    sizes[cpu] = perWorker
                }
                domains += id; sources += cache.source
                if (kind == 5) stride = cache.lineBytes
            }
        }
        val plan = MemoryPlan(level, kind, cpus, cpus.map { sizes.getValue(it) }, stride, domains.distinct(), sources.distinct())
        return plan.takeIf { it.bytes <= 2L * 1073741824 && it.estimatedBytes <= budget }
    }

    fun candidates(topology: CpuTopology, config: RamConfig, level: String, kind: Int, budget: Long): List<MemoryPlan> {
        val cores = topology.allowedCores.sortedWith(compareByDescending<CpuCore> { it.capacity }.thenByDescending { it.maxKhz }.thenBy { it.id }).take(16)
        val masks = linkedSetOf<List<Int>>()
        fun add(ids: List<Int>) { if (ids.isNotEmpty()) masks += ids.sorted() }
        if (kind == 5) cores.forEach { add(listOf(it.id)) }
        else {
            val counts = if (config.automaticThreads) (listOf(1, 2, 3, 4, 6, 8, 16, cores.size)).distinct().filter { it in 1..cores.size }
                         else listOf(config.threads).filter { it <= cores.size }
            val groups = cores.groupBy { "${it.part}:${it.capacity}:${it.maxKhz}:${it.frequencyDomain}" }.values
            if (config.automaticThreads) groups.forEach { group -> add(group.map { it.id }); add(listOf(group.first().id)) }
            counts.forEach { count -> add(cores.take(count).map { it.id }) }
            val physical = cores.distinctBy { it.physicalKey }
            counts.filter { it <= physical.size }.forEach { count -> add(physical.take(count).map { it.id }) }
            groups.forEach { group -> counts.filter { it <= group.size }.forEach { count -> add(group.take(count).map { it.id }) } }
            if (config.automaticThreads) cores.forEach { add(listOf(it.id)) }
        }
        return masks.mapNotNull { plan(topology, config, level, kind, it, budget) }.take(24)
    }

    fun unsupportedReason(topology: CpuTopology, config: RamConfig, level: String, kind: Int): String {
        if (topology.allowedCores.isEmpty()) return "无法取得可绑核的 CPU 列表"
        if (level == "L3" && topology.soc?.optString("id") == "sm8975" && topology.caches.none { it.level == 3 })
            return "8EE6 已确认全核心共享 L2；独立 CPU L3 未确认。分块扫描曲线见缓存规格与实测，不能将 L2 或系统缓存填为 L3。"
        if (level != "RAM" && topology.caches.none { it.level == levels.indexOf(level) + 1 }) return "$level 容量或共享关系未确认"
        if (!config.automaticThreads && kind != 5 && config.threads > topology.allowedCores.size) return "所选线程数超过可用 CPU 数"
        return "当前内存预算或缓存容量不足以区分目标层级"
    }
}
