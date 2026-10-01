package io.benchbridge.app.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.benchbridge.app.BenchmarkFormat
import io.benchbridge.app.ram.*
import java.util.Locale
import kotlin.math.*
import org.json.JSONArray
import org.json.JSONObject

private fun objects(array: JSONArray?): List<JSONObject> = array?.let { a->(0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
private fun sizeLabel(bytes: Long): String = if(bytes>=1048576)"%.3g MiB".format(Locale.US,bytes/1048576.0)else"%.3g KiB".format(Locale.US,bytes/1024.0)

/** 轴为实际工作集与 ns，旧数据只读，不重新解释为缓存规格。
 * Axes use actual working sets and ns; legacy data is read without reclassifying cache specifications. */
@Composable
internal fun CacheLatencyPanel(report: JSONObject?, modifier: Modifier = Modifier, compact: Boolean = false) {
    val probe=report?.optJSONObject("cache_probe")
    val groups=objects(probe?.optJSONArray("groups"))
    var selectedCpu by rememberSaveable(report?.optString("run_id")) { mutableIntStateOf(-1) }
    var selectedBytes by rememberSaveable(report?.optString("run_id"),selectedCpu) { mutableLongStateOf(0) }
    var logarithmic by rememberSaveable { mutableStateOf(true) }
    var references by rememberSaveable { mutableStateOf(false) }
    val group=groups.firstOrNull { it.optInt("cpu_id")==selectedCpu }?:groups.firstOrNull()
    val points=objects(group?.optJSONArray("points")).filter { it.optLong("working_set_bytes")>0 && it.optDouble("latency_ns").let { v->v.isFinite()&&v>0 } }.sortedBy { it.optLong("working_set_bytes") }
    val edges=objects(group?.optJSONArray("transitions"))
    val pending=objects(group?.optJSONArray("candidate_intervals"))
    val caches=objects(report?.optJSONObject("topology")?.optJSONArray("caches")).filter { cache->
        val cpus=cache.optJSONArray("cpus")
        group!=null && cpus!=null && (0 until cpus.length()).any { cpus.optInt(it)==group.optInt("cpu_id") }
    }
    Column(modifier.testTag("cache_curve_panel"),verticalArrangement=Arrangement.spacedBy(if(compact)1.dp else 4.dp)) {
        if(!compact)Text("缓存访问延迟 · 点击曲线查看采样",fontSize=12.sp,lineHeight=16.sp,maxLines=1)
        Row(Modifier.fillMaxWidth()) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                groups.forEach { g->FilterChip(selected=group===g,onClick={selectedCpu=g.optInt("cpu_id");selectedBytes=0},
                    label={Text("CPU ${g.optInt("cpu_id")}",fontSize=if(compact)8.sp else 10.sp,lineHeight=12.sp,maxLines=1)},
                    modifier=Modifier.height(if(compact)26.dp else 30.dp).testTag("curve_cpu_${g.optInt("cpu_id")}")) }
            }
            if(compact) {
                TextButton(onClick={logarithmic=!logarithmic},contentPadding=PaddingValues(0.dp),modifier=Modifier.width(34.dp).height(26.dp).testTag("curve_y_scale")) {
                    Text(if(logarithmic)"对数"else"线性",fontSize=8.sp,lineHeight=12.sp,maxLines=1)
                }
                TextButton(onClick={references=!references},contentPadding=PaddingValues(0.dp),modifier=Modifier.width(34.dp).height(26.dp).testTag("curve_references")) {
                    Text(if(references)"参考✓"else"参考线",fontSize=8.sp,lineHeight=12.sp,maxLines=1)
                }
            }
        }
        if(!compact)Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            TextButton(onClick={logarithmic=!logarithmic},contentPadding=PaddingValues(0.dp),modifier=Modifier.height(26.dp).testTag("curve_y_scale")) {
                Text(if(logarithmic)"纵轴：对数 ns"else"纵轴：线性 ns",fontSize=10.sp,lineHeight=13.sp,maxLines=1)
            }
            TextButton(onClick={references=!references},contentPadding=PaddingValues(0.dp),modifier=Modifier.height(26.dp).testTag("curve_references")) {
                Text(if(references)"参考容量：显示"else"参考容量：隐藏",fontSize=10.sp,lineHeight=13.sp,maxLines=1)
            }
        }
        val primary=MaterialTheme.colorScheme.primary
        val textColor=MaterialTheme.colorScheme.onSurface
        val gridColor=MaterialTheme.colorScheme.outlineVariant
        val uncertain=MaterialTheme.colorScheme.error
        val maxBytes=maxOf(probe?.optLong("maximum_working_set_bytes",64L*1048576)?:64L*1048576,points.lastOrNull()?.optLong("working_set_bytes")?:0,8192)
        val logStart=ln(4096.0);val logSpan=ln(maxBytes.toDouble())-logStart
        val minimum=points.minOfOrNull { it.optDouble("minimum_ns",it.getDouble("latency_ns")) }?.coerceAtLeast(0.01)?:1.0
        val maximum=points.maxOfOrNull { it.optDouble("maximum_ns",it.getDouble("latency_ns")) }?.coerceAtLeast(minimum*1.1)?:512.0
        val minY=if(logarithmic)10.0.pow(floor(log10(minimum)))else 0.0
        val maxY=if(logarithmic)10.0.pow(ceil(log10(maximum)))else ceil(maximum*1.1).coerceAtLeast(1.0)
        val axisLeft=44.dp;val axisRight=12.dp
        Canvas(Modifier.fillMaxWidth().weight(1f).testTag("cache_latency_chart").pointerInput(points,maxBytes) {
            detectTapGestures { offset->
                if(points.isNotEmpty()) {
                    val x=((offset.x-axisLeft.toPx())/(size.width-axisLeft.toPx()-axisRight.toPx())).coerceIn(0f,1f)
                    val logBytes=logStart+x*logSpan
                    selectedBytes=points.minBy { abs(ln(it.getDouble("working_set_bytes"))-logBytes) }.getLong("working_set_bytes")
                }
            }
        }) {
            val left=axisLeft.toPx();val right=size.width-axisRight.toPx();val top=15.dp.toPx();val bottom=size.height-32.dp.toPx()
            if(right<=left||bottom<=top)return@Canvas
            fun x(bytes: Long)=left+((ln(bytes.coerceAtLeast(4096).toDouble())-logStart)/logSpan).toFloat()*(right-left)
            fun y(value: Double): Float {
                val fraction=if(logarithmic)(ln(value.coerceAtLeast(minY))-ln(minY))/(ln(maxY)-ln(minY))else value/maxY
                return bottom-fraction.coerceIn(0.0,1.0).toFloat()*(bottom-top)
            }
            val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=textColor.toArgb();textSize=9.sp.toPx() }
            val ticks=if(logarithmic) (floor(log10(minY)).toInt()..ceil(log10(maxY)).toInt()).map { 10.0.pow(it) }else(0..4).map { maxY*it/4 }
            ticks.forEach { value->
                val py=y(value);drawLine(gridColor,Offset(left,py),Offset(right,py),1f)
                val label=if(value<1)"%.2g".format(Locale.US,value)else"%.0f".format(Locale.US,value)
                drawContext.canvas.nativeCanvas.drawText(label,left-5.dp.toPx()-paint.measureText(label),py+paint.textSize/3,paint)
            }
            drawContext.canvas.nativeCanvas.drawText("ns",2.dp.toPx(),top-3.dp.toPx(),paint)
            val tickSizes=(listOf(4096L,32768L,262144L,2097152L,16777216L)+maxBytes).distinct().filter { it<=maxBytes }.sorted()
            var previous=-1000f
            tickSizes.forEach { bytes->
                val px=x(bytes);val label=if(bytes<1048576)"${bytes/1024}K"else"${bytes/1048576}M"
                val width=paint.measureText(label);val tx=(px-width/2).coerceIn(0f,(size.width-width).coerceAtLeast(0f))
                if(tx>previous+5.dp.toPx()) {
                    drawLine(gridColor,Offset(px,top),Offset(px,bottom),1f)
                    drawContext.canvas.nativeCanvas.drawText(label,tx,bottom+paint.textSize+4.dp.toPx(),paint);previous=tx+width
                }
            }
            val xlabel="工作集大小（KiB / MiB，对数）"
            drawContext.canvas.nativeCanvas.drawText(xlabel,left+(right-left-paint.measureText(xlabel))/2,size.height-2.dp.toPx(),paint)
            edges.forEach { e->drawRect(primary.copy(alpha=0.13f),Offset(x(e.getLong("lower_bytes")),top),Size((x(e.getLong("upper_bytes"))-x(e.getLong("lower_bytes"))).coerceAtLeast(1f),bottom-top)) }
            pending.forEach { e->drawRect(uncertain.copy(alpha=0.6f),Offset(x(e.getLong("lower_bytes")),top),Size((x(e.getLong("upper_bytes"))-x(e.getLong("lower_bytes"))).coerceAtLeast(1f),bottom-top),style=Stroke(1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(6f,5f)))) }
            if(references)caches.forEach { cache->
                val bytes=cache.optLong("bytes")
                if(bytes in 4096..maxBytes) {
                    val px=x(bytes);drawLine(gridColor,Offset(px,top),Offset(px,bottom),2f,pathEffect=PathEffect.dashPathEffect(floatArrayOf(6f,5f)))
                    drawContext.canvas.nativeCanvas.drawText("L${cache.optInt("level")}参考",px+2f,top+paint.textSize,paint)
                }
            }
            points.zipWithNext().forEach { (a,b)->drawLine(primary.copy(alpha=if(a.optBoolean("stable")&&b.optBoolean("stable"))0.9f else 0.3f),
                Offset(x(a.getLong("working_set_bytes")),y(a.getDouble("latency_ns"))),Offset(x(b.getLong("working_set_bytes")),y(b.getDouble("latency_ns"))),2.dp.toPx()) }
            points.forEach { p->
                val px=x(p.getLong("working_set_bytes"));val py=y(p.getDouble("latency_ns"));val color=if(p.optBoolean("stable"))primary else uncertain
                drawLine(color.copy(alpha=0.5f),Offset(px,y(p.optDouble("minimum_ns",p.getDouble("latency_ns")))),Offset(px,y(p.optDouble("maximum_ns",p.getDouble("latency_ns")))),1.dp.toPx())
                if(p.optBoolean("stable"))drawCircle(color,2.5.dp.toPx(),Offset(px,py))else drawCircle(color,3.dp.toPx(),Offset(px,py),style=Stroke(1.dp.toPx()))
                if(p.getLong("working_set_bytes")==selectedBytes)drawCircle(textColor,5.dp.toPx(),Offset(px,py),style=Stroke(1.dp.toPx()))
            }
            if(points.isEmpty())drawContext.canvas.nativeCanvas.drawText(if(probe==null)"开始测试后生成曲线"else"等待有效采样",left+20.dp.toPx(),(top+bottom)/2,paint)
        }
        val selected=points.firstOrNull { it.optLong("working_set_bytes")==selectedBytes }?:points.lastOrNull()
        Text(selected?.let { "${sizeLabel(it.getLong("working_set_bytes"))} · %.2f ns".format(Locale.US,it.getDouble("latency_ns"))+
            if(it.optBoolean("stable"))" · 稳定"else" · 波动 / 样本不足" }?:"固定核心 · 单线程随机访问",fontSize=if(compact)8.sp else 10.sp,lineHeight=if(compact)11.sp else 13.sp,maxLines=1,modifier=Modifier.testTag("curve_selected_point"))
        if(!compact)Text((if(edges.isEmpty())"尚未确认阶跃"else"阶跃："+edges.joinToString("；") { "${sizeLabel(it.getLong("lower_bytes"))}–${sizeLabel(it.getLong("upper_bytes"))}" })+
            if(pending.isNotEmpty())" · ${pending.size} 处疑似阶跃待复测"else" · 空心点需复测",
            fontSize=9.sp,lineHeight=12.sp,maxLines=2,modifier=Modifier.testTag("curve_transitions"))
    }
}

