package io.benchbridge.app.ram

import io.benchbridge.app.BenchmarkFormat
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.sqrt

enum class RamKind(val code: Int, val title: String, val explanation: String) {
    READ(0, "顺序读取", "顺序读取工作集，统计有效读取量"),
    WRITE(1, "顺序写入", "普通缓存写入，统计有效写入量"),
    COPY(2, "复制", "主结果为读取量 + 写入量；工作集是源与目标之和"),
    RANDOM_READ(3, "随机读取", "8 B 访问，独立批宽 8，预生成无重复随机排列"),
    RANDOM_WRITE(4, "随机写入", "8 B 访问，各线程在独立区域内随机写入"),
    LATENCY(5, "访问延迟", "单线程随机依赖指针链，节点间隔 128 B"),
}

data class RamConfig(
    val kinds: List<Int> = RamKind.entries.map { it.code },
    val workingSetMiB: Int = 64,
    val latencySetMiB: Int = 32,
    val threads: Int = 1,
    val warmupMs: Int = 250,
    val durationMs: Int = 1000,
    val rounds: Int = 1,
    val latencyRounds: Int = 1,
    val cooldownMs: Int = 200,
    val presetId: String = "ram-quick-dev-v1",
    val automaticThreads: Boolean = false,
) {
    val hasBandwidth: Boolean get() = kinds.any { it != RamKind.LATENCY.code }
    val hasLatency: Boolean get() = RamKind.LATENCY.code in kinds
    fun resolveThreads(allowedCpus: Int): RamConfig = if (automaticThreads) copy(threads = allowedCpus.coerceIn(1, 16)) else this
    fun sameParameters(other: RamConfig): Boolean = copy(presetId = "") == other.copy(presetId = "")
    fun recognizedPresetId(): String = when {
        sameParameters(aida64(threads)) -> "aida64-style-v1"
        sameParameters(quick()) -> "ram-quick-dev-v1"
        else -> "ram-custom-v1"
    }
    fun normalized(): RamConfig = copy(presetId = recognizedPresetId())
    val summary: String get() = buildList {
        if (hasBandwidth) add("带宽：${BenchmarkFormat.mib(workingSetMiB)} · $threads 线程 · $rounds 次")
        if (hasLatency) add("延迟：${BenchmarkFormat.mib(latencySetMiB)} · 1 线程 · $latencyRounds 次")
        add("每次 ${BenchmarkFormat.duration(durationMs)} · 预热 ${BenchmarkFormat.duration(warmupMs)} · 间隔 ${BenchmarkFormat.duration(cooldownMs)}")
    }.joinToString("\n")
    val totalRounds: Int get() = kinds.sumOf { if (it == RamKind.LATENCY.code) latencyRounds else rounds }
    fun bytes(kind: Int): Long = (if (kind == RamKind.LATENCY.code) latencySetMiB else workingSetMiB) * 1048576L
    fun threads(kind: Int): Int = if (kind == RamKind.LATENCY.code) 1 else threads
    fun rounds(kind: Int): Int = if (kind == RamKind.LATENCY.code) latencyRounds else rounds
    fun estimatedBytes(): Long = kinds.maxOfOrNull { kind ->
        val data = bytes(kind)
        val indices = when (kind) {
            3, 4 -> data / 2
            5 -> data / 32
            else -> 0
        }
        data + indices + threads(kind) * 2L * 1048576 + 32L * 1048576
    } ?: 0L

    fun validate() {
        require(kinds.isNotEmpty() && kinds.size <= 6 && kinds.distinct().size == kinds.size && kinds.all { it in 0..5 }) { "请选择有效且不重复的测试项目" }
        require(workingSetMiB in 1..2048 && latencySetMiB in 1..1024) { "工作集超出支持范围" }
        require(threads in 1..16) { "线程数必须为 1–16" }
        require(durationMs in 50..30000 && warmupMs in 0..10000) { "测量或预热时长无效" }
        require(rounds in 1..10 && latencyRounds in 1..10) { "重复次数必须为 1–10" }
        require(cooldownMs in 0..30000 && presetId.length in 1..64) { "预设参数无效" }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("kinds", JSONArray(kinds))
        put("working_set_mib", workingSetMiB)
        put("latency_set_mib", latencySetMiB)
        put("threads", threads)
        put("thread_mode", if (automaticThreads) "auto" else "fixed")
        put("warmup_ms", warmupMs)
        put("duration_ms", durationMs)
        put("rounds", rounds)
        put("latency_rounds", latencyRounds)
        put("cooldown_ms", cooldownMs)
        put("preset_id", presetId)
        put("seed", 0xB16B00B5L)
        put("latency_threads", 1)
    }

    companion object {
        fun aida64(allowedCpus: Int = 1) = standard().copy(kinds = listOf(0, 1, 2, 5),
            threads = allowedCpus.coerceIn(1, 16), automaticThreads = true, presetId = "aida64-style-v1")
        fun quick() = RamConfig()
        fun standard() = RamConfig(workingSetMiB = 512, latencySetMiB = 256,
            warmupMs = 1000, durationMs = 3000, rounds = 3, latencyRounds = 5,
            cooldownMs = 2000, presetId = "ram-standard-v1")

        fun fromJson(value: String): RamConfig {
            require(value.length <= 8192) { "配置过长" }
            val json = JSONObject(value)
            if (json.has("thread_mode")) require(json.getString("thread_mode") in setOf("auto", "fixed")) { "线程模式无效" }
            val kinds = json.getJSONArray("kinds")
            return RamConfig(
                kinds = (0 until kinds.length()).map { kinds.getInt(it) },
                workingSetMiB = json.getInt("working_set_mib"),
                latencySetMiB = json.getInt("latency_set_mib"),
                threads = json.getInt("threads"),
                warmupMs = json.getInt("warmup_ms"), durationMs = json.getInt("duration_ms"),
                rounds = json.getInt("rounds"), latencyRounds = json.getInt("latency_rounds"),
                cooldownMs = json.getInt("cooldown_ms"), presetId = json.getString("preset_id"),
                automaticThreads = json.optString("thread_mode", if (json.optString("preset_id") == "aida64-style-v1") "auto" else "fixed") == "auto",
            ).also { it.validate() }
        }
    }
}

