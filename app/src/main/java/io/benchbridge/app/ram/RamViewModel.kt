package io.benchbridge.app.ram

import io.benchbridge.app.i18n.L10n

import android.app.Application
import android.net.Uri
import android.os.Process
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.benchbridge.app.storage.StorageConfig
import io.benchbridge.app.storage.StorageCase
import io.benchbridge.app.compute.ComputeConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class RamUiState(
    val config: RamConfig = RamConfig.aida64(), val capabilities: JSONObject? = null,
    val report: JSONObject? = null, val history: List<JSONObject> = emptyList(),
    val storageConfig: StorageConfig = StorageConfig(), val storageCapabilities: JSONObject? = null,
    val storageReport: JSONObject? = null, val activeFamily: String = "ram",
    val selectedHistory: JSONObject? = null, val starting: Boolean = false,
    val cancelling: Boolean = false, val error: String? = null, val notice: String? = null,
    val computeConfig:ComputeConfig=ComputeConfig(),val computeReport:JSONObject?=null,
    val computeCapabilities:JSONObject?=null,
) {
    val running: Boolean get() = starting || report?.optString("state") == "RUNNING" || storageReport?.optString("state") == "RUNNING" || computeReport?.optString("state")=="RUNNING"
    val activeReport: JSONObject? get() = when(activeFamily){"storage"->storageReport;"compute"->computeReport;else->report}
}