@Composable
internal fun CacheTopologyDetails(report: JSONObject) {
    SectionCard("工作集大小—访问延迟") {
        CacheLatencyPanel(report,Modifier.fillMaxWidth().height(360.dp))
        Text("阴影为满足稳定性条件的阶跃区间，虚线框为待复测的疑似阶跃，误差线为重复测量范围。参考线来自系统或资料库，不能把阶跃直接认定为 L1/L2/L3。",style=MaterialTheme.typography.bodySmall)
        report.optJSONObject("cache_probe")?.let { probe->
            if(probe.optString("method")!=CacheProbe.METHOD)Text("旧版扫描记录：保留原始采样与稳定性判断。",style=MaterialTheme.typography.bodySmall)
            objects(probe.optJSONArray("groups")).forEach { group->
                val points=objects(group.optJSONArray("points"))
                Text("CPU ${group.optInt("cpu_id")}：${points.count { it.optBoolean("stable") }} / ${points.size} 个稳定点；排除 ${group.optInt("rejected_trials")} 次受干扰采样",style=MaterialTheme.typography.bodySmall)
            }
        }
        Text("原始样本、被排除的原因、线程实际运行时间与参考规格均保存在 JSON。",style=MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun ColumnScope.CacheCurveBoard(report: JSONObject?, config: RamConfig, unit: String, compact: Boolean) {
    CacheLatencyPanel(report,Modifier.fillMaxWidth().weight(1f),compact)
    HorizontalDivider(Modifier.padding(vertical=if(compact)2.dp else 4.dp))
    if(!compact)Text("RAM · 大工作集",fontSize=11.sp,lineHeight=14.sp,maxLines=1)
    Row(Modifier.fillMaxWidth()) {
        MemoryPlanner.columns.forEach { kind->
            val stats=report?.let { RamResults.statistics(it,kind,"RAM") }
            val plan=report?.let { RamResults.cell(it,"RAM",kind)?.optJSONObject("plan") }
            Column(Modifier.weight(1f).padding(vertical=if(compact)1.dp else 3.dp)) {
                val label=when(kind){0->"读取";1->"写入";5->"延迟";else->"拷贝"}
                Text(if(compact)"$label · ${if(kind==5)"ns"else unit}"else label,fontSize=if(compact)7.sp else 10.sp,lineHeight=if(compact)10.sp else 13.sp,maxLines=1)
                Text(stats?.let { "%.2f".format(Locale.US,it.median*if(kind==5||unit=="GB/s")1 else 1000) }?:"—",fontSize=if(compact)12.sp else 17.sp,lineHeight=if(compact)16.sp else 21.sp,maxLines=1,
                    color=MaterialTheme.colorScheme.primary,modifier=Modifier.testTag("curve_ram_$kind"))
                if(!compact)Text(if(kind==5)"ns"else unit,fontSize=9.sp,lineHeight=12.sp,maxLines=1)
                if(!compact)Text(plan?.let { "T${it.optInt("threads")} · ${sizeLabel(it.optLong("working_set_bytes"))}" }?:if(kind !in config.kinds)"未选"else"",fontSize=8.sp,lineHeight=11.sp,maxLines=1)
            }
        }
    }
}
