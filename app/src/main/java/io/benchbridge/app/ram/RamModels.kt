package io.benchbridge.app.ram

import io.benchbridge.app.i18n.L10n

import io.benchbridge.app.BenchmarkFormat
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.sqrt

enum class RamKind(val code: Int, private val titleKey: String, private val explanationKey: String) {
    READ(0, "m_6f02986c45d1", "m_64a955b05a41"),
    WRITE(1, "m_5aa5c7851e03", "m_01087e7dcd6b"),
    COPY(2, "m_63d90d977348", "m_e396f936a371"),
    RANDOM_READ(3, "m_ec2501a54738", "m_248dcecf45a2"),
    RANDOM_WRITE(4, "m_e22847e1d869", "m_e36320851da4"),
    LATENCY(5, "m_eb4fd500d4c1", "m_76169d89a3e0");
    val title: String get() = L10n.t(titleKey)
    val explanation: String get() = L10n.t(explanationKey)
}

data class RamConfig(
    val kinds: List<Int> = RamKind.entries.map { it.code },
    val workingSetMiB: Int = 64,
    val latencySetMiB: Int = 64,
    val threads: Int = 1,
    val warmupMs: Int = 250,
    val durationMs: Int = 1000,
    val rounds: Int = 1,
    val latencyRounds: Int = 1,
    val cooldownMs: Int = 200,
    val presetId: String = "ram-quick-dev-v1",
    val automaticThreads: Boolean = false,
    val cacheMatrix: Boolean = false,
    val calibrationMs: Int = 150,
    val cacheCurve: Boolean = false,
    val curveIncludeRam: Boolean = true,
    val curveMaxMiB: Int = 64,
    val curveSteps: Int = 8,
    val curveProtocol: String = CacheProbe.FAST_METHOD,
    val expandRamWorkingSet: Boolean = false,
    val aggregation: String = "median",
) {
    val singleCurveSample: Boolean get() = curveProtocol == CacheProbe.FAST_METHOD
    val statisticLabel: String get() = if(aggregation=="arithmetic_mean")L10n.t("m_db7538b96322")else L10n.t("m_1961784db559")
    val curveMode: Boolean get() = cacheMatrix && cacheCurve
    val scoredLevels: List<String> get() = if(curveMode&&!curveIncludeRam)emptyList()else if (cacheMatrix && !curveMode) MemoryPlanner.levels else listOf("RAM")
    val hasBandwidth: Boolean get() = kinds.any { it != RamKind.LATENCY.code }
    val hasLatency: Boolean get() = RamKind.LATENCY.code in kinds
    fun resolveThreads(allowedCpus: Int): RamConfig = if (automaticThreads) copy(threads = allowedCpus.coerceIn(1, 16)) else this
    fun sameParameters(other: RamConfig): Boolean = copy(presetId = "") == other.copy(presetId = "")
    fun recognizedPresetId(): String = when {
        sameParameters(matrixQuick().copy(threads = threads)) -> "cache-curve-quick-v1"
        sameParameters(aida64()) -> "cache-curve-standard-v3"
        sameParameters(quick()) -> "ram-quick-dev-v1"
        else -> "ram-custom-v1"
    }
    fun normalized(): RamConfig = copy(presetId = recognizedPresetId())
    val summary: String get() = buildList {
        if (curveMode) add(L10n.t("m_dee4b55ef0a0"))
        else if (cacheMatrix) add("L1 / L2 / L3 / RAM · ${if (automaticThreads) L10n.t("m_561629ae6062") else L10n.t("m_e72002112185", threads)}")
        if (hasBandwidth && scoredLevels.isNotEmpty()) add(L10n.t("m_2a24eb9a1db2", if (cacheMatrix) "RAM " else "", BenchmarkFormat.mib(workingSetMiB), if (automaticThreads && cacheMatrix) L10n.t("m_b1977ee2eafd") else L10n.t("m_a5c3ffddd0b1", threads), rounds))
        if (hasLatency && scoredLevels.isNotEmpty()) add(L10n.t("m_78cccc46cde4", if (cacheMatrix) "RAM " else "", BenchmarkFormat.mib(latencySetMiB), latencyRounds))
        if(scoredLevels.isNotEmpty())add(L10n.t("m_86b57a9ae02e", if (cacheMatrix) "RAM " else "", BenchmarkFormat.duration(durationMs), BenchmarkFormat.duration(warmupMs), BenchmarkFormat.duration(cooldownMs)))
        if(scoredLevels.isNotEmpty())add(L10n.t("m_f137930818cc", statisticLabel))
        if (curveMode) add(if(singleCurveSample)L10n.t("m_c8ce0da65773", curveMaxMiB, curveSteps)
            else if(curveProtocol==CacheProbe.METHOD)L10n.t("m_9a4ba92f53f0", curveMaxMiB, curveSteps)
            else L10n.t("m_79b5c477dcd6", curveProtocol))
        else if (cacheMatrix) add(L10n.t("m_c7bc208b1753", BenchmarkFormat.duration(minOf(durationMs, 1000))))
        if(curveMode && curveIncludeRam)add(if(expandRamWorkingSet) L10n.t("m_fe9b4f513410") else L10n.t("m_7d50674e1361"))
    }.joinToString("\n")
    val totalRounds: Int get() = kinds.sumOf { if (it == RamKind.LATENCY.code) latencyRounds else rounds } * scoredLevels.size
    fun bytes(kind: Int): Long = (if (kind == RamKind.LATENCY.code) latencySetMiB else workingSetMiB) * 1048576L
    fun threads(kind: Int): Int = if (kind == RamKind.LATENCY.code) 1 else threads
    fun rounds(kind: Int): Int = if (kind == RamKind.LATENCY.code) latencyRounds else rounds
    fun estimatedBytes(): Long = if(curveMode&&!curveIncludeRam) 40L*1048576 else kinds.maxOfOrNull { kind ->
        val data = bytes(kind)
        val indices = when (kind) {
            3, 4 -> data / 2
            5 -> data / 32
            else -> 0
        }
        data + indices + threads(kind) * 2L * 1048576 + 32L * 1048576
    } ?: 0L

    fun validate() {
        require(kinds.isNotEmpty() && kinds.size <= 6 && kinds.distinct().size == kinds.size && kinds.all { it in 0..5 }) { L10n.t("m_0b5d72c4e222") }
        require(workingSetMiB in 1..2048 && latencySetMiB in 1..1024) { L10n.t("m_42b9aee38716") }
        require(threads in 1..16) { L10n.t("m_a17ffdc11cc5") }
        require(durationMs in 50..30000 && warmupMs in 0..10000) { L10n.t("m_c09e1fe8a6c2") }
        require(rounds in 1..10 && latencyRounds in 1..10) { L10n.t("m_11ab3536bf93") }
        require(cooldownMs in 0..30000 && presetId.length in 1..64) { L10n.t("m_f8abacf7c330") }
        require(calibrationMs in 50..1000) { L10n.t("m_bba8fe6846f0") }
        require(curveMaxMiB in 1..256 && curveSteps in 2..8) { L10n.t("m_751ef32692cb") }
        require(!cacheMatrix || kinds.all { it in listOf(0, 1, 2, 5) }) { L10n.t("m_2d998256d0b6") }
        require(aggregation in setOf("median","arithmetic_mean")) { L10n.t("m_9f7da13d0f16") }
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
        put("cache_matrix", cacheMatrix)
        put("cache_curve", curveMode)
        put("curve_include_ram",curveIncludeRam).put("curve_max_mib",curveMaxMiB).put("curve_steps",curveSteps)
        if (curveMode) put("cache_probe_method", curveProtocol).put("curve_include_ram",curveIncludeRam)
            .put("curve_max_mib",curveMaxMiB).put("curve_steps",curveSteps)
        put("calibration_ms", calibrationMs)
        put("expand_ram_working_set", expandRamWorkingSet)
        put("aggregation",aggregation)
        if (cacheMatrix) put("cache_duration_ms", minOf(durationMs, 1000)).put("cache_warmup_ms", minOf(warmupMs, 250))
    }

    companion object {
        fun aida64() = standard().copy(kinds = listOf(0, 1, 2, 5),
            threads = 1, automaticThreads = false, cacheMatrix = true, cacheCurve = true, curveIncludeRam=true, presetId = "cache-curve-standard-v3")
        fun matrixQuick() = RamConfig(kinds = listOf(0, 1, 2, 5), workingSetMiB = 16, latencySetMiB = 8,
            warmupMs = 25, durationMs = 150, rounds = 1, latencyRounds = 1, cooldownMs = 0,
            automaticThreads = true, cacheMatrix = true, cacheCurve = true, curveIncludeRam=true, curveSteps=4, curveMaxMiB=64, calibrationMs = 50, presetId = "cache-curve-quick-v1", curveProtocol=CacheProbe.METHOD)
        fun quick() = RamConfig()
        fun standard() = RamConfig(workingSetMiB = 64, latencySetMiB = 64,
            warmupMs = 1000, durationMs = 3000, rounds = 3, latencyRounds = 3,
            cooldownMs = 2000, presetId = "ram-standard-v3", aggregation="arithmetic_mean")

        fun fromJson(value: String): RamConfig {
            require(value.length <= 8192) { L10n.t("m_6eaad4ac52ca") }
            val json = JSONObject(value)
            if (json.has("thread_mode")) require(json.getString("thread_mode") in setOf("auto", "fixed")) { L10n.t("m_a32d83e84245") }
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
                cacheMatrix = json.optBoolean("cache_matrix", false), calibrationMs = json.optInt("calibration_ms", 150),
                cacheCurve = json.optBoolean("cache_curve", false),
                curveIncludeRam=json.optBoolean("curve_include_ram",true),curveMaxMiB=json.optInt("curve_max_mib",64),curveSteps=json.optInt("curve_steps",4),
                curveProtocol=json.optString("cache_probe_method",if(json.optBoolean("cache_curve"))"legacy-curve"else CacheProbe.METHOD),
                expandRamWorkingSet=json.optBoolean("expand_ram_working_set",true),
                aggregation=json.optString("aggregation","median"),
            ).also { it.validate() }
        }
    }
}

