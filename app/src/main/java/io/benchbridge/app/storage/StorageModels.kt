package io.benchbridge.app.storage

import io.benchbridge.app.BenchmarkFormat
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.sqrt

data class StorageCase(val id: String, val random: Boolean, val blockKiB: Int, val queue: Int, val threads: Int) {
    val modeLabel: String get() = if (random) "RND 随机" else "SEQ 顺序"
    val blockLabel: String get() = "块 ${BenchmarkFormat.kib(blockKiB)}"
    val title: String get() = "${if (random) "RND" else "SEQ"} · $blockLabel"
    val subtitle: String get() = "Q$queue · T$threads"
    fun canonical(): StorageCase {
        val block = if (blockKiB >= 1024 && blockKiB % 1024 == 0) "${blockKiB / 1024}m" else "${blockKiB}k"
        return copy(id = "${if (random) "rnd" else "seq"}$block-q${queue}t$threads")
    }
    fun toJson() = JSONObject().put("id", id).put("random", random).put("block_kib", blockKiB)
        .put("queue", queue).put("threads", threads)
    companion object {
        fun fromJson(o: JSONObject) = StorageCase(o.getString("id"), o.getBoolean("random"),
            o.getInt("block_kib"), o.getInt("queue"), o.getInt("threads"))
        fun standard() = listOf(StorageCase("seq1m-q8t1", false, 1024, 8, 1),
            StorageCase("seq1m-q1t1", false, 1024, 1, 1), StorageCase("rnd4k-q32t1", true, 4, 32, 1),
            StorageCase("rnd4k-q1t1", true, 4, 1, 1))
        fun nvme() = listOf(standard()[0], StorageCase("seq128k-q32t1", false, 128, 32, 1),
            StorageCase("rnd4k-q32t16", true, 4, 32, 16), standard()[3])
    }
}

