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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
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
    var logarithmic by rememberSaveable { mutableStateOf(false) }
    var references by rememberSaveable { mutableStateOf(false) }
    val group=groups.firstOrNull { it.optInt("cpu_id")==selectedCpu }
    val visible=if(group==null)groups else listOf(group)
    val palette=listOf(Color(0xFF4658B8),Color(0xFF008577),Color(0xFFB26318),Color(0xFF9551A5))
    fun samples(g:JSONObject)=objects(g.optJSONArray("points")).filter { it.optLong("working_set_bytes")>0 && it.optDouble("latency_ns").let { v->v.isFinite()&&v>0 } }.sortedBy { it.optLong("working_set_bytes") }
    val points=visible.flatMap(::samples).sortedBy { it.optLong("working_set_bytes") }
    val allPoints=groups.flatMap(::samples)
    val edges=objects(group?.optJSONArray("transitions"))
    val caches=objects(report?.optJSONObject("topology")?.optJSONArray("caches")).filter { cache->
        val cpus=cache.optJSONArray("cpus")
        cpus!=null && visible.any { g->(0 until cpus.length()).any { cpus.optInt(it)==g.optInt("cpu_id") } }
    }
    Column(modifier.testTag("cache_curve_panel"),verticalArrangement=Arrangement.spacedBy(if(compact)1.dp else 4.dp)) {
        if(!compact)Text("各核心组延迟 · 同一坐标比较",fontSize=12.sp,lineHeight=16.sp,maxLines=1)
        Row(Modifier.fillMaxWidth()) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                FilterChip(selected=group==null,onClick={selectedCpu=-1;selectedBytes=0},label={Text("全部",fontSize=10.sp,lineHeight=12.sp,maxLines=1)},modifier=Modifier.height(if(compact)26.dp else 30.dp).testTag("curve_cpu_all"))
                groups.forEach { g->FilterChip(selected=group===g,onClick={selectedCpu=g.optInt("cpu_id");selectedBytes=0},
                    label={Text("CPU ${g.optInt("cpu_id")}",color=palette[groups.indexOf(g)%palette.size],fontSize=if(compact)8.sp else 10.sp,lineHeight=12.sp,maxLines=1)},
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
        val maxBytes=maxOf(probe?.optLong("maximum_working_set_bytes",64L*1048576)?:64L*1048576,points.lastOrNull()?.optLong("working_set_bytes")?:0,8192)
        val logStart=ln(4096.0);val logSpan=ln(maxBytes.toDouble())-logStart
        val minimum=allPoints.minOfOrNull { it.optDouble("minimum_ns",it.getDouble("latency_ns")) }?.coerceAtLeast(0.01)?:1.0
        val maximum=allPoints.maxOfOrNull { it.optDouble("maximum_ns",it.getDouble("latency_ns")) }?.coerceAtLeast(minimum*1.1)?:512.0
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
            if(references)caches.forEach { cache->
                val bytes=cache.optLong("bytes")
                if(bytes in 4096..maxBytes) {
                    val px=x(bytes);drawLine(gridColor,Offset(px,top),Offset(px,bottom),2f,pathEffect=PathEffect.dashPathEffect(floatArrayOf(6f,5f)))
                    val label="L${cache.optInt("level")}参考"
                    drawContext.canvas.nativeCanvas.drawText(label,(px+2f).coerceAtMost(size.width-paint.measureText(label)),top+paint.textSize,paint)
                }
            }
            visible.forEach { g->
                val color=palette[groups.indexOf(g)%palette.size]
                val series=samples(g)
                series.zipWithNext().forEach { (a,b)->
                    if(b.getLong("working_set_bytes").toDouble()/a.getLong("working_set_bytes")<=1.6)
                        drawLine(color.copy(alpha=if(a.optBoolean("stable")&&b.optBoolean("stable"))0.95f else 0.35f),
                            Offset(x(a.getLong("working_set_bytes")),y(a.getDouble("latency_ns"))),Offset(x(b.getLong("working_set_bytes")),y(b.getDouble("latency_ns"))),1.6.dp.toPx())
                }
                series.forEach { p->
                    val px=x(p.getLong("working_set_bytes"));val py=y(p.getDouble("latency_ns"))
                    if(group!=null)drawLine(color.copy(alpha=0.35f),Offset(px,y(p.optDouble("minimum_ns",p.getDouble("latency_ns")))),Offset(px,y(p.optDouble("maximum_ns",p.getDouble("latency_ns")))),1.dp.toPx())
                    if(p.optBoolean("stable"))drawCircle(color,1.7.dp.toPx(),Offset(px,py))else drawCircle(color.copy(alpha=0.6f),2.dp.toPx(),Offset(px,py),style=Stroke(1.dp.toPx()))
                    if(p.getLong("working_set_bytes")==selectedBytes)drawCircle(textColor,4.dp.toPx(),Offset(px,py),style=Stroke(1.dp.toPx()))
                }
            }
            if(points.isEmpty())drawContext.canvas.nativeCanvas.drawText(if(probe==null)"开始测试后生成曲线"else"等待有效采样",left+20.dp.toPx(),(top+bottom)/2,paint)
        }
        val targetBytes=selectedBytes.takeIf { it>0 }?:points.lastOrNull()?.optLong("working_set_bytes")
        val selected=visible.mapNotNull { g->samples(g).firstOrNull { it.optLong("working_set_bytes")==targetBytes }?.let { g.optInt("cpu_id") to it } }
        Text(if(selected.isEmpty())"固定核心 · 随机依赖访问 · 原始中位数"else "${sizeLabel(targetBytes!!)} · "+selected.joinToString(" / "){(cpu,p)->"CPU $cpu %.2f ns".format(Locale.US,p.getDouble("latency_ns"))},
            fontSize=if(compact)8.sp else 10.sp,lineHeight=if(compact)11.sp else 13.sp,maxLines=2,modifier=Modifier.testTag("curve_selected_point"))
        if(!compact)Text(if(group!=null)group.optJSONObject("analysis")?.optString("summary")?:if(probe?.optString("method")==CacheProbe.METHOD)"正在完成整段扫描与交叉验证"else"旧协议记录，保留原始曲线"
            else if(probe?.optString("state")=="COMPLETED")"所有核心组已完成 · 分段结论见详情"else"正反两遍扫描 · 自动复核 · 点按核心查看结论",
            fontSize=9.sp,lineHeight=12.sp,maxLines=3,modifier=Modifier.testTag("curve_transitions"))
    }
}

