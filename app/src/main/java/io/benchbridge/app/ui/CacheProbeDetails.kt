package io.benchbridge.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import io.benchbridge.app.BenchmarkFormat
import java.util.Locale
import kotlin.math.ln
import org.json.JSONObject

@Composable
internal fun CacheTopologyDetails(report: JSONObject) {
    SectionCard("缓存规格与实测") {
        report.optJSONObject("topology")?.let { topology ->
            topology.optJSONObject("soc_metadata")?.optJSONObject("cache_notes")?.optString("zh")?.let {
                Text(it, style=MaterialTheme.typography.bodySmall)
            }
            val caches = topology.optJSONArray("caches")
            if (caches != null) for (i in 0 until caches.length()) {
                val cache = caches.getJSONObject(i)
                val granule = if (cache.optString("line_source")=="runtime-ctr-el0-minimum") "最小行粒度" else "行大小"
                Text("L${cache.optInt("level")} · ${BenchmarkFormat.bytes(cache.optLong("bytes"))} · CPU ${cache.optJSONArray("cpus")} · $granule ${cache.optInt("line_bytes")} B",style=MaterialTheme.typography.bodySmall)
            }
        }
        val probe = report.optJSONObject("cache_probe")
        if (probe == null) Text("缓存拓扑已完整，使用系统或已验证资料。",style=MaterialTheme.typography.bodySmall)
        else {
            Text("绑核分块扫描：延迟上升、带宽下降的位置是有效容量候选区间；TLB、预取、调频和系统缓存也会造成拐点，不能仅凭曲线认定 L3。",style=MaterialTheme.typography.bodySmall)
            Text("状态 ${probe.optString("state")} · 扫描不计入正式成绩",style=MaterialTheme.typography.bodySmall)
            val groups = probe.optJSONArray("groups")
            if (groups != null) for (i in 0 until groups.length()) CacheProbeGroup(groups.getJSONObject(i))
        }
    }
}

@Composable
private fun CacheProbeGroup(group: JSONObject) {
    var expanded by remember { mutableStateOf(false) }
    val array = group.optJSONArray("points")
    val points = if (array == null) emptyList() else (0 until array.length()).map { array.getJSONObject(it) }
    val latencyColor = MaterialTheme.colorScheme.primary
    val bandwidthColor = MaterialTheme.colorScheme.tertiary
    Text("CPU ${group.optInt("cpu_id")} · 步长 ${group.optInt("node_stride_bytes")} B",style=MaterialTheme.typography.labelLarge)
    Text("延迟 ns（主色） / 读取 GB/s（辅色），各自归一化；横轴为工作集对数。",style=MaterialTheme.typography.bodySmall)
    if (points.size >= 2) {
        Canvas(Modifier.fillMaxWidth().height(110.dp)) {
            val start = ln(points.first().getDouble("working_set_bytes"))
            val span = ln(points.last().getDouble("working_set_bytes")) - start
            for ((field,color) in listOf("latency_ns" to latencyColor,"read_gbps" to bandwidthColor)) {
                val max = points.maxOf { it.getDouble(field) }.coerceAtLeast(0.001)
                val path = Path()
                points.forEachIndexed { index, p ->
                    val x = ((ln(p.getDouble("working_set_bytes"))-start)/span*size.width).toFloat()
                    val y = (size.height-6-p.getDouble(field)/max*(size.height-12)).toFloat()
                    if(index==0)path.moveTo(x,y)else path.lineTo(x,y)
                    drawCircle(color,if(p.optBoolean("stable"))2.5f else 4.5f,Offset(x,y))
                }
                drawPath(path,color,style=Stroke(2f))
            }
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Text(BenchmarkFormat.bytes(points.first().getLong("working_set_bytes")),style=MaterialTheme.typography.labelSmall)
            Text(BenchmarkFormat.bytes(points.last().getLong("working_set_bytes")),style=MaterialTheme.typography.labelSmall)
        }
    }
    val edges = group.optJSONArray("transitions")
    if (edges == null || edges.length()==0) Text("未分辨出稳定拐点；不推测容量。",style=MaterialTheme.typography.bodySmall)
    else for (i in 0 until edges.length()) {
        val edge=edges.getJSONObject(i)
        Text("候选区间 ${BenchmarkFormat.bytes(edge.getLong("lower_bytes"))}–${BenchmarkFormat.bytes(edge.getLong("upper_bytes"))}"+
            " · 延迟 ×%.2f".format(Locale.US,edge.getDouble("latency_ratio")),style=MaterialTheme.typography.bodySmall)
    }
    TextButton(onClick={expanded=!expanded}) { Text(if(expanded)"收起采样点"else"查看 ${points.size} 个采样点") }
    if(expanded) points.forEach { p -> Text("${BenchmarkFormat.bytes(p.getLong("working_set_bytes"))} · %.2f ns · %.2f GB/s".format(Locale.US,p.getDouble("latency_ns"),p.getDouble("read_gbps"))+
        if(p.optBoolean("stable"))""else" · 波动",style=MaterialTheme.typography.bodySmall) }
}
