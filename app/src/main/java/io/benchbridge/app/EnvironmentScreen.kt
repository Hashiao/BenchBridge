package io.benchbridge.app

import io.benchbridge.app.i18n.L10n

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.benchbridge.app.diagnostics.DiagnosticsState
import io.benchbridge.app.diagnostics.DiagnosticsViewModel
import io.benchbridge.app.diagnostics.NativeReport
import io.benchbridge.app.ui.BenchBridgeTheme

@Composable
internal fun EnvironmentScreen(state: DiagnosticsState, onRefresh: () -> Unit) {
    val context = LocalContext.current
    Scaffold { insets ->
        Box(
            modifier = Modifier.fillMaxSize().padding(insets),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 760.dp).fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            Text(
                                L10n.display("B"),
                                Modifier.padding(horizontal = 15.dp, vertical = 9.dp),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(L10n.display("BenchBridge"), style = MaterialTheme.typography.titleLarge)
                            Text(
                                L10n.t("m_6c07bb6fd4b6"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                item { IntroCard(state) }
                item {
                    Button(
                        onClick = onRefresh,
                        enabled = !state.running,
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(16.dp),
                    ) {
                        Text(L10n.display(if (state.running) L10n.t("m_6b72c3d6855c") else L10n.t("m_47420722b087")))
                    }
                }
                item {
                    InfoCard(L10n.t("m_c68978537cf3")) {
                        InfoRow(L10n.t("m_ba6117467e72"), state.device.manufacturer)
                        InfoRow(L10n.t("m_13e641a3ea20"), state.device.model)
                        InfoRow(L10n.t("m_5b50d7c4b595"), "Android ${state.device.androidVersion} · API ${state.device.apiLevel}")
                        InfoRow(L10n.t("m_4479c4f8d413"), state.device.socModel)
                        InfoRow(L10n.t("m_677b8b1c0b61"), state.device.supportedAbis.joinToString(", "))
                    }
                }
                state.report?.let { report ->
                    item { NativeCard(report) }
                }
                state.error?.let { error ->
                    item {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            ),
                        ) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(L10n.t("m_9167ee8b67db"), fontWeight = FontWeight.SemiBold)
                                Text(L10n.display(error), style = MaterialTheme.typography.bodySmall)
                                Text(L10n.t("m_c6a401870fba"), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                item {
                    FilledTonalButton(
                        onClick = { copyDiagnostics(context, state.toJson()) },
                        enabled = !state.running,
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(16.dp),
                    ) {
                        Text(L10n.t("m_bbce140b7682"))
                    }
                }
                item {
                    Text(
                        L10n.display("${BuildConfig.VERSION_NAME}  ·  ${state.checkedAt?.let { L10n.t("m_52265ff64d72", it) } ?: L10n.t("m_8f31a776d53b")}"),
                        modifier = Modifier.padding(bottom = 12.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun IntroCard(state: DiagnosticsState) {
    val passed = state.report?.passed == true && state.error == null
    val failed = state.error != null || state.report?.passed == false
    Card(shape = RoundedCornerShape(24.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .background(Brush.linearGradient(listOf(Color(0xFF172547), Color(0xFF36438A))))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(L10n.display("ANDROID / NATIVE"), style = MaterialTheme.typography.labelMedium, color = Color(0xFFBDCAFF))
            Text(
                L10n.display(when {
                    state.running -> L10n.t("m_4be0431f56e5")
                    passed -> L10n.t("m_66fee6b57603")
                    failed -> L10n.t("m_6278cc5f2853")
                    else -> L10n.t("m_868f363da18e")
                }),
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                L10n.display(when {
                    state.running -> L10n.t("m_3afcd49f3c9d")
                    passed -> L10n.t("m_b4430cb98add")
                    failed -> L10n.t("m_da8e80927fd7")
                    else -> L10n.t("m_811e5f55f066")
                }),
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFD9E0FF),
            )
            if (state.running) {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = Color(0xFF6DE0C1))
            }
        }
    }
}

@Composable
private fun NativeCard(report: NativeReport) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    InfoCard(L10n.t("m_f980b82932a6")) {
        InfoRow(L10n.t("m_09601b7ba232"), report.abi)
        InfoRow(L10n.t("m_ebbe4d407f7c"), "${report.pageSizeBytes} B")
        InfoRow(L10n.t("m_a400359b1294"), if (report.memoryOk) L10n.t("m_353e9bb125b9", report.checkedBytes / 1024) else L10n.t("m_2875bc4f7fcb"))
        InfoRow(L10n.t("m_533bff0709b3"), if (report.clockOk) L10n.t("m_1e9f2561b7cf") else L10n.t("m_2875bc4f7fcb"))
        InfoRow(L10n.t("m_7afd1844a279"), if (report.cppStandard >= 202002L) "C++20" else report.cppStandard.toString())
        TextButton(onClick = { expanded = !expanded }) {
            Text(L10n.display(if (expanded) L10n.t("m_2c52e4b9cbae") else L10n.t("m_43f06de05be3")))
        }
        AnimatedVisibility(expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HorizontalDivider()
                InfoRow("NDK", report.ndkVersion)
                InfoRow("CMake", report.cmakeVersion)
                InfoRow(L10n.t("m_3cb6da322842"), report.compiler)
                InfoRow(L10n.t("m_38318f63e6d7"), report.checksum)
                Text(
                    L10n.t("m_42c3de4a36e6"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun InfoCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(L10n.display(title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    // 值按纵向排列，以适应小屏幕及较大的系统字体。
    // Stack values vertically to accommodate small displays and large system fonts.
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(L10n.display(label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            L10n.display(value),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (label in setOf(L10n.t("m_38318f63e6d7"), L10n.t("m_3cb6da322842"))) FontFamily.Monospace else FontFamily.Default,
        )
    }
}

private fun copyDiagnostics(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("BenchBridge environment check", text))
    Toast.makeText(context, L10n.t("m_4356bc8e66fd"), Toast.LENGTH_SHORT).show()
}
