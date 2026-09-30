package io.benchbridge.app.storage

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.StatFs
import android.util.Log
import io.benchbridge.app.BuildConfig
import io.benchbridge.app.BenchmarkFormat
import io.benchbridge.app.ram.RunStore
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

class StorageCoordinator(private val context: Context, private val executor: ExecutorService, private val thermal: () -> Int,
    private val onRunStarting: () -> Unit, private val onRunFinished: () -> Unit) {
    private val store = RunStore(context, "storage_results")
    private val owned = OwnedStorage(context)
    @Volatile private var cachedCapabilities: String? = null
    @Volatile private var current: Run? = null
    private class Run(val id: String, val config: StorageConfig, val report: JSONObject) {
        val cancelled = AtomicBoolean(false)
        @Volatile var reason = "RUN_CANCELLED"
        @Volatile var handle = 0L
        @Volatile var finished = false
        @Volatile var snapshot = report.toString()
    }
    val activeId: String? get() = current?.takeUnless { it.finished }?.id
    init {
        store.recoverInterrupted()
        owned.recover().forEach { (id, cleanup) ->
            store.read(id)?.let { report -> report.put("cleanup", cleanup); store.save(report) }
        }
    }
    fun capabilities(): JSONObject {
        val fs = StatFs(owned.root.absolutePath)
        val memory = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
        return JSONObject(cachedCapabilities ?: "{}").put("free_bytes", fs.availableBytes).put("total_bytes", fs.totalBytes)
            .put("reserve_bytes", (fs.availableBytes / 20).coerceIn(256L * 1048576, 2L * 1073741824))
            .put("memory_budget_bytes", minOf(memory.availMem / 3, (memory.availMem - maxOf(256L * 1048576, memory.threshold)).coerceAtLeast(0)))
            .put("space_policy", "single-reused-file-plus-free-space-reserve-v2")
            .put("worker_pid", Process.myPid()).put("probed", cachedCapabilities != null)
    }
    // 调用方使用服务锁，保证 RAM 与存储测试互斥启动。
    // The caller holds the service lock to prevent concurrent RAM and storage runs.
    fun start(configJson: String): String {
        var created: Run? = null
        try {
            check(activeId == null) { "BUSY" }
            val requested = StorageConfig.fromJson(configJson)
            val config = requested.normalized()
            val caps = capabilities()
            require(config.fileMiB * 1048576L <= caps.getLong("free_bytes") - caps.getLong("reserve_bytes")) {
                "STORAGE_SPACE：测试文件 ${BenchmarkFormat.mib(config.fileMiB)}，可用 ${BenchmarkFormat.bytes(caps.getLong("free_bytes"))}，需保留 ${BenchmarkFormat.bytes(caps.getLong("reserve_bytes"))}"
            }
            require(config.estimatedMemoryBytes <= caps.getLong("memory_budget_bytes")) {
                "STORAGE_MEMORY：I/O 缓冲需 ${config.estimatedMemoryBytes / 1048576} MiB 内存，当前预算 ${caps.getLong("memory_budget_bytes") / 1048576} MiB"
            }
            require(thermal() < PowerManager.THERMAL_STATUS_SEVERE) { "RUN_THERMAL：设备需要降温" }
            val id = UUID.randomUUID().toString()
            val hash = MessageDigest.getInstance("SHA-256").digest(config.toJson().toString().toByteArray())
                .joinToString("") { "%02x".format(it) }
            val report = JSONObject().put("schema_version", 1).put("kind", "storage_benchmark").put("run_id", id)
                .put("state", "RUNNING").put("phase", "PROBING").put("config", config.toJson())
                .put("requested_config", requested.toJson())
                .put("config_sha256", hash).put("app_version", BuildConfig.VERSION_NAME)
                .put("disk_footprint_bytes", config.fileMiB * 1048576L).put("estimated_io_memory_bytes", config.estimatedMemoryBytes)
                .put("started_at_ms", System.currentTimeMillis()).put("capabilities", caps)
                .put("rounds", JSONArray()).put("completed_rounds", 0).put("processed_rounds", 0).put("total_rounds", config.totalRounds)
                .put("cleanup", JSONObject().put("state", "NOT_CREATED")).put("error", JSONObject.NULL)
                .put("device", JSONObject().put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
                    .put("api", Build.VERSION.SDK_INT).put("fingerprint", Build.FINGERPRINT))
            val run = Run(id, config, report)
            onRunStarting()
            try { store.save(report) } catch (error: Exception) { onRunFinished(); throw error }
            created = run
            current = run
            executor.execute { execute(run) }
            Log.i("BenchBridge", "STORAGE_START $id ${config.toJson()}")
            return JSONObject().put("accepted", true).put("run_id", id).put("worker_pid", Process.myPid()).toString()
        } catch (error: Exception) {
            created?.let { onRunFinished(); it.report.put("state", "FAILED").put("error", error.message); publish(it, true); it.finished = true }
            return JSONObject().put("accepted", false).put("error", error.message ?: "START_FAILED").toString()
        }
    }
    fun snapshot(id: String): String? {
        val run = current?.takeIf { it.id == id } ?: return store.read(id)?.let(::binderSnapshot)
        val report = JSONObject(run.snapshot)
        if (!run.finished && run.handle != 0L) {
            val progress = JSONObject(StorageNative.progress(run.handle))
            report.put("io_progress", progress)
            if (report.optString("phase") == "NATIVE") report.put("phase", when (progress.optInt("native_phase")) {
                1 -> "INITIALIZING"; 2 -> "WARMING"; 3 -> "MEASURING"; 4 -> "FLUSHING"; 5 -> "VALIDATING"; else -> "PREPARING"
            })
        }
        return report.toString()
    }
    fun cancel(id: String, reason: String) {
        val run = current?.takeIf { it.id == id && !it.finished } ?: return
        run.reason = reason; run.cancelled.set(true)
        if (run.handle != 0L) StorageNative.cancelSession(run.handle)
    }
    fun retryCleanup(id: String): String {
        check(activeId == null) { "BUSY" }
        val result = owned.cleanup(id)
        store.read(id)?.let { report -> report.put("cleanup", result); store.save(report) }
        current?.takeIf { it.id == id }?.let { it.report.put("cleanup", result); publish(it, false) }
        return result.toString()
    }
    private fun publish(run: Run, persist: Boolean = false) {
        if (persist) store.save(run.report)
        if (run.report.optString("state") != "RUNNING") run.finished = true
        run.snapshot = binderSnapshot(run.report)
    }
    private fun binderSnapshot(report: JSONObject): String {
        val text = report.toString()
        if (text.length <= 240000) return text
        val reduced = JSONObject(text)
        reduced.optJSONArray("rounds")?.let { rounds ->
            for (i in 0 until rounds.length()) {
                val sample = rounds.getJSONObject(i)
                sample.remove("latency_histogram"); sample.remove("per_thread_qd_time_ns"); sample.remove("per_thread")
            }
        }
        reduced.put("full_report_in_storage", true)
        return reduced.toString()
    }
    private fun execute(run: Run) {
        var complete = 0
        var processed = 0
        var failed = false
        val config = run.config
        try {
            if (cachedCapabilities == null && !run.cancelled.get()) cachedCapabilities = probeStorage(context, owned).toString()
            val caps = capabilities()
            run.report.put("capabilities", caps)
            if (run.cancelled.get()) return
            val directory = owned.create(run.id, "storage-benchmark")
            run.report.put("cleanup", JSONObject().put("state", "PENDING"))
            publish(run, true)
            run.handle = StorageNative.createSession(directory.absolutePath, config.fileMiB * 1048576L, config.writeBudgetMiB * 1048576L)
            if (run.cancelled.get()) StorageNative.cancelSession(run.handle)
            run.report.put("phase", "NATIVE"); publish(run)
            val prepared = JSONObject(StorageNative.prepare(run.handle))
            run.report.put("initialization", prepared)
            if (prepared.optString("status") != "COMPLETED") {
                if (!run.cancelled.get()) { failed = true; run.report.put("error", prepared.optString("error", "INITIALIZATION_FAILED")) }
                return
            }
            outer@ for (direction in config.directions) for (case in config.cases) {
                if (run.cancelled.get()) break@outer
                val unsupported = when {
                    config.direct && !caps.optBoolean(if (direction == "write") "direct_write" else "direct_read") -> "此路径不支持 Direct ${if (direction == "write") "写入" else "读取"}"
                    case.queue > 1 && !caps.optBoolean("native_aio") -> "系统不支持原生 AIO 队列"
                    else -> null
                }
                if (unsupported != null) {
                    run.report.getJSONArray("rounds").put(JSONObject().put("case_id", case.id).put("direction", direction)
                        .put("status", "UNSUPPORTED").put("error", unsupported).put("round", 0))
                    processed += config.rounds
                    run.report.put("processed_rounds", processed); publish(run, true)
                    continue
                }
                for (round in 1..config.rounds) {
                    if (run.cancelled.get()) break@outer
                    if (thermal() >= PowerManager.THERMAL_STATUS_SEVERE) { cancel(run.id, "RUN_THERMAL：设备需要降温"); break@outer }
                    run.report.put("current_case", case.id).put("current_direction", direction).put("current_round", round).put("phase", "NATIVE")
                    publish(run)
                    val before = thermal()
                    val sample = JSONObject(StorageNative.runRound(run.handle, case.random, direction == "write", case.blockKiB * 1024,
                        case.queue, case.threads, if (round == 1) config.warmupMs else 0, config.durationMs, config.direct))
                        .put("case_id", case.id).put("direction", direction).put("round", round)
                        .put("thermal_before", before).put("thermal_after", thermal())
                        .put("screen_interactive_after", context.getSystemService(PowerManager::class.java).isInteractive)
                    run.report.getJSONArray("rounds").put(sample)
                    if (sample.optString("status") == "COMPLETED" && sample.optBoolean("verified")) complete++
                    processed++
                    run.report.put("completed_rounds", complete).put("processed_rounds", processed)
                    publish(run, true)
                    Log.i("BenchBridge", "STORAGE_ROUND ${run.id} ${case.id} $direction $round ${sample.optString("status")}")
                    if (sample.optString("status") == "FAILED") { failed = true; run.report.put("error", sample.optString("error", "IO_FAILED")); break@outer }
                    if (sample.optString("status") == "INTERRUPTED") break@outer
                }
                if (processed < config.totalRounds) {
                    run.report.put("phase", "COOLING"); publish(run)
                    var left = config.intervalMs
                    while (left > 0 && !run.cancelled.get()) { val step = minOf(50, left); Thread.sleep(step.toLong()); left -= step }
                }
            }
        } catch (error: Exception) {
            failed = true; run.report.put("error", error.message ?: "STORAGE_FAILED")
            Log.e("BenchBridge", "STORAGE_ERROR ${run.id}", error)
        } finally {
            var budget = false
            if (run.handle != 0L) {
                val progress = JSONObject(StorageNative.progress(run.handle)); budget = progress.optBoolean("budget_exhausted")
                run.report.put("io_totals", progress)
                StorageNative.releaseSession(run.handle); run.handle = 0L
            }
            run.report.put("phase", "CLEANING"); publish(run)
            val cleanup = runCatching { owned.cleanup(run.id) }.getOrElse { JSONObject().put("state", "PENDING").put("error", it.message) }
            val finalState = when {
                run.cancelled.get() -> "CANCELLED"
                failed -> "FAILED"
                budget -> "INTERRUPTED"
                processed == config.totalRounds && complete == config.totalRounds -> "COMPLETED"
                processed == config.totalRounds -> "PARTIAL"
                else -> "INTERRUPTED"
            }
            run.report.put("cleanup", cleanup).put("state", finalState).put("phase", "FINISHED").put("finished_at_ms", System.currentTimeMillis())
            if (run.cancelled.get()) run.report.put("error", run.reason)
            else if (budget) run.report.put("error", "WRITE_BUDGET：累计写入达到 ${BenchmarkFormat.mib(config.writeBudgetMiB)} 上限；测试文件占用为 ${BenchmarkFormat.mib(config.fileMiB)}")
            onRunFinished()
            try { publish(run, true) } catch (error: Exception) {
                run.report.put("state", "FAILED").put("persistence_error", true).put("error", "REPORT_COMMIT_FAILED：${error.message}"); publish(run)
            }
            run.finished = true
            Log.i("BenchBridge", "STORAGE_FINISH ${run.id} state=$finalState complete=$complete cleanup=${cleanup.optString("state")}")
        }
    }
}
