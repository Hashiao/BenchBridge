package io.benchbridge.app.ram

import io.benchbridge.app.hardware.CpuCore
import io.benchbridge.app.hardware.CpuTopology
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

/** 默认单次全网格扫描，可选双向复核；逐点持久化。
 * Single-sample full-grid sweep by default, optional bidirectional checks and per-point checkpoints. */
object CacheProbe {
    const val METHOD="dense-index-curve-v3"
    const val FAST_METHOD="single-pass-index-curve-v1"
    fun supported(method:String)=method in setOf(METHOD,FAST_METHOD)
    data class Point(val bytes:Long,val latency:Double,val stable:Boolean,val low:Double=latency,
                     val high:Double=latency,val trials:Int=0,val passMedians:List<Double> = emptyList()) {
        fun toJson()=JSONObject().put("working_set_bytes",bytes).put("latency_ns",latency).put("stable",stable)
            .put("minimum_ns",low).put("maximum_ns",high).put("valid_trials",trials).put("pass_medians_ns",JSONArray(passMedians))
    }
    internal fun median(values:List<Double>):Double=values.sorted().let { if(it.size%2==1)it[it.size/2]else(it[it.size/2-1]+it[it.size/2])/2 }
    internal fun representatives(topology:CpuTopology):List<CpuCore> = topology.allowedCores.groupBy {
        if(it.maxKhz==0L&&it.capacity==0L&&it.part.isEmpty()&&it.frequencyDomain.isEmpty())"cpu${it.id}"
        else "${it.part}:${it.capacity}:${it.maxKhz}:${it.frequencyDomain}"
    }.values.map { it.minBy(CpuCore::id) }.sortedByDescending(CpuCore::maxKhz).take(16)
    internal fun sizes(limit:Long,anchors:List<Long>,steps:Int=8):List<Long> {
        if(limit<4096)return emptyList()
        val count=ceil(log2(limit/4096.0)*steps).toInt()
        return ((0..count).map { (4096.0*2.0.pow(it.toDouble()/steps)).toLong()/256*256 }+
            anchors.flatMap { a->listOf(a*15/16,a,a*17/16).map { it/256*256 } }+limit/256*256)
            .filter { it in 4096..limit }.distinct().sorted()
    }
    internal fun values(result:JSONObject):List<Double> {
        if(result.optString("status")!="COMPLETED"||!result.optBoolean("verified"))return emptyList()
        val trials=result.optJSONArray("trials")?:return emptyList()
        return (0 until trials.length()).map { trials.getJSONObject(it) }.filter {
            it.optBoolean("accepted")&&it.optLong("operations")>0&&it.optLong("elapsed_ns")>0
        }.map { it.getLong("elapsed_ns").toDouble()/it.getLong("operations") }.filter { it.isFinite()&&it>0 }
    }
    private fun stable(values:List<Double>):Boolean {
        if(values.size<5)return false
        val center=median(values);val deviation=median(values.map { abs(it-center) })/center
        return deviation<=0.06 && values.count { abs(it-center)/center<=0.12 }>=ceil(values.size*0.8).toInt()
    }
    internal fun point(bytes:Long,forward:JSONObject?,reverse:JSONObject?):Point? {
        val a=forward?.let(::values).orEmpty();val b=reverse?.let(::values).orEmpty();val all=a+b
        if(all.isEmpty())return null
        val centers=listOf(a,b).filter { it.isNotEmpty() }.map(::median)
        val center=median(all);val sorted=all.sorted()
        val agrees=centers.size==2&&(centers.max()-centers.min())/center<=0.12
        return Point(bytes,center,stable(a)&&stable(b)&&agrees,sorted[(sorted.size-1)/10],sorted[(sorted.size-1)*9/10],all.size,centers)
    }
    fun canResume(report:JSONObject):Boolean = report.optString("state") in listOf("INTERRUPTED","CANCELLED") &&
        supported(report.optJSONObject("cache_probe")?.optString("method").orEmpty()) && report.optJSONObject("config")?.optBoolean("curve_include_ram",true)==false

