package io.benchbridge.app.ui

import io.benchbridge.app.i18n.L10n

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import io.benchbridge.app.BuildConfig
import io.benchbridge.app.R
import io.benchbridge.app.compute.*
import io.benchbridge.app.ram.*
import java.util.Locale
import org.json.JSONObject

@Composable
internal fun ComputeDashboard(report:JSONObject?,config:ComputeConfig,running:Boolean,
                              onSettings:(()->Unit)?,onDetails:(()->Unit)?,onBack:(()->Unit)?=null,error:String?=null){
    val frozen=report?.optJSONObject("config")?.let { ComputeConfig.fromJson(it.toString()) }?:config
    val completed=report?.optInt("completed_rounds")?:0
    val total=report?.optInt("total_rounds")?:frozen.totalRounds
    BoxWithConstraints(Modifier.fillMaxSize().testTag("compute_page")){
        val compact=maxHeight<560.dp
        Column(Modifier.fillMaxSize().padding(horizontal=16.dp,vertical=if(compact)4.dp else 8.dp),verticalArrangement=Arrangement.spacedBy(if(compact)3.dp else 6.dp)){
            Row(verticalAlignment=Alignment.CenterVertically){
                if(onBack!=null)TextButton(onClick=onBack,contentPadding=PaddingValues(0.dp)){Text(L10n.t("m_572cf45ba436"))}
                Text(L10n.display("GPGPU"),Modifier.weight(1f),style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
                if(onDetails!=null)TextButton(onClick=onDetails,enabled=!running,modifier=Modifier.testTag("compute_details")){Text(L10n.t("m_979a332955c8"))}
                if(onSettings!=null)IconButton(onClick=onSettings,enabled=!running,modifier=Modifier.testTag("compute_settings")){Icon(painterResource(R.drawable.ic_settings),L10n.t("m_1a948ecc9480"))}
            }
            Text(L10n.display(report?.optJSONObject("device")?.optString("model")?:Build.MODEL),style=MaterialTheme.typography.labelMedium,maxLines=1,overflow=TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                Text(L10n.t("m_0c3446e92621", frozen.targets.joinToString(" + ") { it.uppercase() }, frozen.rounds),style=MaterialTheme.typography.labelSmall,maxLines=1)
                Text(L10n.display(if(report==null)L10n.t("m_1bd1893c0900")else L10n.t("m_9c12ee84a862", completed, total)),style=MaterialTheme.typography.labelSmall)
            }
            val status=report?.optString("state")?:"READY"
            val stateText=if(running){
                val kind=ComputeKind.entries.getOrNull(report?.optInt("current_kind",-1)?:-1)
                if(kind==null)L10n.t("m_871be2654869")else"${report?.optString("current_target")?.uppercase()} · ${kind.title} · ${report?.optInt("current_round")} / ${frozen.rounds}"
            }else RamResults.stateLabel(status)
            Text(L10n.display(stateText),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary,maxLines=1,
                modifier=Modifier.testTag("compute_state_$status"))
            Surface(Modifier.fillMaxWidth().weight(1f).testTag("compute_board"),shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surfaceContainerLow){
                Column(Modifier.fillMaxSize().padding(horizontal=10.dp,vertical=if(compact)3.dp else 7.dp)){
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(bottom=3.dp)){
                        Text(L10n.t("m_79f326be4409"),Modifier.weight(1.25f),fontSize=11.sp)
                        Text(L10n.display("CPU"),Modifier.weight(1f).padding(start=6.dp,end=10.dp),fontSize=12.sp,textAlign=TextAlign.End,fontWeight=FontWeight.SemiBold)
                        VerticalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                        Text(L10n.display("GPU"),Modifier.weight(1f).padding(start=10.dp,end=4.dp),fontSize=12.sp,textAlign=TextAlign.End,fontWeight=FontWeight.SemiBold)
                    }
                    ComputeKind.entries.forEach { kind ->
                        HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                        Row(Modifier.fillMaxWidth().weight(1f).testTag("compute_row_${kind.code}"),verticalAlignment=Alignment.CenterVertically){
                            Column(Modifier.weight(1.25f)){
                                if(compact)BasicText(L10n.display("${kind.title} · ${kind.unit}"),maxLines=1,
                                    style=MaterialTheme.typography.labelSmall.copy(lineHeight=TextUnit.Unspecified,color=MaterialTheme.colorScheme.onSurface),autoSize=TextAutoSize.StepBased(7.sp,10.sp))
                                else {Text(L10n.display(kind.title),fontSize=11.sp,lineHeight=14.sp,maxLines=1);Text(L10n.display(kind.unit),fontSize=8.sp,lineHeight=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                            }
                            listOf("cpu","gpu").forEach { target ->
                                if(target=="gpu")VerticalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                                val score=report?.let { ComputeResults.median(it,kind.code,target) }
                                val columnPadding=if(target=="cpu")PaddingValues(start=6.dp,end=10.dp)else PaddingValues(start=10.dp,end=4.dp)
                                Box(Modifier.weight(1f).fillMaxHeight().padding(columnPadding),contentAlignment=Alignment.CenterEnd){
                                    BasicText(L10n.display(score?.let { "%.2f".format(Locale.US,it) }?:"—"),Modifier.fillMaxWidth().testTag("compute_${target}_${kind.code}"),maxLines=1,
                                        style=MaterialTheme.typography.titleMedium.copy(fontWeight=FontWeight.SemiBold,textAlign=TextAlign.End,lineHeight=TextUnit.Unspecified,
                                            color=if(score==null)MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary),
                                        autoSize=TextAutoSize.StepBased(8.sp,if(compact)14.sp else 17.sp))
                                }
                            }
                        }
                    }
                }
            }
            if(status=="FAILED"||status=="INTERRUPTED")Text(L10n.t("m_7a15584f2506"),fontSize=10.sp,color=MaterialTheme.colorScheme.error,maxLines=1)
            else if(!error.isNullOrBlank())Text(L10n.display(when{error.contains(L10n.t("m_7d8f8c37ec78"),ignoreCase=true)||error.contains("BUDGET")->L10n.t("m_c8c88b80b218");error.contains(L10n.t("m_b0b7715bce91"),ignoreCase=true)||error.contains("THERMAL")->L10n.t("m_c856ee25bdc6");else->L10n.t("m_a6635ea65640")}),
                fontSize=10.sp,color=MaterialTheme.colorScheme.error,maxLines=2,modifier=Modifier.testTag("compute_error"))
            Text(L10n.display("BenchBridge ${(report?.optString("app_version")?:BuildConfig.VERSION_NAME).substringBefore('-')}"),fontSize=10.sp,lineHeight=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ComputeSettings(state:RamUiState,model:RamViewModel){
    val config=state.computeConfig
    LazyColumn(Modifier.fillMaxSize().testTag("compute_settings_page"),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{SectionCard(L10n.t("m_58b1c516d3cd")){
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                FilterChip(config==ComputeConfig(),{model.configureCompute(ComputeConfig())},{Text(L10n.t("m_3459a2bac987"))},modifier=Modifier.testTag("compute_default"))
                FilterChip(config==ComputeConfig.quick(),{model.configureCompute(ComputeConfig.quick())},{Text(L10n.t("m_fa394156117e"))},modifier=Modifier.testTag("compute_quick"))
            }
            Text(L10n.t("m_f9a36b617bd1"),style=MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                listOf("gpu","cpu").forEach { target -> FilterChip(target in config.targets,{
                    model.configureCompute(config.copy(targets=if(target in config.targets)config.targets-target else (config.targets+target)))
                },{Text(L10n.display(target.uppercase()))},modifier=Modifier.testTag("compute_target_$target")) }
            }
            Text(L10n.t("m_afb2bbd126b5"),style=MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                listOf(1,3,5).forEach { n -> FilterChip(config.rounds==n,{model.configureCompute(config.copy(rounds=n))},{Text(L10n.t("m_2aa79928fce8", n))},modifier=Modifier.testTag("compute_rounds_$n")) }
            }
            Text(L10n.t("m_66767f440e23"),style=MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                listOf(150,1000,3000).forEach { n -> FilterChip(config.durationMs==n,{model.configureCompute(config.copy(durationMs=n))},{Text(L10n.t("m_523fb6184d48", n/1000.0))}) }
            }
            Text(L10n.t("m_290505a7ff35"),style=MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                val allowed=state.computeCapabilities?.optJSONObject("cpu")?.optJSONArray("cpu_ids")?.length()?:state.capabilities?.optInt("allowed_cpus",1)?:1
                listOf(0,1,2,4,8,16).filter { it==0||it<=allowed }.forEach { n -> FilterChip(config.threads==n,{model.configureCompute(config.copy(threads=n))},{Text(L10n.display(if(n==0)L10n.t("m_7eb336e42cb5")else"$n"))}) }
            }
            Text(L10n.t("m_2efc112eea2b"),style=MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                listOf(16,64,128,256).forEach { n -> FilterChip(config.memoryMiB==n,{model.configureCompute(config.copy(memoryMiB=n))},{Text(L10n.display("$n MiB"))}) }
            }
            Text(L10n.t("m_12b76a0dfc05"),style=MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                listOf(256,512,1024).forEach { n -> FilterChip(config.imageSize==n,{model.configureCompute(config.copy(imageSize=n))},{Text(L10n.display("$n × $n"))}) }
            }
            Text(L10n.t("m_d67921a192d8"),style=MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                ComputeKind.entries.forEach { kind -> FilterChip(kind.code in config.kinds,{
                    model.configureCompute(config.copy(kinds=if(kind.code in config.kinds)config.kinds-kind.code else (config.kinds+kind.code).sorted()))
                },{Text(L10n.display(kind.title))}) }
            }
        }}
    }
}

@Composable
internal fun ComputeDetails(report:JSONObject,onExport:(JSONObject)->Unit){
    val config=ComputeConfig.fromJson(report.getJSONObject("config").toString())
    LazyColumn(Modifier.fillMaxSize().testTag("compute_details_page"),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{ReportActions(report,onExport)}
        item{SectionCard(L10n.t("m_d2ad8d08d42a")){
            Text(L10n.display(config.summary));Text(L10n.t("m_9c12ee84a862", report.optInt("completed_rounds"), report.optInt("total_rounds")))
            when(report.getJSONObject("config").optString("protocol")) {
                "gpgpu-v3" -> Text(L10n.t("m_b1df535601aa"),style=MaterialTheme.typography.bodySmall)
                "gpgpu-v2" -> Text(L10n.t("m_64f4ed4fc3a6"),style=MaterialTheme.typography.bodySmall)
                else -> Text(L10n.t("m_e8ebd9af7227"),style=MaterialTheme.typography.bodySmall)
            }
        }}
        ComputeKind.entries.filter { it.code in config.kinds }.forEach { kind -> item{
            SectionCard(kind.title){
                listOf("cpu","gpu").filter(config.targets::contains).forEach { target ->
                    val samples=ComputeResults.samples(report,kind.code,target)
                    val score=ComputeResults.median(report,kind.code,target)
                    val cell=ComputeResults.cell(report,kind.code,target)
                    Text(L10n.display("${target.uppercase()}：${score?.let { "%.2f ${kind.unit}".format(Locale.US,it) }?:"—"}"))
                    if(cell?.optString("state")=="UNSUPPORTED")Text(L10n.t("m_577177a42191"),style=MaterialTheme.typography.bodySmall)
                    else Text(L10n.t("m_9d869777c44d", samples.size, config.rounds),style=MaterialTheme.typography.bodySmall)
                    samples.forEach { sample ->
                        val value=ComputeResults.value(sample)
                        Text(L10n.t("m_fc0aae1e9699", sample.optInt("round"))+if(value.isFinite())"%.2f ${kind.unit}".format(Locale.US,value)else"—",style=MaterialTheme.typography.bodySmall)
                        if(target=="gpu" && sample.optString("protocol")=="gpgpu-v3") {
                            Text(L10n.t("m_8694d9dcd9c4").format(Locale.US,sample.optLong("elapsed_ns")/1e6,sample.optLong("wall_elapsed_ns")/1e6),style=MaterialTheme.typography.bodySmall)
                            if(sample.optString("timer_scope")=="host-submit-fence")Text(L10n.t("m_ad73cb0b8a84"),style=MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }}
    }
}
