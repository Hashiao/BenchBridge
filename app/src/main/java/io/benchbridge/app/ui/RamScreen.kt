package io.benchbridge.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.Manifest
import android.os.Build
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import io.benchbridge.app.BuildConfig
import io.benchbridge.app.BenchmarkFormat
import io.benchbridge.app.EnvironmentScreen
import io.benchbridge.app.R
import io.benchbridge.app.diagnostics.DeviceInformation
import io.benchbridge.app.diagnostics.DiagnosticsViewModel
import io.benchbridge.app.ram.*
import io.benchbridge.app.storage.StorageConfig
import io.benchbridge.app.compute.ComputeConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject
import kotlinx.coroutines.launch

@Composable
fun BenchBridgeApp(state: RamUiState, model: RamViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var settingsTab by rememberSaveable { mutableIntStateOf(-1) }
    var details by rememberSaveable { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var pendingStart by rememberSaveable { mutableStateOf<String?>(null) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        val family = pendingStart
        pendingStart = null
        if (family == "compute") model.startCompute() else if (family == "storage") model.startStorage() else if (family == "ram") model.start()
    }
    val startBenchmark: () -> Unit = {
        val family = when(tab){1->"storage";4->"compute";else->"ram"}
        val preferences = context.getSharedPreferences("permissions", Context.MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            && !preferences.getBoolean("notifications_asked", false)) {
            pendingStart = family
            preferences.edit { putBoolean("notifications_asked", true) }
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (family == "compute") model.startCompute() else if (family == "storage") model.startStorage() else model.start()
    }
    val scope = rememberCoroutineScope()
    var pendingExport by rememberSaveable { mutableStateOf("") }
    var preparingExport by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (pendingExport.isNotEmpty()) model.exportPrepared(uri, pendingExport)
        pendingExport = ""
    }
    val exportReport: (JSONObject) -> Unit = { report ->
        if (!preparingExport && pendingExport.isEmpty()) scope.launch {
            preparingExport = true
            try {
                pendingExport = model.prepareExport(report)
                val family = when(report.optString("kind")){"compute_benchmark"->"GPGPU";"storage_benchmark"->"ROM";else->"RAM"}
                export.launch("BenchBridge_${family}_${report.getString("run_id").take(8)}.json")
            } catch (error: Exception) {
                if (pendingExport.isNotEmpty()) model.exportPrepared(null, pendingExport)
                pendingExport = ""
                snackbar.showSnackbar(error.message ?: "导出准备失败")
            } finally { preparingExport = false }
        }
    }
    LaunchedEffect(state.notice) { state.notice?.let { snackbar.showSnackbar(it); model.dismissNotice() } }
    LaunchedEffect(state.running) { if (state.running) { settingsTab = -1; details = false; tab = when(state.activeFamily){"compute"->4;"storage"->1;else->0} } }
    BackHandler(settingsTab >= 0 || details || tab == 2 && state.selectedHistory != null) {
        when { settingsTab >= 0 -> settingsTab = -1; details -> details = false; else -> model.selectHistory(null) }
    }
    val displayed = when (tab) { 0 -> state.report; 1 -> state.storageReport; 2 -> state.selectedHistory; 4->state.computeReport; else -> null }
    val share: () -> Unit = {
        if (!sharing && !state.running) scope.launch {
            sharing = true
            try { shareScreenshot(context) } catch (error: Exception) { snackbar.showSnackbar(error.message ?: "截图失败") }
            finally { sharing = false }
        }
    }
    Scaffold(modifier = Modifier.semantics { testTagsAsResourceId = true }, snackbarHost = { SnackbarHost(snackbar) }, topBar = {
        if (settingsTab >= 0 || details) Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (details) "测试详情" else when(settingsTab){0->"RAM 设置";4->"GPGPU 设置";else->"ROM 设置"}, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { settingsTab = -1; details = false }, modifier = Modifier.testTag("settings_done")) { Text("完成") }
        }
    }, bottomBar = {
        if (settingsTab < 0 && !details)
        Column {
            if (state.running) {
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (state.cancelling) "正在停止…" else "${when(state.activeFamily){"compute"->"GPGPU";"storage"->"ROM";else->"RAM"}} 测试中", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { model.cancel() }, enabled = !state.cancelling,
                            modifier = Modifier.testTag("ram_stop")) { Text("停止") }
                    }
                }
            } else if (tab in listOf(0,1,4) || displayed != null) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (displayed != null) FilledTonalButton(onClick = share, enabled = !sharing, modifier = Modifier.weight(1f).testTag("share_screenshot"),
                        contentPadding = PaddingValues(vertical = 14.dp)) { Text("分享截图") }
                    if (tab in listOf(0,1,4)) Button(onClick = startBenchmark,
                        enabled = when(tab){4->state.computeConfig.kinds.isNotEmpty()&&state.computeConfig.targets.isNotEmpty();1->state.storageConfig.cases.isNotEmpty();else->state.config.kinds.isNotEmpty()},
                        modifier = Modifier.weight(1f).testTag(when(tab){4->"compute_start";1->"storage_start";else->"ram_start"}),
                        contentPadding = PaddingValues(vertical = 14.dp)) { Text(if (displayed == null) "开始测试" else "重新测试") }
                }
            }
            NavigationBar {
                listOf(0 to "RAM",1 to "ROM",4 to "GPGPU",2 to "历史",3 to "设备").forEach { (index,title) ->
                    NavigationBarItem(selected = tab == index, enabled = !state.running || index == tab,
                        onClick = { tab = index; if (index == 2) model.refreshHistory() },
                        icon = { Icon(painterResource(listOf(R.drawable.ic_memory, R.drawable.ic_storage, R.drawable.ic_history, R.drawable.ic_device,R.drawable.ic_compute)[index]), null) },
                        label = { Text(title) }, modifier = Modifier.testTag("tab_$index"))
                }
            }
        }
    }) { insets ->
        Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets), contentAlignment = Alignment.TopCenter) {
            when {
                settingsTab == 0 -> RamSettingsPage(state, model)
                settingsTab == 1 -> StorageSettingsPage(state, model)
                settingsTab == 4 -> ComputeSettings(state,model)
                details && displayed != null -> DetailedReportPage(displayed, model, exportReport)
                tab == 4 -> ComputeDashboard(displayed,state.computeConfig,state.running,{settingsTab=4},displayed?.let { {details=true} },error=state.error)
                tab == 0 || tab == 1 -> ResultDashboard(tab == 1, displayed, state.config, state.storageConfig, state.running, state.error,
                    onSettings = { settingsTab = tab }, onDetails = displayed?.let { { details = true } })
                tab == 2 && state.selectedHistory != null -> if(state.selectedHistory.optString("kind")=="compute_benchmark")
                    ComputeDashboard(state.selectedHistory,state.computeConfig,false,null,{details=true},{model.selectHistory(null)})
                else ResultDashboard(state.selectedHistory.optString("kind") == "storage_benchmark",state.selectedHistory, state.config, state.storageConfig, false, null, null, { details = true }, { model.selectHistory(null) })
                tab == 2 -> HistoryPage(state, model, exportReport)
                else -> {
                    val diagnostics: DiagnosticsViewModel = viewModel()
                    val diagnosticState by diagnostics.state.collectAsStateWithLifecycle()
                    EnvironmentScreen(diagnosticState, diagnostics::refresh)
                }
            }
        }
    }
}