    fun run(topology:CpuTopology,memoryBudget:Long,shouldStop:()->Boolean,
            sample:(cpu:Int,bytes:Long,stride:Int,seed:Long)->JSONObject,progress:(JSONObject)->Unit,
            config:RamConfig=RamConfig.matrixQuick(),previous:JSONObject?=null):JSONObject {
        val single=config.singleCurveSample
        val passes=if(single)1 else 2
        val method=if(single)FAST_METHOD else METHOD
        val limit=minOf(config.curveMaxMiB*1048576L,((memoryBudget-40L*1048576).coerceAtLeast(0)*8/9)/256*256)
        val cores=representatives(topology)
        val signature=cores.joinToString(";"){"${it.id}:${it.maxKhz}:${it.frequencyDomain}"}
        if(previous!=null)require(previous.optString("method")==method&&previous.optString("topology_signature")==signature&&previous.optInt("steps_per_octave")==config.curveSteps&&previous.optLong("requested_maximum_bytes")==config.curveMaxMiB*1048576L&&previous.optLong("maximum_working_set_bytes")<=limit) { "核心环境、内存预算或测试协议变化，请重新测试" }
        val report=previous?:JSONObject().put("method",method).put("groups",JSONArray()).put("scored",false)
            .put("topology_signature",signature).put("steps_per_octave",config.curveSteps).put("passes",passes)
            .put("sampling_mode",if(single)"single-sample"else"bidirectional-repeated")
            .put("automatic_rechecks",!single).put("automatic_refinement",!single)
            .put("requested_maximum_bytes",config.curveMaxMiB*1048576L).put("maximum_working_set_bytes",limit)
            .put("kernel","dependent-index32-v1").put("data_pattern","global-random-high-entropy")
        report.put("state","RUNNING")
        val groups=report.getJSONArray("groups")
        val commonGrid=sizes(limit,if(single)emptyList()else topology.caches.map { it.bytes },config.curveSteps)
        if(groups.length()==0)cores.forEach { core->
            val grid=commonGrid
            groups.put(JSONObject().put("cpu_id",core.id).put("max_khz",core.maxKhz).put("frequency_domain",core.frequencyDomain)
                .put("node_stride_bytes",topology.cache(core.id,1)?.lineBytes?:topology.dataLineBytes.takeIf { it in listOf(32,64,128,256) }?:64)
                .put("planned_sizes",JSONArray(grid)).put("samples",JSONArray()).put("points",JSONArray()).put("transitions",JSONArray()).put("state","RUNNING"))
        }
        fun grid(g:JSONObject)=g.getJSONArray("planned_sizes").let { a->(0 until a.length()).map { a.getLong(it) } }
        val batches=mutableMapOf<Triple<Int,Long,Int>,JSONObject>()
        val attempts=mutableMapOf<Triple<Int,Long,Int>,Int>()
        for(i in 0 until groups.length()) {
            val g=groups.getJSONObject(i);val records=g.getJSONArray("samples")
            for(j in 0 until records.length()) {
                val record=records.getJSONObject(j);val key=Triple(g.getInt("cpu_id"),record.getLong("working_set_bytes"),record.getInt("pass"))
                batches[key]=record.getJSONObject("result");attempts[key]=(attempts[key]?:0)+1
            }
        }
        fun latest(g:JSONObject,bytes:Long,pass:Int)=batches[Triple(g.getInt("cpu_id"),bytes,pass)]
        fun refresh(g:JSONObject):List<Point> {
            val pts=grid(g).mapNotNull { bytes->if(single)latest(g,bytes,0)?.let(::values)?.singleOrNull()?.let {
                Point(bytes,it,true,trials=1,passMedians=listOf(it))
            }else point(bytes,latest(g,bytes,0),latest(g,bytes,1)) }
            g.put("points",JSONArray(pts.map(Point::toJson))).put("stable_points",pts.count { it.stable })
            report.put("planned_points",(0 until groups.length()).sumOf { grid(groups.getJSONObject(it)).size*passes })
                .put("completed_points",(0 until groups.length()).sumOf { i->val x=groups.getJSONObject(i);grid(x).sumOf { b->(0 until passes).count { latest(x,b,it)!=null } } })
            return pts
        }
        fun measure(g:JSONObject,bytes:Long,pass:Int,force:Boolean=false) {
            if(shouldStop()||!force&&latest(g,bytes,pass)!=null)return
            val records=g.getJSONArray("samples")
            val key=Triple(g.getInt("cpu_id"),bytes,pass)
            val attempt=(attempts[key]?:0)+1
            if(attempt>(if(single)1 else 3))return
            report.put("current_cpu_id",g.getInt("cpu_id")).put("current_working_set_bytes",bytes).put("current_pass",pass+1)
            report.put("checkpoint",false);progress(report)
            val result=sample(g.getInt("cpu_id"),bytes,g.getInt("node_stride_bytes"),0xB16B00B5L+pass*1009+attempt*7919)
            if(result.optString("status")=="INTERRUPTED")return
            records.put(JSONObject().put("working_set_bytes",bytes).put("pass",pass).put("attempt",attempt).put("result",result))
            batches[key]=result;attempts[key]=attempt
            refresh(g);report.put("checkpoint",true);progress(report)
        }
        for(i in 0 until groups.length())refresh(groups.getJSONObject(i))
        report.put("stage","SWEEPING").put("checkpoint",true);progress(report)
        // 第二遍反转大小和核心组顺序，用独立排列交叉验证。 / Reverse sizes and group order with independent permutations on pass two.
        for(pass in 0 until passes)for(i in if(pass==0)(0 until groups.length()).toList()else(groups.length()-1 downTo 0).toList()) {
            if(shouldStop())break
            val g=groups.getJSONObject(i);val list=grid(g).let { if(pass==0)it else it.reversed() }
            list.forEach { if(!shouldStop())measure(g,it,pass) }
        }
        for(i in 0 until groups.length()) {
            if(shouldStop())break
            val g=groups.getJSONObject(i)
            // 对不一致点有界复核，不选择最快结果；最新一批替换该遍的旧批次，原始数据仍保留。
            // Bounded rechecks replace the latest batch rather than selecting the fastest; retain every raw batch.
            for(recheck in 0 until (if(single)0 else 2)) {
                report.put("stage","VERIFYING")
                val invalid=grid(g).filter { bytes->point(bytes,latest(g,bytes,0),latest(g,bytes,1))?.stable!=true }
                invalid.forEach { bytes->for(pass in 0..1)if(!shouldStop())measure(g,bytes,pass,true) }
                if(shouldStop())break
            }
            if(!single&&!shouldStop()) {
                val analysis=LatencyAnalysis.analyze(refresh(g),grid(g))
                val edges=analysis.getJSONArray("transitions")
                val extra=(0 until edges.length()).flatMap { index->val e=edges.getJSONObject(index);(1..3).map { n->(e.getLong("lower_bytes")+(e.getLong("upper_bytes")-e.getLong("lower_bytes"))*n/4)/256*256 } }
                    .distinct().filter { it !in grid(g) }
                g.put("planned_sizes",JSONArray((grid(g)+extra).distinct().sorted()))
                report.put("stage","REFINING")
                extra.forEach { bytes->
                    for(pass in 0..1)if(!shouldStop())measure(g,bytes,pass)
                    for(recheck in 0..1)if(point(bytes,latest(g,bytes,0),latest(g,bytes,1))?.stable!=true)
                        for(pass in 0..1)if(!shouldStop())measure(g,bytes,pass,true)
                }
            }
            val finalPoints=refresh(g)
            val analysis=LatencyAnalysis.analyze(finalPoints,grid(g))
            if(single) {
                analysis.put("sampling_mode","single-sample").put("summary",analysis.optString("summary")
                    .replace("点通过验证","个有效采样点").replace("稳定点","有效点").replace("完整曲线尚未通过验证","曲线尚未完整采集"))
                val edges=analysis.getJSONArray("transitions")
                for(e in 0 until edges.length())edges.getJSONObject(e).put("confidence","single-sample")
            }
            g.put("analysis",analysis).put("transitions",analysis.getJSONArray("transitions"))
                .put("state",if(shouldStop())"CANCELLED"else if(analysis.optString("status")=="COMPLETE")"COMPLETED"else"INCOMPLETE")
            progress(report)
        }
        report.put("state",when { shouldStop()->"CANCELLED";limit<4096->"BUDGET_UNAVAILABLE";groups.length()==0->"UNAVAILABLE"
            (0 until groups.length()).any { groups.getJSONObject(it).optString("state")!="COMPLETED" }->"INCOMPLETE";else->"COMPLETED" })
        progress(report);return report
    }
}
