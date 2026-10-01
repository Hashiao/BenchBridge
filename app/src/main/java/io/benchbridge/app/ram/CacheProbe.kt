package io.benchbridge.app.ram

import io.benchbridge.app.hardware.CpuCore
import io.benchbridge.app.hardware.CpuTopology
import org.json.JSONArray
import org.json.JSONObject

/** 扫描实际拐点，不把 TLB/SLC 或共享缓存猜成某一级 CPU 缓存。
 * Measure transitions without relabeling TLB/SLC effects or shared caches as CPU cache levels. */
object CacheProbe {
    data class Point(val bytes: Long, val latency: Double, val bandwidth: Double, val stable: Boolean) {
        fun toJson() = JSONObject().put("working_set_bytes", bytes).put("latency_ns", latency)
            .put("read_gbps", bandwidth).put("stable", stable)
    }
    data class Edge(val lower: Long, val upper: Long, val latencyRatio: Double, val bandwidthRatio: Double) {
        fun toJson() = JSONObject().put("lower_bytes", lower).put("upper_bytes", upper)
            .put("latency_ratio", latencyRatio).put("bandwidth_ratio", bandwidthRatio)
            .put("confidence", if (bandwidthRatio < 0.92) "latency-and-bandwidth" else "latency-only")
            .put("cache_level", JSONObject.NULL)
    }

    internal fun representatives(topology: CpuTopology): List<CpuCore> = topology.allowedCores
        // 没有分组证据时逐核扫描，不能把所有未知核心并为同一种核心。
        // With no grouping evidence, probe each core instead of merging unknown cores.
        .groupBy { if (it.maxKhz == 0L && it.capacity == 0L && it.part.isEmpty() && it.frequencyDomain.isEmpty()) "cpu${it.id}"
            else "${it.part}:${it.capacity}:${it.maxKhz}:${it.frequencyDomain}" }
        .values.map { it.minBy(CpuCore::id) }.sortedByDescending(CpuCore::maxKhz).take(16)

    internal fun sizes(limit: Long, anchors: List<Long>): List<Long> {
        val sizes = sortedSetOf<Long>()
        var bytes = 4096L
        while (bytes <= limit) { sizes += bytes; sizes += bytes * 3 / 2; bytes *= 2 }
        anchors.forEach { size -> listOf(0.75, 1.0, 1.25, 1.5).forEach { sizes += (size * it).toLong() / 256 * 256 } }
        return sizes.filter { it in 4096..limit }
    }

    internal fun edges(points: List<Point>): List<Edge> {
        val sorted = points.sortedBy(Point::bytes)
        return (2 until sorted.size - 1).mapNotNull { i ->
            val a = sorted[i - 2]; val b = sorted[i - 1]; val c = sorted[i]; val d = sorted[i + 1]
            // 两个稳定平台与后续点交叉验证；孤立尖峰和扫描空洞不构成容量依据。
            // Require a stable preceding plateau and a persistent rise; reject spikes and gaps.
            if (listOf(a,b,c,d).any { !it.stable } || c.bytes > b.bytes * 2 || d.bytes > c.bytes * 2 ||
                a.latency <= 0 || b.latency <= 0 || c.latency <= 0 || b.bandwidth <= 0) return@mapNotNull null
            val base = (a.latency + b.latency) / 2
            val ratio = c.latency / base
            if (b.latency / a.latency !in 0.85..1.15 || ratio < 1.25 || d.latency < base * 1.20) null
            else Edge(b.bytes, c.bytes, ratio, c.bandwidth / b.bandwidth)
        }
    }

