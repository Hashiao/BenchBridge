package io.benchbridge.app.ram

import android.app.ActivityManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.util.Log
import io.benchbridge.app.BuildConfig
import io.benchbridge.app.diagnostics.DeviceInformation
import io.benchbridge.app.diagnostics.NativeProbe
import io.benchbridge.app.storage.StorageCoordinator
import io.benchbridge.app.hardware.CpuTopology
import io.benchbridge.app.compute.ComputeCoordinator
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

class RamRunnerService : Service() {
    private val lock = Any()
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var store: RunStore
    private lateinit var power: PowerManager
    private lateinit var storage: StorageCoordinator
    private lateinit var compute: ComputeCoordinator
    private lateinit var resources: BenchmarkRunResources
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null
    @Volatile private var current: Run? = null

    private class Run(val id: String, val config: RamConfig, val handle: Long, var report: JSONObject) {
        val cancelled = AtomicBoolean(false)
        @Volatile var reason: String = "RUN_CANCELLED"
        @Volatile var snapshotText: String = report.toString()
        @Volatile var finished: Boolean = false
    }

    override fun onCreate() {
        super.onCreate()
        store = RunStore(this)
        store.recoverInterrupted()
        power = getSystemService(PowerManager::class.java)
        resources = BenchmarkRunResources(this)
        storage = StorageCoordinator(this, executor, ::thermalStatus,
            onRunStarting = { resources.start("ROM") }, onRunFinished = { resources.finish() })
        compute = ComputeCoordinator(this,executor,::thermalStatus,{resources.start("GPGPU")},{resources.finish()})
        thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
            if (status >= PowerManager.THERMAL_STATUS_SEVERE) {
                requestCancel(current?.id.orEmpty(), "RUN_THERMAL：设备热状态过高")
                storage.activeId?.let { storage.cancel(it, "RUN_THERMAL：设备需要降温") }
                compute.activeId?.let { compute.cancel(it,"设备需要降温") }
            }
        }.also { power.addThermalStatusListener(it) }
    }

    private fun thermalStatus(): Int = try { power.currentThermalStatus } catch (_: RuntimeException) { -1 }

    private fun capabilityReport(): JSONObject {
        val info = ActivityManager.MemoryInfo()
        getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        return JSONObject(RamNative.capabilities()).apply {
            put("available_memory_bytes", info.availMem)
            put("total_memory_bytes", info.totalMem)
            put("memory_budget_bytes", minOf(info.availMem / 4, (info.availMem - 1073741824L).coerceAtLeast(0)))
            put("thermal_status", thermalStatus())
            put("worker_pid", Process.myPid())
            put("wake_lock_held", resources.wakeHeld)
            put("foreground_run", resources.active)
            val ramActive = current?.takeUnless { it.finished }
            put("active_run_id", ramActive?.id ?: storage.activeId ?: compute.activeId ?: "")
            put("active_family", if (ramActive != null) "ram" else if (storage.activeId != null) "storage" else if(compute.activeId!=null)"compute"else "")
            put("native", JSONObject(NativeProbe.inspect().rawJson))
        }
    }

    private val binder = object : IRamRunner.Stub() {
        override fun computeCapabilities():String=compute.capabilities().toString()
        override fun startCompute(configJson:String):String=synchronized(lock){
            val active=current?.takeUnless { it.finished }?.id?:storage.activeId?:compute.activeId
            if(active!=null)JSONObject().put("accepted",false).put("error","BUSY：已有测试正在运行").put("run_id",active).toString()
            else compute.start(configJson)
        }
        override fun storageCapabilities(): String = storage.capabilities().toString()
        override fun startStorage(configJson: String): String = synchronized(lock) {
            val active = current?.takeUnless { it.finished }?.id ?: storage.activeId ?: compute.activeId
            if (active != null) JSONObject().put("accepted", false).put("error", "BUSY：已有测试正在运行").put("run_id", active).toString()
            else storage.start(configJson)
        }
        override fun cleanupStorage(runId: String): String = synchronized(lock) {
            if (current?.finished == false || storage.activeId != null || compute.activeId != null) JSONObject().put("state", "PENDING").put("error", "BUSY").toString()
            else storage.retryCleanup(runId)
        }
        override fun capabilities(): String = capabilityReport().toString()
        override fun startRam(configJson: String): String = synchronized(lock) {
            compute.activeId?.let { return@synchronized JSONObject().put("accepted",false).put("error","BUSY：已有计算测试正在运行").put("run_id",it).toString() }
            storage.activeId?.let {
                return@synchronized JSONObject().put("accepted", false).put("error", "BUSY：已有存储测试正在运行").put("run_id", it).toString()
            }
            current?.takeUnless { it.finished }?.let {
                return@synchronized JSONObject().put("accepted", false).put("error", "BUSY：已有测试正在运行")
                    .put("run_id", it.id).toString()
            }
            try {
                val requested = RamConfig.fromJson(configJson)
                val resumeId = JSONObject(configJson).optString("resume_run_id")
                val previous = if (resumeId.isEmpty()) null else store.read(resumeId).also {
                    require(it != null && CacheProbe.canResume(it)) { "该记录无法续测，请重新测试" }
                    require(requested.sameParameters(RamConfig.fromJson(it.getJSONObject("config").toString()))) { "续测参数与原记录不一致" }
                }
                val caps = capabilityReport()
                val config = requested.resolveThreads(caps.optInt("allowed_cpus", 1)).normalized()
                require(config.estimatedBytes() <= caps.getLong("memory_budget_bytes")) {
                    "RAM_BUDGET：需要约 ${config.estimatedBytes() / 1048576} MiB，当前预算 ${caps.getLong("memory_budget_bytes") / 1048576} MiB；请明确选择较小工作集"
                }
                require(thermalStatus() < PowerManager.THERMAL_STATUS_SEVERE) { "RUN_THERMAL：设备需要降温" }
                val id = UUID.randomUUID().toString()
                val configuration = config.toJson()
                val hash = MessageDigest.getInstance("SHA-256").digest(configuration.toString().toByteArray())
                    .joinToString("") { "%02x".format(it) }
                val flags = mutableListOf("ui_overhead_not_characterized", "cached_memory_access")
                if (BuildConfig.DEBUG) flags += "development_build"
                if (DeviceInformation().appearsToBeEmulator) flags += "emulator_functional_test"
                if (thermalStatus() == -1) flags += "thermal_status_unknown"
                val report = JSONObject().apply {
                    put("schema_version", if (config.cacheMatrix) 2 else 1); put("kind", "ram_benchmark"); put("run_id", id)
                    put("state", "RUNNING"); put("phase", "PREPARING")
                    put("started_at_ms", System.currentTimeMillis()); put("app_version", BuildConfig.VERSION_NAME)
                    put("config", configuration); put("requested_config", requested.toJson())
                    put("config_sha256", hash); put("capabilities", caps)
                    put("effective_plan", JSONArray(config.kinds.map { kind ->
                        JSONObject().put("kind", kind).put("working_set_bytes", config.bytes(kind))
                            .put("threads", config.threads(kind)).put("rounds", config.rounds(kind))
                    }))
                    put("quality_flags", JSONArray(flags)); put("rounds", JSONArray())
                    put("completed_rounds", 0); put("processed_rounds", 0); put("total_rounds", config.totalRounds)
                    put("device", JSONObject().put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
                        .put("api", Build.VERSION.SDK_INT).put("android", Build.VERSION.RELEASE)
                        .put("fingerprint", Build.FINGERPRINT))
                    put("error", JSONObject.NULL)
                    if (previous != null) {
                        put("cache_probe", previous.getJSONObject("cache_probe"))
                        put("resumed_from_run_id", resumeId)
                        put("original_started_at_ms", previous.optLong("original_started_at_ms", previous.getLong("started_at_ms")))
                    }
                }
                val handle = RamNative.createSession()
                try { resources.start("RAM"); store.save(report) }
                catch (error: Exception) { RamNative.releaseSession(handle); resources.finish(); throw error }
                val run = Run(id, config, handle, report)
                current = run
                try { executor.execute { executeRun(run) } }
                catch (error: RuntimeException) {
                    RamNative.releaseSession(handle); resources.finish()
                    run.report.put("state", "FAILED").put("error", "START_FAILED：${error.message}")
                    publish(run, persist = true)
                    throw error
                }
                Log.i("BenchBridge", "RAM_START $id ${configuration}")
                JSONObject().put("accepted", true).put("run_id", id).put("worker_pid", Process.myPid()).toString()
            } catch (error: Exception) {
                JSONObject().put("accepted", false).put("error", error.message ?: error.javaClass.simpleName).toString()
            }
        }
        override fun snapshot(runId: String): String {
            val run = current?.takeIf { it.id == runId }
                ?: return storage.snapshot(runId) ?: compute.snapshot(runId) ?: store.read(runId)?.let { compactReport(it).toString() } ?: JSONObject().put("error", "RUN_NOT_FOUND").toString()
            val result = JSONObject(run.snapshotText)
            if (!run.finished) {
                val nativePhase = RamNative.phase(run.handle)
                val name = listOf("", "PREPARING", "WARMING", "MEASURING", "VALIDATING").getOrElse(nativePhase) { "" }
                if (name.isNotEmpty() && result.optString("phase") !in listOf("CALIBRATING", "CACHE_PROBING")) result.put("phase", name)
                result.put("cancel_requested", run.cancelled.get())
            }
            return result.toString()
        }
        override fun cancel(runId: String, reason: String) {
            requestCancel(runId, reason.take(160))
            storage.cancel(runId, reason.take(160))
            compute.cancel(runId,reason.take(160))
        }
    }

    private fun requestCancel(id: String, reason: String) {
        val run = current?.takeIf { it.id == id && !it.finished } ?: return
        run.reason = reason
        run.cancelled.set(true)
        RamNative.cancelSession(run.handle)
        Log.i("BenchBridge", "RAM_CANCEL $id $reason")
    }

    private fun publish(run: Run, persist: Boolean = false) {
        if (persist) store.save(run.report)
        if (run.report.optString("state") in RamResults.terminalStates) run.finished = true
        if (run.report.has("cache_probe")) {
            run.snapshotText = compactReport(run.report).toString()
            return
        }
        val text = run.report.toString()
        if (text.length <= 240000) run.snapshotText = text
        else {
            // 大型校准记录保留在磁盘，Binder 仅传界面需要的摘要。
            // Keep full calibration records on disk and send only UI summaries through Binder.
            val compact = JSONObject(text)
            compact.optJSONObject("cache_probe")?.optJSONArray("groups")?.let { groups ->
                for (i in 0 until groups.length()) groups.getJSONObject(i).remove("samples")
            }
            compact.optJSONArray("cells")?.let { cells ->
                for (i in 0 until cells.length()) cells.getJSONObject(i).remove("calibration")
            }
            compact.put("full_report_in_storage", true)
            run.snapshotText = compact.toString()
        }
    }

    // 避免先序列化再解析整份采样记录；磁盘保存全量，Binder 只传摘要。
    // Avoid serializing and reparsing all raw samples; persist the full report and send a compact Binder view.
    private fun compactReport(report: JSONObject): JSONObject {
        fun copy(source: JSONObject, excluded: Set<String>) = JSONObject().apply {
            source.keys().forEach { key -> if (key !in excluded) put(key, source.get(key)) }
        }
        return copy(report, setOf("cache_probe", "cells")).apply {
            report.optJSONObject("cache_probe")?.let { probe ->
                put("cache_probe", copy(probe, setOf("groups")).put("groups", JSONArray().apply {
                    val groups = probe.optJSONArray("groups") ?: JSONArray()
                    for (i in 0 until groups.length()) put(copy(groups.getJSONObject(i), setOf("samples")))
                }))
            }
            report.optJSONArray("cells")?.let { cells ->
                put("cells", JSONArray().apply { for (i in 0 until cells.length()) put(copy(cells.getJSONObject(i), setOf("calibration"))) })
            }
            put("full_report_in_storage", true)
        }
    }

    private fun executeRun(run: Run) {
        var completed = 0
        var failed = false
        try {
            if (run.config.cacheMatrix) {
                val topology = CpuTopology.collect(this, JSONObject(RamNative.capabilities()))
                val state = MemoryMatrixRunner(this, run.config, run.report, run.handle, topology,
                    run.report.getJSONObject("capabilities").getLong("memory_budget_bytes"),
                    cancelled = { run.cancelled.get() }, thermal = ::thermalStatus,
                    cancel = { requestCancel(run.id, it) }, publish = { publish(run, it) }).execute()
                completed = run.report.optInt("completed_rounds")
                run.report.put("state", state)
                if (run.cancelled.get()) run.report.put("error", run.reason)
                return
            }
            outer@ for (kind in run.config.kinds) {
                for (round in 1..run.config.rounds(kind)) {
                    if (run.cancelled.get()) break@outer
                    if (thermalStatus() >= PowerManager.THERMAL_STATUS_SEVERE) {
                        requestCancel(run.id, "RUN_THERMAL：设备需要降温")
                        break@outer
                    }
                    run.report.put("current_kind", kind).put("current_round", round).put("phase", "PREPARING")
                    publish(run)
                    val thermalBefore = thermalStatus()
                    val sample = JSONObject(RamNative.runRound(run.handle, kind, run.config.bytes(kind),
                        run.config.threads(kind), run.config.warmupMs, run.config.durationMs, 0xB16B00B5L))
                    sample.put("kind", kind).put("round", round)
                        .put("thermal_before", thermalBefore).put("thermal_after", thermalStatus())
                        .put("screen_interactive_after", power.isInteractive).put("wake_lock_held", resources.wakeHeld)
                    run.report.getJSONArray("rounds").put(sample)
                    if (sample.optString("status") == "COMPLETED" && sample.optBoolean("verified")) completed++
                    run.report.put("completed_rounds", completed).put("phase", "PERSISTING")
                    publish(run, persist = true)
                    Log.i("BenchBridge", "RAM_ROUND ${run.id} kind=$kind round=$round status=${sample.optString("status")}")
                    if (sample.optString("status") == "FAILED") {
                        failed = true
                        run.report.put("error", sample.optString("error", "NATIVE_FAILED"))
                        break@outer
                    }
                    if (sample.optString("status") == "INTERRUPTED") break@outer
                    if (completed < run.config.totalRounds) {
                        run.report.put("phase", "COOLING")
                        publish(run)
                        var rest = run.config.cooldownMs
                        while (rest > 0 && !run.cancelled.get()) {
                            val step = minOf(rest, 50)
                            Thread.sleep(step.toLong()); rest -= step
                        }
                    }
                }
            }
            val finalState = when {
                failed -> "FAILED"
                completed == run.config.totalRounds -> "COMPLETED"
                run.cancelled.get() -> "CANCELLED"
                else -> "INTERRUPTED"
            }
            run.report.put("state", finalState)
            if (run.cancelled.get() && finalState != "COMPLETED") run.report.put("error", run.reason)
        } catch (error: Exception) {
            run.report.put("state", "FAILED").put("error", "RUN_FAILED：${error.message}")
            Log.e("BenchBridge", "RAM_ERROR ${run.id}", error)
        } finally {
            RamNative.releaseSession(run.handle)
            resources.finish()
            run.report.put("phase", "FINISHED").put("finished_at_ms", System.currentTimeMillis())
            try { publish(run, persist = true) } catch (error: Exception) {
                run.report.put("state", "FAILED").put("persistence_error", true)
                    .put("error", "REPORT_COMMIT_FAILED：${error.message}")
                publish(run)
            }
            run.finished = true
            Log.i("BenchBridge", "RAM_FINISH ${run.id} state=${run.report.optString("state")} completed=$completed")
        }
    }

    override fun onBind(intent: Intent): IBinder = binder
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == BenchmarkRunResources.ACTION_STOP) {
            requestCancel(current?.id.orEmpty(), "RUN_CANCELLED：通知栏停止")
            storage.activeId?.let { storage.cancel(it, "RUN_CANCELLED：通知栏停止") }
            compute.activeId?.let { compute.cancel(it,"用户停止") }
            if (!resources.active) stopSelf(startId)
        }
        return START_NOT_STICKY
    }
    override fun onUnbind(intent: Intent): Boolean {
        // 用户启动的前台测试在界面解绑后继续运行。
        // A user-started foreground run continues after the UI unbinds.
        return super.onUnbind(intent)
    }
    override fun onDestroy() {
        requestCancel(current?.id.orEmpty(), "RUN_SERVICE_STOPPED")
        storage.activeId?.let { storage.cancel(it, "RUN_SERVICE_STOPPED") }
        compute.activeId?.let { compute.cancel(it,"RUN_SERVICE_STOPPED") }
        resources.finish()
        thermalListener?.let { power.removeThermalStatusListener(it) }
        executor.shutdown()
        super.onDestroy()
    }
}
