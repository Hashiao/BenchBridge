package io.benchbridge.app.ui

import io.benchbridge.app.i18n.L10n

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
            SectionCard(L10n.t("m_58b1c516d3cd")) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(preset == "cache-curve-standard-v3", { model.configure(RamConfig.aida64()) }, { Text(L10n.t("m_f0e1147ac22e")) }, modifier = Modifier.testTag("ram_aida"))
                    FilterChip(preset == "cache-curve-quick-v1", { model.configure(RamConfig.matrixQuick().resolveThreads(allowed)) }, { Text(L10n.t("m_2c54f1904d61")) }, modifier = Modifier.testTag("ram_matrix_quick"))
                    FilterChip(preset == "ram-quick-dev-v1", { model.configure(RamConfig.quick()) }, { Text(L10n.t("m_a82cebdb7836")) }, modifier = Modifier.testTag("ram_quick"))
                }
                if(config.curveMode) {
                    Text(L10n.t("m_4c1ba2afa2fb"),style=MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        listOf(64,128,256).forEach { mib->FilterChip(config.curveMaxMiB==mib,{edit(config.copy(curveMaxMiB=mib))},{Text(L10n.display("$mib MiB"))}) }
                    }
                    Row {
                        Text(L10n.t("m_e908350fc3c9"),Modifier.weight(1f))
                        Switch(config.singleCurveSample,{edit(config.copy(curveProtocol=if(it)CacheProbe.FAST_METHOD else CacheProbe.METHOD))})
                    }
                    Text(L10n.t("m_6596d1311f2d", config.curveSteps)+if(config.singleCurveSample)L10n.t("m_c9e4e2603288")else L10n.t("m_3ae09f36e468"),style=MaterialTheme.typography.bodySmall)
                    Row {
                        Text(L10n.t("m_aa327c632b7c"),Modifier.weight(1f))
                        Switch(config.curveIncludeRam,{edit(config.copy(curveIncludeRam=it))},modifier=Modifier.testTag("curve_include_ram"))
                    }
                }
                if(config.scoredLevels.isNotEmpty()) {
                if (config.hasBandwidth) {
                    Text(L10n.t("m_d04056bb7f1c", if (config.cacheMatrix) "RAM " else ""), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (listOf(16, 64, 128, 256, 512) + config.workingSetMiB).distinct().sorted().forEach { mib ->
                            FilterChip(config.workingSetMiB == mib, { edit(config.copy(workingSetMiB = mib)) }, { Text(L10n.display(BenchmarkFormat.mib(mib))) },
                                modifier = Modifier.testTag("ram_working_$mib"))
                        }
                    }
                    Text(L10n.t("m_25903839eabd"), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(config.automaticThreads, { edit(config.copy(automaticThreads = true)) },
                            { Text(L10n.display(if (config.cacheMatrix) L10n.t("m_b10b282b616f") else L10n.t("m_1787218c5908", allowed.coerceIn(1, 16)))) }, modifier = Modifier.testTag("ram_threads_auto"))
                        (listOf(1, 2, 4, 8, 16).filter { it <= allowed } + minOf(allowed, 16) + config.threads).distinct().sorted().forEach { count ->
                            FilterChip(!config.automaticThreads && config.threads == count, { edit(config.copy(threads = count, automaticThreads = false)) },
                                { Text(L10n.t("m_a5c3ffddd0b1", count)) }, modifier = Modifier.testTag("ram_threads_$count"))
                        }
                    }
                    Text(L10n.t("m_91ecc525da78"), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (listOf(1, 3, 5) + config.rounds).distinct().sorted().forEach { count ->
                            FilterChip(config.rounds == count, { edit(config.copy(rounds = count)) }, { Text(L10n.t("m_2aa79928fce8", count)) },
                                modifier = Modifier.testTag("ram_bandwidth_rounds_$count"))
                        }
                    }
                }
                if (config.hasLatency) {
                    HorizontalDivider()
                    Text(L10n.t("m_1dbecf7ebb33", if (config.cacheMatrix) "RAM " else ""), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (listOf(8, 32, 64, 128, 256) + config.latencySetMiB).distinct().sorted().forEach { mib ->
                            FilterChip(config.latencySetMiB == mib, { edit(config.copy(latencySetMiB = mib)) }, { Text(L10n.display(BenchmarkFormat.mib(mib))) },
                                modifier = Modifier.testTag("ram_latency_$mib"))
                        }
                    }
                    Text(L10n.t("m_9cb715e053fb"), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (listOf(1, 3, 5) + config.latencyRounds).distinct().sorted().forEach { count ->
                            FilterChip(config.latencyRounds == count, { edit(config.copy(latencyRounds = count)) }, { Text(L10n.t("m_2aa79928fce8", count)) },
                                modifier = Modifier.testTag("ram_latency_rounds_$count"))
                        }
                    }
                }
                HorizontalDivider()
                Text(L10n.t("m_c181ac47bb1d", if (config.cacheMatrix) "RAM " else ""), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (listOf(1000, 3000, 5000) + config.durationMs).distinct().sorted().forEach { duration ->
                        FilterChip(config.durationMs == duration, { edit(config.copy(durationMs = duration)) }, { Text(L10n.display(BenchmarkFormat.duration(duration))) },
                            modifier = Modifier.testTag("ram_duration_$duration"))
                    }
                }
                }
                Text(L10n.display(config.summary), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("ram_config_summary"))
                state.capabilities?.let { caps ->
                    Text(L10n.t("m_ee0d3836d307", if(config.curveMode)L10n.t("m_147c26c14313", config.curveMaxMiB)else L10n.t("m_8debe502df51", config.estimatedBytes() / 1048576), caps.optInt("allowed_cpus")),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
                if(config.scoredLevels.isNotEmpty())FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RamKind.entries.filter { !config.cacheMatrix || it.code in MemoryPlanner.columns }.forEach { kind ->
                        FilterChip(kind.code in config.kinds, {
                            edit(config.copy(kinds = if (kind.code in config.kinds) config.kinds - kind.code else (config.kinds + kind.code).sorted()))
                        }, { Text(L10n.display(kind.title)) })
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
            SectionCard(L10n.t("m_dd3ec4711a76")) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(preset == "diskmark-default-v1", { model.configureStorage(StorageConfig()) }, { Text(L10n.t("m_cdc46680960b")) }, modifier = Modifier.testTag("storage_default"))
                        FilterChip(preset == "diskmark-nvme-v1", { model.configureStorage(StorageConfig.nvme()) }, { Text(L10n.t("m_cf277021bb1b")) }, modifier = Modifier.testTag("storage_nvme"))
                        FilterChip(preset == "storage-quick-v1", { model.configureStorage(StorageConfig.quick()) }, { Text(L10n.t("m_fa394156117e")) }, modifier = Modifier.testTag("storage_quick"))
                    }
                    Text(L10n.t("m_da5920919577"), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (listOf(64, 256, 1024, 4096) + config.fileMiB).distinct().sorted().forEach { size ->
                            FilterChip(config.fileMiB == size, { edit(config.copy(fileMiB = size,
                                writeBudgetMiB = if (config.writeBudgetMiB == 0) 0 else maxOf(size, config.writeBudgetMiB))) },
                                { Text(L10n.display(BenchmarkFormat.mib(size))) }, modifier = Modifier.testTag("storage_file_$size"))
                        }
                    }
                    Text(L10n.t("m_2d167971c06b"), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (listOf(1, 3, 5, 9) + config.rounds).distinct().sorted().forEach { count ->
                            FilterChip(config.rounds == count, { edit(config.copy(rounds = count)) }, { Text(L10n.t("m_2aa79928fce8", count)) }, modifier = Modifier.testTag("storage_rounds_$count"))
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(L10n.t("m_5c55a67935af") to listOf("read", "write"), L10n.t("m_3b5ec3533b0e") to listOf("read"), L10n.t("m_7f4605501fc3") to listOf("write")).forEach { (title, directions) ->
                            FilterChip(config.directions.toSet() == directions.toSet(), { edit(config.copy(directions = directions)) }, { Text(L10n.display(title)) })
                        }
                    }
                    Text(L10n.display(config.summary), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("storage_config_summary"))
                    TextButton(onClick = { advanced = !advanced }, modifier = Modifier.testTag("storage_advanced")) { Text(L10n.display(if (advanced) L10n.t("m_f8af6f45840a") else L10n.t("m_530922477172"))) }
                    if (advanced) {
                        Text(L10n.t("m_66767f440e23"), style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            (listOf(600, 1000, 3000, 5000, 10000) + config.durationMs).distinct().sorted().forEach { ms ->
                                FilterChip(config.durationMs == ms, { edit(config.copy(durationMs = ms)) }, { Text(L10n.display(BenchmarkFormat.duration(ms))) }, modifier = Modifier.testTag("storage_duration_$ms"))
                            }
                        }
                        Text(L10n.t("m_be215559e2d4"), style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            (listOf(0, 100, 1000, 5000) + config.warmupMs).distinct().sorted().forEach { ms ->
                                FilterChip(config.warmupMs == ms, { edit(config.copy(warmupMs = ms)) }, { Text(L10n.display(BenchmarkFormat.duration(ms))) })
                            }
                        }
                        Text(L10n.t("m_c38cab8cb048"), style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            (listOf(0, 100, 1000, 5000) + config.intervalMs).distinct().sorted().forEach { ms ->
                                FilterChip(config.intervalMs == ms, { edit(config.copy(intervalMs = ms)) }, { Text(L10n.display(BenchmarkFormat.duration(ms))) })
                            }
                        }
                        Text(L10n.t("m_433071b8ffe4"), style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(config.direct, { edit(config.copy(direct = true)) }, { Text(L10n.display("Direct")) })
                            FilterChip(!config.direct, { edit(config.copy(direct = false)) }, { Text(L10n.display("Buffered")) })
                        }
                        Text(L10n.display(if (config.direct) L10n.t("m_f17d9690f67a") else L10n.t("m_88707137a619")), style = MaterialTheme.typography.bodySmall)
                        Text(L10n.t("m_d67921a192d8"), style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            (StorageCase.standard() + StorageCase.nvme() + config.cases).distinctBy { it.id }.forEach { case ->
                                FilterChip(config.cases.any { it.id == case.id }, {
                                    edit(config.copy(cases = if (config.cases.any { it.id == case.id }) config.cases.filterNot { it.id == case.id } else config.cases + case))
                                }, { Text(L10n.display("${case.title} ${case.subtitle}")) })
                            }
                        }
                        HorizontalDivider()
                        Text(L10n.t("m_577feeefb88d"), style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            config.cases.forEach { case ->
                                FilterChip(case.id == editingCase?.id, { editingCaseId = case.id }, { Text(L10n.display("${case.title} ${case.subtitle}")) },
                                    modifier = Modifier.testTag("storage_edit_${case.id}"))
                            }
                        }
                        editingCase?.let { case ->
                            Text(L10n.display("${case.title} · ${case.subtitle}"), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("storage_editor_summary"))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(case.random, { editCase(case.copy(random = true)) }, { Text(L10n.t("m_75ca4f921437")) })
                                FilterChip(!case.random, { editCase(case.copy(random = false)) }, { Text(L10n.t("m_9249ca6c2aab")) })
                            }
                            Text(L10n.t("m_a82b2eb1324e"), style = MaterialTheme.typography.labelLarge)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                (listOf(4, 16, 128, 1024) + case.blockKiB).distinct().sorted().forEach { block ->
                                    FilterChip(case.blockKiB == block, { editCase(case.copy(blockKiB = block)) }, { Text(L10n.display(BenchmarkFormat.kib(block))) },
                                        modifier = Modifier.testTag("storage_block_$block"))
                                }
                            }
                            Text(L10n.t("m_6f196a7029cb"), style = MaterialTheme.typography.labelLarge)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                (listOf(1, 2, 4, 8, 16, 32, 64) + case.queue).distinct().sorted().forEach { q ->
                                    FilterChip(case.queue == q, { editCase(case.copy(queue = q)) }, { Text(L10n.display("Q$q")) }, modifier = Modifier.testTag("storage_queue_$q"))
                                }
                            }
                            Text(L10n.t("m_56e542017a0f"), style = MaterialTheme.typography.labelLarge)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                (listOf(1, 2, 4, 8, 16) + case.threads).distinct().sorted().forEach { t ->
                                    FilterChip(case.threads == t, { editCase(case.copy(threads = t)) }, { Text(L10n.display("T$t")) }, modifier = Modifier.testTag("storage_threads_$t"))
                                }
                            }
                            OutlinedButton(onClick = { edit(config.copy(cases = listOf(case))) }) { Text(L10n.t("m_ad0aac9d9ee8")) }
                        }
                        Text(L10n.t("m_986626b6f51c"), style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            (listOf(0, 4096, 16384, 102400, 262144) + config.writeBudgetMiB).distinct().sorted().forEach { mib ->
                                FilterChip(config.writeBudgetMiB == mib, { edit(config.copy(writeBudgetMiB = mib)) },
                                    { Text(L10n.display(if (mib == 0) L10n.t("m_e355b1a49e05") else BenchmarkFormat.mib(mib))) }, enabled = mib == 0 || mib >= config.fileMiB,
                                    modifier = Modifier.testTag("storage_budget_$mib"))
                            }
                        }
                        Text(L10n.t("m_8bd3bb068f32"), style = MaterialTheme.typography.bodySmall)
                    }
            }
        }
        state.error?.let { item { ErrorCard(it) } }
    }
}