@Composable
internal fun CacheTopologyDetails(report: JSONObject) {
    SectionCard("工作集大小—访问延迟") {
        CacheLatencyPanel(report,Modifier.fillMaxWidth().height(360.dp))
        Text("实线连接实测中位数；空心点未通过一致性验证，未用于分段结论。误差线为 10–90% 分位区间。不同核心共享坐标轴，线性和对数纵轴均以 ns 为单位。",style=MaterialTheme.typography.bodySmall)
        report.optJSONObject("cache_probe")?.let { probe->
            if(probe.optString("method")!=CacheProbe.METHOD)Text("旧协议记录：仅保留原始曲线。",style=MaterialTheme.typography.bodySmall)
            objects(probe.optJSONArray("groups")).forEach { g->
                HorizontalDivider()
                Text("CPU ${g.optInt("cpu_id")} · %.2f GHz 上限".format(Locale.US,g.optLong("max_khz")/1000000.0),style=MaterialTheme.typography.titleSmall)
                val analysis=g.optJSONObject("analysis")
                Text(analysis?.optString("summary")?:"扫描未完成，已有采样已保存。",style=MaterialTheme.typography.bodySmall)
                objects(analysis?.optJSONArray("regions")).forEach { r->
                    val trend=when(r.optString("trend")){"plateau"->"平台";"rising"->"上升";else->"下降"}
                    Text("区间 ${r.optInt("index")}（$trend）：${sizeLabel(r.getLong("lower_bytes"))}–${sizeLabel(r.getLong("upper_bytes"))} · 中位 %.2f ns".format(Locale.US,r.getDouble("latency_ns")),style=MaterialTheme.typography.bodySmall)
                }
                objects(analysis?.optJSONArray("transitions")).forEachIndexed { index,e->
                    Text("转换 ${index+1}：${sizeLabel(e.getLong("lower_bytes"))}–${sizeLabel(e.getLong("upper_bytes"))} · %.2f → %.2f ns".format(Locale.US,e.getDouble("before_ns"),e.getDouble("after_ns")),style=MaterialTheme.typography.bodySmall)
                }
                objects(analysis?.optJSONArray("unresolved_intervals")).forEach { e->
                    Text("${sizeLabel(e.getLong("lower_bytes"))}–${sizeLabel(e.getLong("upper_bytes"))}："+if(e.optString("reason")=="insufficient_contiguous_points")"连续有效点不足，不能定位边界"else"重复测量不一致或缺测，不能定位边界",style=MaterialTheme.typography.bodySmall)
                }
            }
        }
        Text("结论描述实测访问层次，不将曲线转换直接等同于 L1/L2/L3 容量。系统页大小、TLB、预取和频率均可影响曲线。参考线仅代表系统或资料库容量。",style=MaterialTheme.typography.bodySmall)
        Text("本版采用高熵数据上的 32 位依赖索引链（含地址计算），普通系统页。原始计时、线程实际运行时间、频率及正反两遍结果均保存在 JSON，比较其他工具时需使用相同访问模式。",style=MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun ColumnScope.CacheCurveBoard(report: JSONObject?, config: RamConfig, unit: String, compact: Boolean) {
    CacheLatencyPanel(report,Modifier.fillMaxWidth().weight(1f),compact)
    HorizontalDivider(Modifier.padding(vertical=if(compact)2.dp else 4.dp))
    RamSummaryRow(report,config,unit,compact)
}

/** 首页和详情共用四项摘要；仅使用 RAM 正式轮次，不拿曲线值代替未测成绩。
 * Share the four-score summary between dashboard and details; never substitute curve samples for unmeasured RAM rounds. */
@Composable
internal fun RamSummaryRow(report: JSONObject?, config: RamConfig, unit: String = "GB/s", compact: Boolean = false) {
    Text(if(!config.curveIncludeRam)"RAM · 本次未测试"else"RAM · 大工作集",
        fontSize=if(compact)8.sp else 11.sp,lineHeight=if(compact)11.sp else 14.sp,maxLines=1,modifier=Modifier.testTag("ram_summary_title"))
    Row(Modifier.fillMaxWidth()) {
        MemoryPlanner.columns.forEach { kind->
            val stats=report?.let { RamResults.statistics(it,kind,"RAM") }
            val cell=report?.let { RamResults.cell(it,"RAM",kind) }
            val plan=cell?.optJSONObject("plan")
            val hint=when {
                stats!=null -> plan?.let { "T${it.optInt("threads")} · ${sizeLabel(it.optLong("working_set_bytes"))}" }.orEmpty()
                !config.curveIncludeRam -> "未测"
                kind !in config.kinds -> "未选"
                cell?.optString("state")=="UNSUPPORTED" -> "不支持"
                cell?.optString("state")=="FAILED" -> "未完成"
                report!=null && report.optString("state") in RamResults.terminalStates -> "未测"
                else -> "待测"
            }
            Column(Modifier.weight(1f).padding(vertical=if(compact)1.dp else 3.dp)) {
                val label=when(kind){0->"读取";1->"写入";5->"延迟";else->"拷贝"}
                Text(if(compact)"$label · ${if(kind==5)"ns"else unit}"else label,fontSize=if(compact)7.sp else 10.sp,lineHeight=if(compact)10.sp else 13.sp,maxLines=1)
                ScoreNumber(stats?.let { "%.2f".format(Locale.US,it.median*if(kind==5||unit=="GB/s")1 else 1000) }?:"—",
                    stats!=null,"curve_ram_$kind",if(compact)12 else 17,Modifier.fillMaxWidth(),TextAlign.Start,8)
                if(!compact)Text(if(kind==5)"ns"else unit,fontSize=9.sp,lineHeight=12.sp,maxLines=1)
                if(!compact || stats==null)Text(hint,fontSize=8.sp,lineHeight=11.sp,maxLines=1,modifier=Modifier.testTag("curve_ram_hint_$kind"))
            }
        }
    }
}
