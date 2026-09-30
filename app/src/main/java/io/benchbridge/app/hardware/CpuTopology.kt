package io.benchbridge.app.hardware

import android.content.Context
import android.os.Build
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

data class CpuCore(val id: Int, val physicalKey: String, val capacity: Long, val maxKhz: Long,
                   val part: String, val frequencyDomain: String, val allowed: Boolean) {
    fun toJson() = JSONObject().put("id", id).put("physical_key", physicalKey).put("capacity", capacity)
        .put("max_khz", maxKhz).put("midr_part", part).put("frequency_domain", frequencyDomain).put("allowed", allowed)
}

data class CpuCache(val id: String, val level: Int, val bytes: Long, val lineBytes: Int,
                    val cpus: List<Int>, val source: String, val sourceRef: String = "") {
    fun toJson() = JSONObject().put("id", id).put("level", level).put("bytes", bytes).put("line_bytes", lineBytes)
        .put("cpus", JSONArray(cpus)).put("source", source).put("source_ref", sourceRef)
}

data class CpuTopology(val cores: List<CpuCore>, val caches: List<CpuCache>, val soc: JSONObject?,
                       val catalogRevision: String, val notes: List<String> = emptyList()) {
    val allowedCores: List<CpuCore> get() = cores.filter { it.allowed }
    fun cache(cpu: Int, level: Int): CpuCache? = caches.filter { it.level == level && cpu in it.cpus }.minByOrNull { it.bytes }
    fun largestCache(cpu: Int): CpuCache? = caches.filter { cpu in it.cpus }.maxByOrNull { it.level }
    fun toJson() = JSONObject().put("cores", JSONArray(cores.map { it.toJson() })).put("caches", JSONArray(caches.map { it.toJson() }))
        .put("soc_metadata", soc ?: JSONObject.NULL).put("catalog_revision", catalogRevision).put("notes", JSONArray(notes))

    companion object {
        internal fun cpuList(value: String): List<Int> = runCatching {
            value.trim().split(',').flatMap { item ->
                val range = item.trim().split('-').map { it.toInt() }
                require(range.size in 1..2 && range.all { it in 0..1023 })
                if (range.size == 1) range else { require(range[0] <= range[1]); (range[0]..range[1]).toList() }
            }.distinct().sorted()
        }.getOrDefault(emptyList())
        internal fun cacheBytes(value: String): Long? {
            val match = Regex("(?i)^([0-9]+)([KMG]?)(?:B)?$").matchEntire(value.trim()) ?: return null
            val factor = when (match.groupValues[2].uppercase()) { "K" -> 1024L; "M" -> 1048576L; "G" -> 1073741824L; else -> 1L }
            return match.groupValues[1].toLongOrNull()?.let { runCatching { Math.multiplyExact(it, factor) }.getOrNull() }
                ?.takeIf { it in 1024..1073741824L }
        }
        private fun read(path: String): String = runCatching { File(path).bufferedReader().use { it.readText().take(16384).trim() } }.getOrDefault("")

        fun collect(context: Context, native: JSONObject): CpuTopology {
            val base = "/sys/devices/system/cpu"
            val array = native.optJSONArray("allowed_cpu_ids") ?: JSONArray()
            val allowed = (0 until array.length()).map { array.getInt(it) }.toSet()
            val present = cpuList(read("$base/present")).ifEmpty { allowed.sorted() }
            val cores = present.map { cpu ->
                val root = "$base/cpu$cpu"
                val midr = read("$root/regs/identification/midr_el1").removePrefix("0x").toULongOrNull(16)
                val part = midr?.let { ((it shr 4) and 0xfffuL).toString(16) }.orEmpty()
                val siblings = cpuList(read("$root/topology/thread_siblings_list"))
                CpuCore(cpu, siblings.takeIf { it.isNotEmpty() }?.joinToString(",") ?: "cpu$cpu",
                    read("$root/cpu_capacity").toLongOrNull() ?: 0,
                    read("$root/cpufreq/cpuinfo_max_freq").toLongOrNull() ?: 0, part,
                    cpuList(read("$root/cpufreq/related_cpus").replace(Regex("\\s+"), ",")).joinToString(","), cpu in allowed)
            }
            val caches = mutableListOf<CpuCache>()
            cores.forEach { core ->
                for (index in 0..7) {
                    val root = "$base/cpu${core.id}/cache/index$index"
                    val type = read("$root/type")
                    val level = read("$root/level").toIntOrNull() ?: continue
                    if (level !in 1..3 || type !in listOf("Data", "Unified")) continue
                    val size = cacheBytes(read("$root/size")) ?: continue
                    val shared = cpuList(read("$root/shared_cpu_list"))
                    if (core.id !in shared) continue
                    val line = read("$root/coherency_line_size").toIntOrNull()?.takeIf { it in 32..256 && it and (it - 1) == 0 } ?: continue
                    val id = "L$level:${shared.joinToString(",")}"
                    if (caches.none { it.id == id }) caches += CpuCache(id, level, size, line, shared, "runtime-sysfs", root)
                }
            }
            val catalog = SocCatalog(context)
            val identifiers = listOf(if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else "", Build.HARDWARE,
                read("/sys/devices/soc0/machine"), read("/sys/devices/system/soc/soc0/machine"))
            val soc = catalog.match(identifiers)
            return withCatalog(CpuTopology(cores, caches, soc, catalog.revision))
        }

        /** 只补充缺失字段，并验证变体核心数及核心组匹配。 / Fill gaps only after validating variant count and core-group mapping. */
        internal fun withCatalog(topology: CpuTopology): CpuTopology {
            val soc = topology.soc ?: return topology
            if (soc.optInt("cpu_count") != topology.cores.size)
                return topology.copy(notes = topology.notes + "CATALOG_CORE_COUNT_MISMATCH")
            val caches = topology.caches.toMutableList()
            val groups = soc.optJSONArray("cpu_groups") ?: JSONArray()
            val assigned = mutableSetOf<Int>()
            val sorted = topology.cores.sortedWith(compareByDescending<CpuCore> { it.maxKhz }.thenBy { it.id })
            // 所有核心的 L1D 规格一致时，无需推断具体核心编号。
            // A uniform L1D specification does not require a per-core index mapping.
            val entries = (0 until groups.length()).map { groups.getJSONObject(it) }
            val uniformL1 = entries.map { it.optLong("l1d_bytes") }.distinct().singleOrNull()?.takeIf { it >= 1024 }
            val uniformLine = entries.map { it.optInt("cache_line_bytes") }.distinct().singleOrNull()?.takeIf { it in listOf(32, 64, 128, 256) }
            if (entries.sumOf { it.optInt("count") } == topology.cores.size && uniformL1 != null && uniformLine != null) {
                topology.cores.filter { core -> caches.none { it.level == 1 && core.id in it.cpus } }.forEach { core ->
                    caches += CpuCache("L1:${core.id}", 1, uniformL1, uniformLine, listOf(core.id), "catalog-validated",
                        entries.first().optString("l1_source", entries.first().optString("cache_source")))
                }
            }
            for (i in 0 until groups.length()) {
                val group = groups.getJSONObject(i)
                val part = group.optString("midr_part")
                val count = group.optInt("count")
                var matching = if (part.isNotEmpty()) topology.cores.filter { it.part == part && it.id !in assigned } else emptyList()
                if (matching.size != count && group.optBoolean("frequency_rank_mapping") && sorted.all { it.maxKhz > 0 }) {
                    val remaining = sorted.filter { it.id !in assigned }
                    val candidate = remaining.take(count)
                    val boundary = remaining.getOrNull(count)
                    matching = if (candidate.size == count && candidate.all { part.isEmpty() || it.part.isEmpty() || it.part == part } &&
                        (boundary == null || candidate.last().maxKhz > boundary.maxKhz)) candidate else emptyList()
                }
                if (matching.size != count) continue
                assigned += matching.map { it.id }
                val line = group.optInt("cache_line_bytes", 0)
                if (line !in listOf(32, 64, 128, 256)) continue
                for (level in 1..2) {
                    val size = group.optLong(if (level == 1) "l1d_bytes" else "l2_bytes")
                    if (size !in 1024..1073741824L) continue
                    val domains = if (level == 2 && group.optString("l2_scope") == "cluster") listOf(matching.map { it.id })
                                  else matching.map { listOf(it.id) }
                    domains.forEach { cpus ->
                        if (caches.none { it.level == level && it.cpus.any(cpus::contains) })
                            caches += CpuCache("L$level:${cpus.sorted().joinToString(",")}", level, size, line, cpus.sorted(),
                                "catalog-validated", if (level == 1) group.optString("l1_source", group.optString("cache_source")) else group.optString("cache_source"))
                    }
                }
            }
            val l3 = soc.optJSONObject("cpu_l3")
            if (caches.none { it.level == 3 } && l3 != null && l3.optString("scope") == "soc" && l3.optLong("bytes") > 0) {
                val cpus = topology.cores.map { it.id }.sorted()
                val line = l3.optInt("line_bytes")
                if (line in listOf(32, 64, 128, 256)) caches += CpuCache("L3:${cpus.joinToString(",")}", 3, l3.getLong("bytes"), line, cpus,
                    "catalog-validated", l3.optString("source"))
            }
            return topology.copy(caches = caches)
        }
    }
}
