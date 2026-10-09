package io.benchbridge.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.benchbridge.app.BenchmarkFormat
import io.benchbridge.app.ram.*
import io.benchbridge.app.storage.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RamSettingsPage(state: RamUiState, model: RamViewModel) {
    val config = state.config
    val allowed = state.capabilities?.optInt("allowed_cpus", 1) ?: 1
    val preset = config.recognizedPresetId()
    val edit: (RamConfig) -> Unit = model::configure
    LazyColumn(Modifier.fillMaxSize().testTag("ram_settings_page"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionCard("测试配置") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(preset == "cache-curve-standard-v3", { model.configure(RamConfig.aida64()) }, { Text("缓存曲线 + RAM") }, modifier = Modifier.testTag("ram_aida"))
                    FilterChip(preset == "cache-curve-quick-v1", { model.configure(RamConfig.matrixQuick().resolveThreads(allowed)) }, { Text("曲线 + RAM 快测") }, modifier = Modifier.testTag("ram_matrix_quick"))
                    FilterChip(preset == "ram-quick-dev-v1", { model.configure(RamConfig.quick()) }, { Text("RAM 六项快测") }, modifier = Modifier.testTag("ram_quick"))
                }
                if(config.curveMode) {
                    Text("曲线最大工作集",style=MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        listOf(64,128,256).forEach { mib->FilterChip(config.curveMaxMiB==mib,{edit(config.copy(curveMaxMiB=mib))},{Text("$mib MiB")}) }
                    }
                    Text("每倍容量 ${config.curveSteps} 个间隔 · 正反两遍 · 自动复核",style=MaterialTheme.typography.bodySmall)
                    Row {
                        Text("同时测试 RAM 四项（先测）",Modifier.weight(1f))
                        Switch(config.curveIncludeRam,{edit(config.copy(curveIncludeRam=it))},modifier=Modifier.testTag("curve_include_ram"))
                    }
                }
                if(config.scoredLevels.isNotEmpty()) {
                if (config.hasBandwidth) {
                    Text("${if (config.cacheMatrix) "RAM " else ""}带宽 · 总工作集", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (listOf(16, 64, 128, 256, 512) + config.workingSetMiB).distinct().sorted().forEach { mib ->
                            FilterChip(config.workingSetMiB == mib, { edit(config.copy(workingSetMiB = mib)) }, { Text(BenchmarkFormat.mib(mib)) },
                                modifier = Modifier.testTag("ram_working_$mib"))
                        }
                    }
                    Text("带宽 · 线程数", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(config.automaticThreads, { edit(config.copy(automaticThreads = true)) },
                            { Text(if (config.cacheMatrix) "自动校准" else "自动 (${allowed.coerceIn(1, 16)})") }, modifier = Modifier.testTag("ram_threads_auto"))
                        (listOf(1, 2, 4, 8, 16).filter { it <= allowed } + minOf(allowed, 16) + config.threads).distinct().sorted().forEach { count ->
                            FilterChip(!config.automaticThreads && config.threads == count, { edit(config.copy(threads = count, automaticThreads = false)) },
                                { Text("$count 线程") }, modifier = Modifier.testTag("ram_threads_$count"))
                        }
                    }
                    Text("带宽 · 每项重复次数", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (listOf(1, 3, 5) + config.rounds).distinct().sorted().forEach { count ->
                            FilterChip(config.rounds == count, { edit(config.copy(rounds = count)) }, { Text("$count 次") },
                                modifier = Modifier.testTag("ram_bandwidth_rounds_$count"))
                        }
                    }
                }
                if (config.hasLatency) {
                    HorizontalDivider()
                    Text("${if (config.cacheMatrix) "RAM " else ""}延迟 · 总工作集（固定 1 线程）", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (listOf(8, 32, 64, 128, 256) + config.latencySetMiB).distinct().sorted().forEach { mib ->
                            FilterChip(config.latencySetMiB == mib, { edit(config.copy(latencySetMiB = mib)) }, { Text(BenchmarkFormat.mib(mib)) },
                                modifier = Modifier.testTag("ram_latency_$mib"))
                        }
                    }
                    Text("延迟 · 重复次数", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (listOf(1, 3, 5) + config.latencyRounds).distinct().sorted().forEach { count ->
                            FilterChip(config.latencyRounds == count, { edit(config.copy(latencyRounds = count)) }, { Text("$count 次") },
                                modifier = Modifier.testTag("ram_latency_rounds_$count"))
                        }
                    }
                }
                HorizontalDivider()
                Text("${if (config.cacheMatrix) "RAM " else ""}每轮测量时间", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (listOf(1000, 3000, 5000) + config.durationMs).distinct().sorted().forEach { duration ->
                        FilterChip(config.durationMs == duration, { edit(config.copy(durationMs = duration)) }, { Text(BenchmarkFormat.duration(duration)) },
                            modifier = Modifier.testTag("ram_duration_$duration"))
                    }
                }
                }
                Text(config.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("ram_config_summary"))
                state.capabilities?.let { caps ->
                    Text("${if(config.curveMode)"曲线工作集上限 ${config.curveMaxMiB} MiB，按内存预算调整"else "内存用量约 ${config.estimatedBytes() / 1048576} MiB"} · ${caps.optInt("allowed_cpus")} 个可用 CPU",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
                if(config.scoredLevels.isNotEmpty())FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RamKind.entries.filter { !config.cacheMatrix || it.code in MemoryPlanner.columns }.forEach { kind ->
                        FilterChip(kind.code in config.kinds, {
                            edit(config.copy(kinds = if (kind.code in config.kinds) config.kinds - kind.code else (config.kinds + kind.code).sorted()))
                        }, { Text(kind.title) })
                    }
                }
            }
        }
        state.error?.let { item { ErrorCard(it) } }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StorageSettingsPage(state: RamUiState, model: RamViewModel) {
    val config = state.storageConfig
    var advanced by rememberSaveable { mutableStateOf(false) }
    var editingCaseId by rememberSaveable { mutableStateOf("") }
    val editingCase = config.cases.firstOrNull { it.id == editingCaseId } ?: config.cases.firstOrNull()
    val preset = config.recognizedPresetId()
    val edit: (StorageConfig) -> Unit = model::configureStorage
    val editCase: (StorageCase) -> Unit = { next ->
        editingCase?.let { if (model.replaceStorageCase(it.id, next)) editingCaseId = next.canonical().id }
    }
    LazyColumn(Modifier.fillMaxSize().testTag("storage_settings_page"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionCard("测试参数") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(preset == "diskmark-default-v1", { model.configureStorage(StorageConfig()) }, { Text("DiskMark 默认") }, modifier = Modifier.testTag("storage_default"))
                        FilterChip(preset == "diskmark-nvme-v1", { model.configureStorage(StorageConfig.nvme()) }, { Text("NVMe 参数") }, modifier = Modifier.testTag("storage_nvme"))
                        FilterChip(preset == "storage-quick-v1", { model.configureStorage(StorageConfig.quick()) }, { Text("快速测试") }, modifier = Modifier.testTag("storage_quick"))
                    }
                    Text("测试文件大小", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (listOf(64, 256, 1024, 4096) + config.fileMiB).distinct().sorted().forEach { size ->
                            FilterChip(config.fileMiB == size, { edit(config.copy(fileMiB = size,
                                writeBudgetMiB = if (config.writeBudgetMiB == 0) 0 else maxOf(size, config.writeBudgetMiB))) },
                                { Text(BenchmarkFormat.mib(size)) }, modifier = Modifier.testTag("storage_file_$size"))
                        }
                    }
                    Text("每项每方向的重复次数", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (listOf(1, 3, 5, 9) + config.rounds).distinct().sorted().forEach { count ->
                            FilterChip(config.rounds == count, { edit(config.copy(rounds = count)) }, { Text("$count 次") }, modifier = Modifier.testTag("storage_rounds_$count"))
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("全部" to listOf("read", "write"), "只读" to listOf("read"), "只写" to listOf("write")).forEach { (title, directions) ->
                            FilterChip(config.directions.toSet() == directions.toSet(), { edit(config.copy(directions = directions)) }, { Text(title) })
                        }
                    }
                    Text(config.summary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("storage_config_summary"))
                    TextButton(onClick = { advanced = !advanced }, modifier = Modifier.testTag("storage_advanced")) { Text(if (advanced) "收起高级参数" else "高级参数与单项测试") }
                    if (advanced) {
                        Text("每轮时长", style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            (listOf(600, 1000, 3000, 5000, 10000) + config.durationMs).distinct().sorted().forEach { ms ->
                                FilterChip(config.durationMs == ms, { edit(config.copy(durationMs = ms)) }, { Text(BenchmarkFormat.duration(ms)) }, modifier = Modifier.testTag("storage_duration_$ms"))
                            }
                        }
                        Text("每项首轮预热", style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            (listOf(0, 100, 1000, 5000) + config.warmupMs).distinct().sorted().forEach { ms ->
                                FilterChip(config.warmupMs == ms, { edit(config.copy(warmupMs = ms)) }, { Text(BenchmarkFormat.duration(ms)) })
                            }
                        }
                        Text("项目间隔", style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            (listOf(0, 100, 1000, 5000) + config.intervalMs).distinct().sorted().forEach { ms ->
                                FilterChip(config.intervalMs == ms, { edit(config.copy(intervalMs = ms)) }, { Text(BenchmarkFormat.duration(ms)) })
                            }
                        }
                        Text("缓存模式", style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(config.direct, { edit(config.copy(direct = true)) }, { Text("Direct") })
                            FilterChip(!config.direct, { edit(config.copy(direct = false)) }, { Text("Buffered") })
                        }
                        Text(if (config.direct) "Direct 绕过文件页缓存。" else "Buffered 包含文件页缓存；结果独立记录。", style = MaterialTheme.typography.bodySmall)
                        Text("测试项目", style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            (StorageCase.standard() + StorageCase.nvme() + config.cases).distinctBy { it.id }.forEach { case ->
                                FilterChip(config.cases.any { it.id == case.id }, {
                                    edit(config.copy(cases = if (config.cases.any { it.id == case.id }) config.cases.filterNot { it.id == case.id } else config.cases + case))
                                }, { Text("${case.title} ${case.subtitle}") })
                            }
                        }
                        HorizontalDivider()
                        Text("编辑项目", style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            config.cases.forEach { case ->
                                FilterChip(case.id == editingCase?.id, { editingCaseId = case.id }, { Text("${case.title} ${case.subtitle}") },
                                    modifier = Modifier.testTag("storage_edit_${case.id}"))
                            }
                        }
                        editingCase?.let { case ->
                            Text("${case.title} · ${case.subtitle}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("storage_editor_summary"))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(case.random, { editCase(case.copy(random = true)) }, { Text("随机") })
                                FilterChip(!case.random, { editCase(case.copy(random = false)) }, { Text("顺序") })
                            }
                            Text("每次 I/O 块大小", style = MaterialTheme.typography.labelLarge)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                (listOf(4, 16, 128, 1024) + case.blockKiB).distinct().sorted().forEach { block ->
                                    FilterChip(case.blockKiB == block, { editCase(case.copy(blockKiB = block)) }, { Text(BenchmarkFormat.kib(block)) },
                                        modifier = Modifier.testTag("storage_block_$block"))
                                }
                            }
                            Text("每线程队列深度 Q", style = MaterialTheme.typography.labelLarge)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                (listOf(1, 2, 4, 8, 16, 32, 64) + case.queue).distinct().sorted().forEach { q ->
                                    FilterChip(case.queue == q, { editCase(case.copy(queue = q)) }, { Text("Q$q") }, modifier = Modifier.testTag("storage_queue_$q"))
                                }
                            }
                            Text("线程数 T", style = MaterialTheme.typography.labelLarge)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                (listOf(1, 2, 4, 8, 16) + case.threads).distinct().sorted().forEach { t ->
                                    FilterChip(case.threads == t, { editCase(case.copy(threads = t)) }, { Text("T$t") }, modifier = Modifier.testTag("storage_threads_$t"))
                                }
                            }
                            OutlinedButton(onClick = { edit(config.copy(cases = listOf(case))) }) { Text("只测试此项") }
                        }
                        Text("累计写入上限", style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            (listOf(0, 4096, 16384, 102400, 262144) + config.writeBudgetMiB).distinct().sorted().forEach { mib ->
                                FilterChip(config.writeBudgetMiB == mib, { edit(config.copy(writeBudgetMiB = mib)) },
                                    { Text(if (mib == 0) "按时长完成" else BenchmarkFormat.mib(mib)) }, enabled = mib == 0 || mib >= config.fileMiB,
                                    modifier = Modifier.testTag("storage_budget_$mib"))
                            }
                        }
                        Text("默认按次数和时长完成。设定上限后，初始化、预热和正式测试均计入累计写入；测试始终复用一个文件。", style = MaterialTheme.typography.bodySmall)
                    }
            }
        }
        state.error?.let { item { ErrorCard(it) } }
    }
}