data class StorageConfig(
    val cases: List<StorageCase> = StorageCase.standard(),
    val directions: List<String> = listOf("read", "write"),
    val fileMiB: Int = 1024,
    val rounds: Int = 5,
    val warmupMs: Int = 5000,
    val durationMs: Int = 5000,
    val intervalMs: Int = 5000,
    val direct: Boolean = true,
    val writeBudgetMiB: Int = 0,
    val presetId: String = "diskmark-default-v1",
) {
    fun sameParameters(other: StorageConfig): Boolean = copy(presetId = "", cases = cases.map { it.copy(id = "") }) ==
        other.copy(presetId = "", cases = other.cases.map { it.copy(id = "") })
    fun recognizedPresetId(): String = when {
        sameParameters(StorageConfig()) -> "diskmark-default-v1"
        sameParameters(nvme()) -> "diskmark-nvme-v1"
        sameParameters(quick()) -> "storage-quick-v1"
        else -> "storage-custom-v1"
    }
    fun normalized(): StorageConfig = copy(presetId = recognizedPresetId())
    val fileLabel: String get() = "测试文件：${BenchmarkFormat.mib(fileMiB)}"
    val timingLabel: String get() = "每项${if (directions.size == 2) "读写各" else if (directions.firstOrNull() == "write") "写入" else "读取"} $rounds 次 · 每次 ${BenchmarkFormat.duration(durationMs)}"
    val directionLabel: String get() = when (directions.toSet()) {
        setOf("read") -> "只读"
        setOf("write") -> "只写"
        else -> "读取 + 写入"
    }
    val summary: String get() = "$fileLabel · $timingLabel\n${if (direct) "Direct" else "Buffered"} · $directionLabel"
    val totalRounds: Int get() = cases.size * directions.size * rounds
    val estimatedMemoryBytes: Long get() = (cases.maxOfOrNull { it.threads.toLong() * it.queue * (it.blockKiB * 1024L + 65536) + it.threads * 2097152L } ?: 0) + 100L * 1048576
    fun validate() {
        require(cases.isNotEmpty() && cases.size <= 8 && cases.map { it.id }.distinct().size == cases.size) { "请选择测试项目" }
        require(directions.isNotEmpty() && directions.distinct().size == directions.size && directions.all { it in listOf("read", "write") }) { "读写方向无效" }
        require(fileMiB in 8..65536 && rounds in 1..10) { "文件大小或重复次数无效" }
        require(durationMs in 50..30000 && warmupMs in 0..10000 && intervalMs in 0..30000) { "测试时间无效" }
        require(writeBudgetMiB == 0 || writeBudgetMiB in fileMiB..1048576) { "累计写入上限必须覆盖文件初始化，或设为不限" }
        require(presetId.length in 1..64) { "预设名称无效" }
        cases.forEach {
            require(it.id.matches(Regex("[a-z0-9-]{1,48}"))) { "项目编号无效" }
            require(it.blockKiB in listOf(4, 8, 16, 32, 64, 128, 256, 512, 1024, 2048, 4096)) { "块大小无效" }
            require(it.queue in 1..64 && it.threads in 1..16 && it.queue * it.threads <= 512) { "队列 × 线程不得超过 512" }
            require(it.blockKiB.toLong() * 1024 * it.queue * it.threads <= 256L * 1048576) { "I/O 缓冲区不得超过 256 MiB" }
            require(fileMiB * 1024L / it.blockKiB >= it.threads * 2L) { "文件太小，无法划分线程区域" }
        }
    }
    fun toJson() = JSONObject().put("cases", JSONArray(cases.map { it.toJson() }))
        .put("directions", JSONArray(directions)).put("file_mib", fileMiB).put("rounds", rounds)
        .put("warmup_ms", warmupMs).put("duration_ms", durationMs).put("interval_ms", intervalMs)
        .put("direct", direct).put("write_budget_mib", writeBudgetMiB).put("preset_id", presetId)
        .put("write_budget_mode", if (writeBudgetMiB == 0) "unlimited" else "limited")
        .put("score", "maximum_completed_round").put("flush_policy", "fdatasync_after_round_separate")
        .put("data_pattern", "splitmix64-64mib-pool-v1").put("seed", 0xB16B00B5L)
    companion object {
        fun quick() = StorageConfig(fileMiB = 64, rounds = 1, warmupMs = 100, durationMs = 600,
            intervalMs = 100, writeBudgetMiB = 0, presetId = "storage-quick-v1")
        fun nvme() = StorageConfig(cases = StorageCase.nvme(), presetId = "diskmark-nvme-v1")
        fun fromJson(text: String): StorageConfig {
            require(text.length <= 16384)
            val o = JSONObject(text)
            return StorageConfig(cases = o.getJSONArray("cases").let { a -> (0 until a.length()).map { StorageCase.fromJson(a.getJSONObject(it)) } },
                directions = o.getJSONArray("directions").let { a -> (0 until a.length()).map { a.getString(it) } },
                fileMiB = o.getInt("file_mib"), rounds = o.getInt("rounds"), warmupMs = o.getInt("warmup_ms"),
                durationMs = o.getInt("duration_ms"), intervalMs = o.getInt("interval_ms"), direct = o.getBoolean("direct"),
                writeBudgetMiB = o.getInt("write_budget_mib"), presetId = o.getString("preset_id")).also { it.validate() }
        }
    }
}

object StorageResults {
    fun samples(report: JSONObject, caseId: String, direction: String): List<JSONObject> {
        val a = report.optJSONArray("rounds") ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it) }.filter {
            it.optString("case_id") == caseId && it.optString("direction") == direction
        }
    }
    fun valid(report: JSONObject, caseId: String, direction: String) = samples(report, caseId, direction).filter {
        it.optString("status") == "COMPLETED" && it.optBoolean("verified") && it.optLong("elapsed_ns") > 0 && it.optLong("operations") > 0
    }
    fun mbps(sample: JSONObject) = sample.getLong("completed_bytes").toDouble() * 1000 / sample.getLong("elapsed_ns")
    fun best(report: JSONObject, caseId: String, direction: String) = valid(report, caseId, direction).maxByOrNull(::mbps)
    fun cv(samples: List<JSONObject>): Double? {
        val values = samples.map(::mbps)
        if (values.size < 2 || values.average() <= 0) return null
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / (values.size - 1)) / mean * 100
    }
}