@Composable
private fun DetailedReportPage(report: JSONObject, model: RamViewModel, onExport: (JSONObject) -> Unit) {
    if(report.optString("kind")=="compute_benchmark"){ComputeDetails(report,onExport);return}
    LazyColumn(Modifier.fillMaxSize().testTag("details_page"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ReportActions(report, onExport) }
        if (report.optString("kind") == "storage_benchmark") {
            item { StorageSummary(report, false, model) }
            item { StorageMatrix(report, null) }
        } else {
            item { RunProgress(report, false) }
            item { SectionCard("本次参数") { Text(RamConfig.fromJson(report.getJSONObject("config").toString()).summary,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("ram_result_config")) } }
            val config = RamConfig.fromJson(report.getJSONObject("config").toString())
            if (config.cacheMatrix) item { CacheTopologyDetails(report) }
            config.scoredLevels.forEach { level ->
                config.kinds.forEach { code -> item { ResultCard(report, RamKind.entries.first { it.code == code }, level) } }
            }
        }
    }
}

@Composable
internal fun RunProgress(report: JSONObject, running: Boolean) {
    SectionCard(RamResults.stateLabel(report.optString("state"))) {
        Text("已完成 ${report.optInt("completed_rounds")} / ${report.optInt("total_rounds")} 轮", modifier = Modifier.testTag("run_state_${report.optString("state")}"))
        if (running) {
            val kind = RamKind.entries.getOrNull(report.optInt("current_kind", -1))
            Text("${kind?.title ?: "RAM"} · 第 ${report.optInt("current_round", 1)} 轮 · ${RamResults.phaseLabel(report.optString("phase"))}", style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(progress = { report.optInt("completed_rounds").toFloat() / report.optInt("total_rounds", 1).coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
        }
        if (!report.isNull("error")) Text(report.optString("error"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        if (report.optBoolean("persistence_error")) Text("结果未写入历史，请导出保存。", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ResultCard(report: JSONObject, kind: RamKind, level: String = "RAM") {
    var expanded by rememberSaveable(report.optString("run_id"), level, kind.code) { mutableStateOf(false) }
    val stats = RamResults.statistics(report, kind.code, level)
    val cell = RamResults.cell(report, level, kind.code)
    SectionCard("$level · " + if (kind == RamKind.COPY) "复制 · 读写合计" else kind.title) {
        cell?.optJSONObject("plan")?.let { plan ->
            Text("T${plan.optInt("threads")} · CPU ${plan.optJSONArray("cpu_ids")} · 总工作集 ${BenchmarkFormat.bytes(plan.optLong("working_set_bytes"))}", style = MaterialTheme.typography.bodySmall)
            Text("每线程 ${plan.optJSONArray("per_thread_working_set_bytes")} B · ${BenchmarkFormat.duration(plan.optInt("duration_ms"))} × ${plan.optInt("rounds")}", style = MaterialTheme.typography.bodySmall)
            if (kind == RamKind.LATENCY) Text("节点间隔 ${plan.optInt("node_stride_bytes")} B", style = MaterialTheme.typography.bodySmall)
        }
        cell?.takeIf { it.has("reason") }?.let { Text(it.optString("reason"), style = MaterialTheme.typography.bodySmall) }
        cell?.optJSONObject("calibration")?.let { calibration ->
            val candidates = calibration.optJSONArray("candidates")
            Text("校准 ${candidates?.length() ?: 0} 个组合 · 每组合 2–3 次 × ${calibration.optInt("trial_ms")} ms", style = MaterialTheme.typography.bodySmall)
            Text("绑核、工作集及各次校准结果保存在导出 JSON 中。", style = MaterialTheme.typography.bodySmall)
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stats?.let { "%.2f".format(Locale.US, it.median * if (kind == RamKind.LATENCY) 1 else 1000) } ?: "—", style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("result_${kind.code}"))
            Text(if (kind == RamKind.LATENCY) "ns" else "MB/s", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 6.dp))
        }
        stats?.let {
            Text("${it.count} 个有效轮次的中位数" + (it.cvPercent?.let { cv -> " · CV %.1f%%".format(Locale.US, cv) } ?: ""), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起原始轮次" else "查看原始轮次") }
            if (expanded) {
                Text(if (kind == RamKind.LATENCY && cell != null) "单线程依赖指针链，随机遍历每条缓存行。" else kind.explanation, style = MaterialTheme.typography.bodySmall)
                if (kind == RamKind.COPY) Text("单向复制量：%.2f MB/s".format(Locale.US, it.median * 500), style = MaterialTheme.typography.bodySmall)
                val scale = if (kind == RamKind.LATENCY) 1 else 1000
                val unit = if (kind == RamKind.LATENCY) "ns" else "MB/s"
                Text("最小 / 最大：%.2f / %.2f $unit".format(Locale.US, it.minimum * scale, it.maximum * scale), style = MaterialTheme.typography.bodySmall)
                RamResults.validRounds(report, kind.code, level).forEach { sample ->
                HorizontalDivider()
                Text("第 ${sample.getInt("round")} 轮 · ${sample.getInt("threads")} 线程 · ${BenchmarkFormat.bytes(sample.getLong("working_set_bytes"))}", style = MaterialTheme.typography.labelLarge)
                Text("${sample.getLong("operations")} 次访问 · ${sample.getLong("payload_bytes")} B\n${sample.getLong("elapsed_ns")} ns · 校验通过", style = MaterialTheme.typography.bodySmall)
                if (cell != null) Text("实际 CPU ${sample.optJSONArray("observed_start_cpus")} → ${sample.optJSONArray("observed_end_cpus")}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
internal fun ReportActions(report: JSONObject, onExport: (JSONObject) -> Unit) {
    val context = LocalContext.current
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        FilledTonalButton(onClick = {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("BenchBridge result", resultText(report)))
            Toast.makeText(context, "成绩已复制", Toast.LENGTH_SHORT).show()
        }, modifier = Modifier.weight(1f).testTag("copy_scores")) { Text("复制成绩") }
        FilledTonalButton(onClick = { onExport(report) }, modifier = Modifier.weight(1f)) { Text("导出 JSON") }
    }
}

@Composable
private fun HistoryPage(state: RamUiState, model: RamViewModel, onExport: (JSONObject) -> Unit) {
    val locale = LocalLocale.current.platformLocale
    LazyColumn(Modifier.widthIn(max = 760.dp).fillMaxSize().testTag("history_page"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { PageTitle("测试历史", "保留原始轮次与测量条件") }
        val selected = state.selectedHistory
        if (selected == null) {
            if (state.history.isEmpty()) item { SectionCard("还没有测试记录") { Text("完成测试后，结果会保存在这里。") } }
            state.history.forEach { report -> item {
                Card(onClick = { model.selectHistory(report) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val disk = report.optString("kind") == "storage_benchmark"
                        val compute=report.optString("kind")=="compute_benchmark"
                        Text("${if(compute)"GPGPU"else if (disk) "ROM" else "RAM"} · ${RamResults.stateLabel(report.optString("state"))}", style = MaterialTheme.typography.titleMedium)
                        Text(SimpleDateFormat("MM-dd HH:mm:ss", locale).format(Date(report.optLong("started_at_ms"))), style = MaterialTheme.typography.bodySmall)
                        val config = report.getJSONObject("config")
                        Text(if(compute)ComputeConfig.fromJson(config.toString()).summary else if (disk) StorageConfig.fromJson(config.toString()).summary
                            else RamConfig.fromJson(config.toString()).summary, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } }
        } else {
            item { TextButton(onClick = { model.selectHistory(null) }) { Text("返回历史列表") } }
            if (selected.optString("kind") == "storage_benchmark") {
                item { StorageSummary(selected, false, model) }
                item { ReportActions(selected, onExport) }
                item { StorageMatrix(selected, null) }
            } else {
                item { RunProgress(selected, false) }
                item { SectionCard("本次参数") { Text(RamConfig.fromJson(selected.getJSONObject("config").toString()).summary,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("ram_result_config")) } }
                item { ReportActions(selected, onExport) }
                val kinds = selected.getJSONObject("config").getJSONArray("kinds")
                (0 until kinds.length()).forEach { index -> item { ResultCard(selected, RamKind.entries.first { it.code == kinds.getInt(index) }) } }
            }
        }
    }
}

@Composable
internal fun PageTitle(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable
internal fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}
@Composable
internal fun ErrorCard(error: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Text(error, Modifier.padding(18.dp), color = MaterialTheme.colorScheme.onErrorContainer)
    }
}
