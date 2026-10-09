package io.benchbridge.app.storage

import io.benchbridge.app.i18n.L10n

import io.benchbridge.app.BenchmarkFormat
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.sqrt

data class StorageCase(val id: String, val random: Boolean, val blockKiB: Int, val queue: Int, val threads: Int) {
    val modeLabel: String get() = if (random) L10n.t("m_1006a8f10ade") else L10n.t("m_3a78d2b3457d")
    val blockLabel: String get() = L10n.t("m_5d3ddad7866d", BenchmarkFormat.kib(blockKiB))
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
    val rounds: Int = 3,
    val warmupMs: Int = 1000,
    val durationMs: Int = 5000,
    val intervalMs: Int = 1000,
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
    val fileLabel: String get() = L10n.t("m_a192da71860c", BenchmarkFormat.mib(fileMiB))
    val timingLabel: String get() = L10n.t("m_bfcbfa02abb4", if (directions.size == 2) L10n.t("m_7a459cc20275") else if (directions.firstOrNull() == "write") L10n.t("m_5c783c467965") else L10n.t("m_534cb3fa8fbf"), rounds, BenchmarkFormat.duration(durationMs))
    val directionLabel: String get() = when (directions.toSet()) {
        setOf("read") -> L10n.t("m_3b5ec3533b0e")
        setOf("write") -> L10n.t("m_7f4605501fc3")
        else -> L10n.t("m_fae0b6a83e05")
    }
    val summary: String get() = "$fileLabel · $timingLabel\n${if (direct) "Direct" else "Buffered"} · $directionLabel"
    val totalRounds: Int get() = cases.size * directions.size * rounds
    val estimatedMemoryBytes: Long get() = (cases.maxOfOrNull { it.threads.toLong() * it.queue * (it.blockKiB * 1024L + 65536) + it.threads * 2097152L } ?: 0) + 100L * 1048576
    fun validate() {
        require(cases.isNotEmpty() && cases.size <= 8 && cases.map { it.id }.distinct().size == cases.size) { L10n.t("m_862ad650feb8") }
        require(directions.isNotEmpty() && directions.distinct().size == directions.size && directions.all { it in listOf("read", "write") }) { L10n.t("m_4988cc507d93") }
        require(fileMiB in 8..65536 && rounds in 1..10) { L10n.t("m_fdf9f0908c5e") }
        require(durationMs in 50..30000 && warmupMs in 0..10000 && intervalMs in 0..30000) { L10n.t("m_c3688f912b82") }
        require(writeBudgetMiB == 0 || writeBudgetMiB in fileMiB..1048576) { L10n.t("m_ea57239324df") }
        require(presetId.length in 1..64) { L10n.t("m_7ae645b99bf2") }
        cases.forEach {
            require(it.id.matches(Regex("[a-z0-9-]{1,48}"))) { L10n.t("m_82eb5676f95d") }
            require(it.blockKiB in listOf(4, 8, 16, 32, 64, 128, 256, 512, 1024, 2048, 4096)) { L10n.t("m_da0fd4a6150d") }
            require(it.queue in 1..64 && it.threads in 1..16 && it.queue * it.threads <= 512) { L10n.t("m_0df761a0cdbd") }
            require(it.blockKiB.toLong() * 1024 * it.queue * it.threads <= 256L * 1048576) { L10n.t("m_859f47678ef8") }
            require(fileMiB * 1024L / it.blockKiB >= it.threads * 2L) { L10n.t("m_c0c264f77223") }
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