    fun run(topology: CpuTopology, memoryBudget: Long, shouldStop: () -> Boolean,
            sample: (cpu: Int, bytes: Long, kind: Int, stride: Int, seed: Long) -> JSONObject,
            progress: (JSONObject) -> Unit): JSONObject {
        val groups = JSONArray()
        val report = JSONObject().put("method", "pinned-working-set-sweep-v1").put("state", "RUNNING")
            .put("groups", groups).put("scored", false).put("trial_ms", 50).put("repeats", 2)
            .put("interpretation", "Effective transition ranges only; TLB, prefetch, DVFS and SLC can also cause transitions.")
        val limit = minOf(64L * 1048576, ((memoryBudget - 34L * 1048576).coerceAtLeast(0) * 8 / 9) / 256 * 256)
        report.put("maximum_working_set_bytes", limit)
        progress(report)
        for (core in representatives(topology)) {
            if (shouldStop()) break
            val stride = topology.cache(core.id, 1)?.lineBytes ?: topology.dataLineBytes.takeIf { it in listOf(32,64,128,256) } ?: 64
            val records = JSONArray()
            val group = JSONObject().put("cpu_id", core.id).put("max_khz", core.maxKhz).put("node_stride_bytes", stride)
                .put("stride_source", topology.cache(core.id, 1)?.lineSource ?: if (topology.dataLineBytes > 0) topology.dataLineSource else "probe-parameter-not-hardware-spec")
                .put("samples", records).put("points", JSONArray()).put("transitions", JSONArray())
            groups.put(group)
            val points = mutableListOf<Point>()
            var incomplete = false
            fun measure(bytes: Long) {
                if (shouldStop()) return
                val values = mutableMapOf<Int, List<Double>>()
                for (kind in listOf(5,0)) {
                    val trials = mutableListOf<Double>()
                    repeat(2) { repeat ->
                        if (shouldStop()) return
                        val result = sample(core.id, bytes, kind, stride, 0xB16B00B5L + repeat)
                        records.put(JSONObject().put("working_set_bytes", bytes).put("kind", kind).put("repeat", repeat + 1).put("result", result))
                        if (result.optString("status") == "COMPLETED" && result.optBoolean("verified")) {
                            val value = RamResults.value(result)
                            if (value.isFinite() && value > 0) trials += value
                        }
                    }
                    values[kind] = trials
                }
                val latency = values.getValue(5); val bandwidth = values.getValue(0)
                if (latency.size == 2 && bandwidth.size == 2) {
                    val stable = listOf(latency,bandwidth).all { (it.max() - it.min()) / it.average() <= 0.15 }
                    points += Point(bytes, latency.average(), bandwidth.average(), stable)
                } else incomplete = true
                group.put("points", JSONArray(points.sortedBy(Point::bytes).map(Point::toJson)))
                progress(report)
            }
            sizes(limit, topology.caches.filter { core.id in it.cpus }.map { it.bytes }).forEach { if (!shouldStop()) measure(it) }
            // 在初次突变区间内继续细分，不声称得到精确物理容量。
            // Refine initial transition intervals without claiming an exact physical capacity.
            val initial = edges(points)
            val refined = initial.flatMap { edge -> (1..3).map { (edge.lower + (edge.upper-edge.lower) * it / 4) / 256 * 256 } }
                .distinct().filter { size -> points.none { it.bytes == size } && size in 4096..limit }
            refined.forEach { if (!shouldStop()) measure(it) }
            group.put("coarse_transitions", JSONArray(initial.map(Edge::toJson)))
            val narrowed = initial.map { edge ->
                val base = points.first { it.bytes == edge.lower }.latency
                val inside = points.filter { it.stable && it.bytes in edge.lower..edge.upper }.sortedBy(Point::bytes)
                val high = inside.firstOrNull { it.latency >= base * 1.25 }
                val low = high?.let { h -> inside.lastOrNull { it.bytes < h.bytes && it.latency <= base * 1.15 } }
                if (high != null && low != null) Edge(low.bytes, high.bytes, high.latency / low.latency, high.bandwidth / low.bandwidth) else edge
            }
            group.put("transitions", JSONArray(narrowed.map(Edge::toJson)))
            group.put("state", if (shouldStop()) "CANCELLED" else if (points.isEmpty()) "UNAVAILABLE" else if (incomplete) "PARTIAL" else "COMPLETED")
            progress(report)
        }
        report.put("state", when {
            shouldStop() -> "CANCELLED"
            limit < 4096 -> "BUDGET_UNAVAILABLE"
            groups.length() == 0 -> "UNAVAILABLE"
            (0 until groups.length()).any { groups.getJSONObject(it).optString("state") != "COMPLETED" } -> "PARTIAL"
            else -> "COMPLETED"
        })
        progress(report)
        return report
    }
}