data class RamStatistics(val median: Double, val minimum: Double, val maximum: Double, val cvPercent: Double?, val count: Int,
                         val mean: Double, val score: Double)

object RamResults {
    fun bindingLabel(plan: JSONObject): String = if(plan.optString("binding_mode")=="system_scheduled")L10n.t("m_7236eb1304dd")else"CPU ${plan.optJSONArray("cpu_ids")}"
    fun statisticLabel(report: JSONObject): String = if(report.optJSONObject("config")?.optString("aggregation")=="arithmetic_mean")L10n.t("m_db7538b96322")else L10n.t("m_1961784db559")
    val terminalStates = setOf("COMPLETED", "PARTIAL", "CANCELLED", "FAILED", "INTERRUPTED")
    fun cell(report: JSONObject, level: String, kind: Int): JSONObject? {
        val cells = report.optJSONArray("cells") ?: return null
        return (0 until cells.length()).map { cells.getJSONObject(it) }.firstOrNull { it.optString("level") == level && it.optInt("kind", -1) == kind }
    }
    fun validRounds(report: JSONObject, kind: Int, level: String = "RAM"): List<JSONObject> {
        val rounds = report.optJSONArray("rounds") ?: return emptyList()
        return (0 until rounds.length()).map { rounds.getJSONObject(it) }.filter {
            it.optInt("kind", -1) == kind && it.optString("level", "RAM") == level && it.optString("status") == "COMPLETED" &&
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
    fun statistics(report: JSONObject, kind: Int, level: String = "RAM"): RamStatistics? {
        val values = validRounds(report, kind, level).map(::value).sorted()
        if (values.isEmpty()) return null
        val median = if (values.size % 2 == 1) values[values.size / 2]
            else (values[values.size / 2 - 1] + values[values.size / 2]) / 2
        val mean = values.average()
        val cv = if (values.size > 1 && mean > 0) sqrt(values.sumOf { (it - mean) * (it - mean) } / (values.size - 1)) / mean * 100 else null
        val score=if(report.optJSONObject("config")?.optString("aggregation")=="arithmetic_mean")mean else median
        return RamStatistics(median, values.first(), values.last(), cv, values.size, mean, score)
    }
    fun stateLabel(state: String): String = when (state) {
        "RUNNING" -> L10n.t("m_1f0eb99b7ed0")
        "COMPLETED" -> L10n.t("m_f28461bb49c8")
        "PARTIAL" -> L10n.t("m_2dd049355cad")
        "CANCELLED" -> L10n.t("m_f006455e3baf")
        "INTERRUPTED" -> L10n.t("m_3d645b00b875")
        "FAILED" -> L10n.t("m_6707de42c29d")
        else -> L10n.t("m_1bd1893c0900")
    }
    fun stateLabel(report: JSONObject): String = if(report.optString("state")=="PARTIAL" && report.optJSONObject("config")?.optBoolean("cache_curve")==true)
        if(report.optJSONObject("cache_probe")?.optJSONArray("groups")?.let { groups -> (0 until groups.length()).any {
            groups.getJSONObject(it).optString("state")=="AFFINITY_UNAVAILABLE" } }==true)L10n.t("m_d3174a42c726")
        else if(report.optJSONObject("cache_probe")?.optString("state")!="COMPLETED")L10n.t("m_10abac98de60")else L10n.t("m_6954ee060e8a")
        else stateLabel(report.optString("state"))
    fun phaseLabel(phase: String): String = when (phase) {
        "PREPARING" -> L10n.t("m_d6404cfc96c6")
        "WARMING" -> L10n.t("m_2051831c1a77")
        "MEASURING" -> L10n.t("m_7a34eafff5b2")
        "VALIDATING" -> L10n.t("m_2c72c666472c")
        "COOLING" -> L10n.t("m_69f82395883c")
        "PERSISTING" -> L10n.t("m_8783d9db6edb")
        "CALIBRATING" -> L10n.t("m_b4c37dc2c23a")
        "CACHE_PROBING" -> L10n.t("m_6820bf00e022")
        else -> L10n.t("m_e304fbe1cea4")
    }
}
