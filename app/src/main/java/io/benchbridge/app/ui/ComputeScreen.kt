package io.benchbridge.app.ui

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
                if(onBack!=null)TextButton(onClick=onBack,contentPadding=PaddingValues(0.dp)){Text("返回")}
                Text("GPGPU",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
                if(onDetails!=null)TextButton(onClick=onDetails,enabled=!running,modifier=Modifier.testTag("compute_details")){Text("详情")}
                if(onSettings!=null)IconButton(onClick=onSettings,enabled=!running,modifier=Modifier.testTag("compute_settings")){Icon(painterResource(R.drawable.ic_settings),"测试设置")}
            }
            Text(report?.optJSONObject("device")?.optString("model")?:Build.MODEL,style=MaterialTheme.typography.labelMedium,maxLines=1,overflow=TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                Text("${frozen.targets.joinToString(" + ") { it.uppercase() }} · ${frozen.rounds} 次 · 中位数",style=MaterialTheme.typography.labelSmall,maxLines=1)
                Text(if(report==null)"准备就绪"else"$completed / $total 轮",style=MaterialTheme.typography.labelSmall)
            }
            val status=report?.optString("state")?:"READY"
            val stateText=if(running){
                val kind=ComputeKind.entries.getOrNull(report?.optInt("current_kind",-1)?:-1)
                if(kind==null)"准备中…"else"${report?.optString("current_target")?.uppercase()} · ${kind.title} · ${report?.optInt("current_round")} / ${frozen.rounds}"
            }else RamResults.stateLabel(status)
            Text(stateText,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary,maxLines=1,
                modifier=Modifier.testTag("compute_state_$status"))
            Surface(Modifier.fillMaxWidth().weight(1f).testTag("compute_board"),shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surfaceContainerLow){
                Column(Modifier.fillMaxSize().padding(horizontal=10.dp,vertical=if(compact)3.dp else 7.dp)){
                    Row(Modifier.fillMaxWidth().padding(bottom=3.dp)){
                        Text("项目",Modifier.weight(1.25f),fontSize=11.sp)
                        Text("CPU",Modifier.weight(1f),fontSize=12.sp,textAlign=TextAlign.End,fontWeight=FontWeight.SemiBold)
                        Text("GPU",Modifier.weight(1f),fontSize=12.sp,textAlign=TextAlign.End,fontWeight=FontWeight.SemiBold)
                    }
                    ComputeKind.entries.forEach { kind ->
                        HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                        Row(Modifier.fillMaxWidth().weight(1f).testTag("compute_row_${kind.code}"),verticalAlignment=Alignment.CenterVertically){
                            Column(Modifier.weight(1.25f)){
                                if(compact)BasicText("${kind.title} · ${kind.unit}",maxLines=1,
                                    style=MaterialTheme.typography.labelSmall.copy(lineHeight=TextUnit.Unspecified,color=MaterialTheme.colorScheme.onSurface),autoSize=TextAutoSize.StepBased(7.sp,10.sp))
                                else {Text(kind.title,fontSize=11.sp,lineHeight=14.sp,maxLines=1);Text(kind.unit,fontSize=8.sp,lineHeight=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                            }
                            listOf("cpu","gpu").forEach { target ->
                                val score=report?.let { ComputeResults.median(it,kind.code,target) }
                                Box(Modifier.weight(1f).fillMaxHeight().padding(start=6.dp),contentAlignment=Alignment.CenterEnd){
                                    BasicText(score?.let { "%.2f".format(Locale.US,it) }?:"—",Modifier.fillMaxWidth().testTag("compute_${target}_${kind.code}"),maxLines=1,
                                        style=MaterialTheme.typography.titleMedium.copy(fontWeight=FontWeight.Bold,textAlign=TextAlign.End,lineHeight=TextUnit.Unspecified,
                                            color=if(score==null)MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary),
                                        autoSize=TextAutoSize.StepBased(8.sp,if(compact)17.sp else 21.sp))
                                }
                            }
                        }
                    }
                }
            }
            if(status=="FAILED"||status=="INTERRUPTED")Text("部分项目未完成，可重新测试",fontSize=10.sp,color=MaterialTheme.colorScheme.error,maxLines=1)
            else if(!error.isNullOrBlank())Text(when{error.contains("内存")->"可用内存不足，请选择快速测试";error.contains("降温")->"设备需要降温后再测试";else->"暂时无法开始测试，请重试"},
                fontSize=10.sp,color=MaterialTheme.colorScheme.error,maxLines=2,modifier=Modifier.testTag("compute_error"))
            Text("BenchBridge ${(report?.optString("app_version")?:BuildConfig.VERSION_NAME).substringBefore('-')}",fontSize=10.sp,lineHeight=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ComputeSettings(state:RamUiState,model:RamViewModel){
    val config=state.computeConfig
    LazyColumn(Modifier.fillMaxSize().testTag("compute_settings_page"),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{SectionCard("测试配置"){
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                FilterChip(config==ComputeConfig(),{model.configureCompute(ComputeConfig())},{Text("标准测试")},modifier=Modifier.testTag("compute_default"))
                FilterChip(config==ComputeConfig.quick(),{model.configureCompute(ComputeConfig.quick())},{Text("快速测试")},modifier=Modifier.testTag("compute_quick"))
            }
            Text("测试对象",style=MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                listOf("gpu","cpu").forEach { target -> FilterChip(target in config.targets,{
                    model.configureCompute(config.copy(targets=if(target in config.targets)config.targets-target else (config.targets+target)))
                },{Text(target.uppercase())},modifier=Modifier.testTag("compute_target_$target")) }
            }
            Text("重复次数",style=MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                listOf(1,3,5).forEach { n -> FilterChip(config.rounds==n,{model.configureCompute(config.copy(rounds=n))},{Text("$n 次")},modifier=Modifier.testTag("compute_rounds_$n")) }
            }
            Text("每轮时长",style=MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                listOf(150,1000,3000).forEach { n -> FilterChip(config.durationMs==n,{model.configureCompute(config.copy(durationMs=n))},{Text("${n/1000.0} 秒")}) }
            }
            Text("CPU 线程",style=MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                val allowed=state.computeCapabilities?.optJSONObject("cpu")?.optJSONArray("cpu_ids")?.length()?:state.capabilities?.optInt("allowed_cpus",1)?:1
                listOf(0,1,2,4,8,16).filter { it==0||it<=allowed }.forEach { n -> FilterChip(config.threads==n,{model.configureCompute(config.copy(threads=n))},{Text(if(n==0)"自动"else"$n")}) }
            }
            Text("内存测试大小",style=MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                listOf(16,64,128,256).forEach { n -> FilterChip(config.memoryMiB==n,{model.configureCompute(config.copy(memoryMiB=n))},{Text("$n MiB")}) }
            }
            Text("分形图像大小",style=MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                listOf(256,512,1024).forEach { n -> FilterChip(config.imageSize==n,{model.configureCompute(config.copy(imageSize=n))},{Text("$n × $n")}) }
            }
            Text("测试项目",style=MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                ComputeKind.entries.forEach { kind -> FilterChip(kind.code in config.kinds,{
                    model.configureCompute(config.copy(kinds=if(kind.code in config.kinds)config.kinds-kind.code else (config.kinds+kind.code).sorted()))
                },{Text(kind.title)}) }
            }
        }}
    }
}

@Composable
internal fun ComputeDetails(report:JSONObject,onExport:(JSONObject)->Unit){
    val config=ComputeConfig.fromJson(report.getJSONObject("config").toString())
    LazyColumn(Modifier.fillMaxSize().testTag("compute_details_page"),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{ReportActions(report,onExport)}
        item{SectionCard("本次测试"){Text(config.summary);Text("${report.optInt("completed_rounds")} / ${report.optInt("total_rounds")} 轮")}}
        ComputeKind.entries.filter { it.code in config.kinds }.forEach { kind -> item{
            SectionCard(kind.title){
                listOf("cpu","gpu").filter(config.targets::contains).forEach { target ->
                    val samples=ComputeResults.samples(report,kind.code,target)
                    val score=ComputeResults.median(report,kind.code,target)
                    val cell=ComputeResults.cell(report,kind.code,target)
                    Text("${target.uppercase()}：${score?.let { "%.2f ${kind.unit}".format(Locale.US,it) }?:"—"}")
                    if(cell?.optString("state")=="UNSUPPORTED")Text("设备暂不支持该项目",style=MaterialTheme.typography.bodySmall)
                    else Text("已完成 ${samples.size} / ${config.rounds} 次",style=MaterialTheme.typography.bodySmall)
                    samples.forEach { sample -> Text("第 ${sample.optInt("round")} 次：%.2f ${kind.unit}".format(Locale.US,ComputeResults.value(sample)),style=MaterialTheme.typography.bodySmall) }
                }
            }
        }}
    }
}
