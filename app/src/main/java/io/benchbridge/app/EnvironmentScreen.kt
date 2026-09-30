package io.benchbridge.app

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
                                "B",
                                Modifier.padding(horizontal = 15.dp, vertical = 9.dp),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("BenchBridge", style = MaterialTheme.typography.titleLarge)
                            Text(
                                "设备信息",
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
                        Text(if (state.running) "正在检查…" else "重新检查环境")
                    }
                }
                item {
                    InfoCard("当前设备") {
                        InfoRow("制造商", state.device.manufacturer)
                        InfoRow("设备型号", state.device.model)
                        InfoRow("系统", "Android ${state.device.androidVersion} · API ${state.device.apiLevel}")
                        InfoRow("SoC 原始值", state.device.socModel)
                        InfoRow("系统 ABI", state.device.supportedAbis.joinToString(", "))
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
                                Text("诊断信息", fontWeight = FontWeight.SemiBold)
                                Text(error, style = MaterialTheme.typography.bodySmall)
                                Text("可以复制诊断信息，保留具体错误以便排查。", style = MaterialTheme.typography.bodySmall)
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
                        Text("复制诊断信息")
                    }
                }
                item {
                    Text(
                        "${BuildConfig.VERSION_NAME}  ·  ${state.checkedAt?.let { "最近检查 $it" } ?: "等待检查"}",
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
            Text("ANDROID / NATIVE", style = MaterialTheme.typography.labelMedium, color = Color(0xFFBDCAFF))
            Text(
                when {
                    state.running -> "正在连接原生引擎"
                    passed -> "原生引擎已就绪"
                    failed -> "环境检查未通过"
                    else -> "准备检查环境"
                },
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                when {
                    state.running -> "正在检查 JNI、系统页大小与基础内存读写。"
                    passed -> "JNI、内存校验与时钟检查通过。"
                    failed -> "请查看下方诊断信息。失败项会保留具体原因。"
                    else -> "运行一次轻量检查，确认应用与 C++ 库能够协同工作。"
                },
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
    InfoCard("原生自检") {
        InfoRow("进程架构", report.abi)
        InfoRow("系统页大小", "${report.pageSizeBytes} B")
        InfoRow("读写校验", if (report.memoryOk) "通过 · ${report.checkedBytes / 1024} KiB" else "未通过")
        InfoRow("单调时钟", if (report.clockOk) "通过" else "未通过")
        InfoRow("C++ 标准", if (report.cppStandard >= 202002L) "C++20" else report.cppStandard.toString())
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "收起构建信息" else "查看构建信息")
        }
        AnimatedVisibility(expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HorizontalDivider()
                InfoRow("NDK", report.ndkVersion)
                InfoRow("CMake", report.cmakeVersion)
                InfoRow("编译器", report.compiler)
                InfoRow("校验摘要", report.checksum)
                Text(
                    "这是小规模读写正确性检查，不产生带宽、延迟或磁盘性能分数。",
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
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    // 值按纵向排列，以适应小屏幕及较大的系统字体。
    // Stack values vertically to accommodate small displays and large system fonts.
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (label in setOf("校验摘要", "编译器")) FontFamily.Monospace else FontFamily.Default,
        )
    }
}

private fun copyDiagnostics(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("BenchBridge environment check", text))
    Toast.makeText(context, "诊断信息已复制", Toast.LENGTH_SHORT).show()
}