data class RamStatistics(val median: Double, val minimum: Double, val maximum: Double, val cvPercent: Double?, val count: Int)

object RamResults {
    val terminalStates = setOf("COMPLETED", "PARTIAL", "CANCELLED", "FAILED", "INTERRUPTED")
    fun validRounds(report: JSONObject, kind: Int): List<JSONObject> {
        val rounds = report.optJSONArray("rounds") ?: return emptyList()
        return (0 until rounds.length()).map { rounds.getJSONObject(it) }.filter {
            it.optInt("kind", -1) == kind && it.optString("status") == "COMPLETED" &&
                it.optBoolean("verified") && it.optLong("elapsed_ns") > 0 && it.optLong("operations") > 0
        }
    }
    fun value(round: JSONObject): Double = if (round.getInt("kind") == 5) {
        round.getLong("elapsed_ns").toDouble() / round.getLong("operations")
    } else {
        // 字节数除以纳秒数，数值上等于十进制 GB/s。
        // Bytes divided by nanoseconds is numerically equal to decimal GB/s.
        round.getLong("logical_bytes").toDouble() / round.getLong("elapsed_ns")
    }
    fun statistics(report: JSONObject, kind: Int): RamStatistics? {
        val values = validRounds(report, kind).map(::value).sorted()
        if (values.isEmpty()) return null
        val median = if (values.size % 2 == 1) values[values.size / 2]
            else (values[values.size / 2 - 1] + values[values.size / 2]) / 2
        val mean = values.average()
        val cv = if (values.size > 1 && mean > 0) sqrt(values.sumOf { (it - mean) * (it - mean) } / (values.size - 1)) / mean * 100 else null
        return RamStatistics(median, values.first(), values.last(), cv, values.size)
    }
    fun stateLabel(state: String): String = when (state) {
        "RUNNING" -> "运行中"
        "COMPLETED" -> "已完成"
        "PARTIAL" -> "部分项目不支持"
        "CANCELLED" -> "已停止"
        "INTERRUPTED" -> "运行中断"
        "FAILED" -> "未完成"
        else -> "准备就绪"
    }
    fun phaseLabel(phase: String): String = when (phase) {
        "PREPARING" -> "分配内存、建立访问序列"
        "WARMING" -> "预热"
        "MEASURING" -> "正式测量"
        "VALIDATING" -> "校验数据"
        "COOLING" -> "轮间休息"
        "PERSISTING" -> "保存结果"
        else -> "准备测试"
    }
}