class RamViewModel(application: Application) : AndroidViewModel(application) {
    private val store = RunStore(application)
    private val storageStore = RunStore(application, "storage_results")
    private val computeStore = RunStore(application,"compute_results")
    private val client = RunnerClient(application)
    private val mutableState = MutableStateFlow(RamUiState())
    val state = mutableState.asStateFlow()
    private var pendingCancel: String? = null
    private var cancelAt = 0L
    init {
        refreshHistory()
        viewModelScope.launch {
            try {
                val caps = withContext(Dispatchers.IO) { JSONObject(client.service().capabilities()) }
                val disk = withContext(Dispatchers.IO) { JSONObject(client.service().storageCapabilities()) }
                val old = mutableState.value
                val config = if (!old.running && old.report == null) old.config.resolveThreads(caps.optInt("allowed_cpus", 1)).normalized() else old.config
                mutableState.value = old.copy(capabilities = caps, config = config, storageCapabilities = disk)
                refreshHistory()
                val activeId = caps.optString("active_run_id")
                if (activeId.isNotEmpty() && !mutableState.value.running) {
                    startFamily(caps.optString("active_family", "ram"), JSONObject()
                        .put("accepted", true).put("run_id", activeId).put("worker_pid", caps.getInt("worker_pid")))
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.value = mutableState.value.copy(error = L10n.t("m_b7f080d32fac", error.message))
            }
        }
    }
    fun configure(config: RamConfig) {
        if (mutableState.value.running) return
        val next = config.resolveThreads(mutableState.value.capabilities?.optInt("allowed_cpus", 1) ?: config.threads).normalized()
        val old = mutableState.value
        mutableState.value = old.copy(config = next, report = if (old.config.sameParameters(next)) old.report else null, error = null)
    }
    fun configureStorage(config: StorageConfig) {
        if (mutableState.value.running) return
        val old = mutableState.value
        val next = config.normalized()
        mutableState.value = old.copy(storageConfig = next, storageReport = if (old.storageConfig.sameParameters(next)) old.storageReport else null, error = null)
    }
    fun replaceStorageCase(originalId: String, value: StorageCase): Boolean {
        if (mutableState.value.running) return false
        val config = mutableState.value.storageConfig
        val updated = value.canonical()
        val cases = config.cases.map { if (it.id == originalId) updated else it }
        if (cases.map { it.id }.distinct().size != cases.size) {
            mutableState.value = mutableState.value.copy(error = L10n.t("m_b7e7f476b32b"))
            return false
        }
        configureStorage(config.copy(cases = cases))
        return true
    }
    fun start() = startFamily("ram")
    fun resumeCurve(report: JSONObject) {
        if (mutableState.value.running || !CacheProbe.canResume(report)) return
        configure(RamConfig.fromJson(report.getJSONObject("config").toString()))
        startFamily("ram", resumeId = report.getString("run_id"))
    }
    fun startStorage() = startFamily("storage")
    fun startCompute() = startFamily("compute")
    fun configureCompute(config:ComputeConfig){
        val old=mutableState.value;if(old.running)return
        mutableState.value=old.copy(computeConfig=config,computeReport=if(old.computeConfig==config)old.computeReport else null,error=null)
    }
    private fun resultStore(family:String)=when(family){"storage"->storageStore;"compute"->computeStore;else->store}
    private fun updateReport(report: JSONObject?, family: String) {
        mutableState.value = if(family=="compute")mutableState.value.copy(computeReport=report,
            computeCapabilities=report?.optJSONObject("capabilities")?:mutableState.value.computeCapabilities,
            computeConfig=report?.optJSONObject("config")?.let { ComputeConfig.fromJson(it.toString()) }?:mutableState.value.computeConfig)
        else if (family == "storage") mutableState.value.copy(storageReport = report,
            storageConfig = report?.optJSONObject("config")?.let { StorageConfig.fromJson(it.toString()) } ?: mutableState.value.storageConfig)
        else mutableState.value.copy(report = report,
            config = report?.optJSONObject("config")?.let { RamConfig.fromJson(it.toString()) } ?: mutableState.value.config)
    }
    private fun startFamily(family: String, resumed: JSONObject? = null, resumeId: String? = null) {
        if (mutableState.value.running) return
        val json = if (resumed != null) JSONObject() else try {
            if(family=="compute")mutableState.value.computeConfig.also { it.validate() }.toJson()
            else if (family == "storage") mutableState.value.storageConfig.also { it.validate() }.toJson()
            else mutableState.value.config.also { it.validate() }.toJson()
        } catch (error: Exception) { mutableState.value = mutableState.value.copy(error = error.message); return }
        resumeId?.let { json.put("resume_run_id", it) }
        pendingCancel = null; cancelAt = 0
        mutableState.value = mutableState.value.copy(starting = true, cancelling = false, error = null, activeFamily = family)
        updateReport(null, family)
        viewModelScope.launch {
            var id: String? = null
            try {
                val response = resumed ?: withContext(Dispatchers.IO) {
                    val runner = client.service()
                    JSONObject(when(family){"compute"->runner.startCompute(json.toString());"storage"->runner.startStorage(json.toString());else->runner.startRam(json.toString())})
                }
                check(response.optBoolean("accepted")) { response.optString("error", L10n.t("m_1ec43d31e733")) }
                val runId = response.getString("run_id"); id = runId
                val workerPid = response.getInt("worker_pid")
                var cancelSent = false
                while (true) {
                    pendingCancel?.takeUnless { cancelSent }?.let { reason ->
                        withContext(Dispatchers.IO) { client.service().cancel(runId, reason) }; cancelSent = true
                    }
                    val report = withContext(Dispatchers.IO) {
                        val snapshot = JSONObject(client.service().snapshot(runId))
                        if (snapshot.optBoolean("full_report_in_storage") && snapshot.optString("state") in RamResults.terminalStates)
                            resultStore(family).read(runId) ?: snapshot else snapshot
                    }
                    check(report.has("state")) { report.optString("error", L10n.t("m_6dc034e51a65")) }
                    updateReport(report, family)
                    mutableState.value = mutableState.value.copy(starting = false)
                    if (report.getString("state") in RamResults.terminalStates) break
                    if (cancelAt > 0 && SystemClock.elapsedRealtime() - cancelAt > 15000) {
                        if (workerPid > 0 && workerPid != Process.myPid()) Process.killProcess(workerPid)
                        error(L10n.t("m_427bd266056e"))
                    }
                    delay(300)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                val saved = id?.let { runId -> withContext(Dispatchers.IO) { runCatching { resultStore(family).read(runId) }.getOrNull() } }
                val interrupted = (saved ?: mutableState.value.activeReport)?.let { JSONObject(it.toString()).apply {
                    if(optString("state") !in RamResults.terminalStates){put("state", "INTERRUPTED"); put("error", L10n.t("m_0b24b4556a91"))}
                } }
                updateReport(interrupted, family)
                mutableState.value = mutableState.value.copy(error = L10n.t("m_c150a6c42646", error.message))
            } finally {
                mutableState.value = mutableState.value.copy(starting = false, cancelling = false)
                refreshHistory()
            }
        }
    }
    fun cancel(reason: String = L10n.t("m_16b108837a48")) {
        if (!mutableState.value.running) return
        pendingCancel = reason
        if (cancelAt == 0L) cancelAt = SystemClock.elapsedRealtime()
        mutableState.value = mutableState.value.copy(cancelling = true)
        val id = mutableState.value.activeReport?.optString("run_id") ?: return
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { client.service().cancel(id, reason) } }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.value = mutableState.value.copy(error = L10n.t("m_19aab1fbad7d", error.message))
            }
        }
    }
    fun retryCleanup(id: String) {
        if (mutableState.value.running) return
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { JSONObject(client.service().cleanupStorage(id)) }
                val report = withContext(Dispatchers.IO) { storageStore.read(id) }
                mutableState.value = mutableState.value.copy(
                    storageReport = if (mutableState.value.storageReport?.optString("run_id") == id) report else mutableState.value.storageReport,
                    selectedHistory = if (mutableState.value.selectedHistory?.optString("run_id") == id) report else mutableState.value.selectedHistory,
                    notice = if (result.optString("state") == "CLEANED") L10n.t("m_71b19ba2c1a1") else L10n.t("m_062fe9555cd9", result.optString("error")))
                refreshHistory()
            } catch (error: Exception) { mutableState.value = mutableState.value.copy(error = error.message) }
        }
    }
    fun refreshHistory() {
        viewModelScope.launch {
            val history = withContext(Dispatchers.IO) { (store.list() + storageStore.list() + computeStore.list()).sortedByDescending { it.optLong("started_at_ms") }.take(200) }
            mutableState.value = mutableState.value.copy(history = history)
        }
    }
    fun selectHistory(report: JSONObject?) { mutableState.value = mutableState.value.copy(selectedHistory = report) }
    suspend fun prepareExport(report: JSONObject): String = withContext(Dispatchers.IO) {
        PreparedExport(getApplication()).prepare(report)
    }
    fun exportPrepared(uri: Uri?, token: String) {
        viewModelScope.launch {
            val notice = withContext(Dispatchers.IO) {
                try {
                    PreparedExport(getApplication()).finish(uri, token)
                    if (uri != null) L10n.t("m_09055b9156f9") else null
                } catch (error: Exception) { L10n.t("m_8a0302ddf022", error.message) }
            }
            mutableState.value = mutableState.value.copy(notice = notice)
        }
    }
    fun dismissNotice() { mutableState.value = mutableState.value.copy(notice = null) }
    override fun onCleared() { client.close() }
}
