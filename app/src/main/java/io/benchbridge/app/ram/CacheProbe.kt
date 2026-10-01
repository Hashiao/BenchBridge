package io.benchbridge.app.ram

import io.benchbridge.app.hardware.CpuCore
import io.benchbridge.app.hardware.CpuTopology
import org.json.JSONArray
import org.json.JSONObject

/** 固定核心的延迟阶跃扫描，不猜测物理缓存层级。 / Fixed-core latency steps without guessed cache levels. */
object CacheProbe {
    const val METHOD = "latency-step-sweep-v2"
    data class Point(val bytes: Long, val latency: Double, val stable: Boolean,
                     val low: Double = latency, val high: Double = latency, val trials: Int = 3) {
        fun toJson() = JSONObject().put("working_set_bytes",bytes).put("latency_ns",latency)
            .put("minimum_ns",low).put("maximum_ns",high).put("stable",stable).put("valid_trials",trials)
            .put("relative_range",if(latency>0)(high-low)/latency else 0)
    }
    data class Edge(val lower: Long, val upper: Long, val latencyRatio: Double) {
        fun toJson() = JSONObject().put("lower_bytes",lower).put("upper_bytes",upper)
            .put("latency_ratio",latencyRatio).put("confidence","repeated-latency-step").put("cache_level",JSONObject.NULL)
    }
    internal fun median(values: List<Double>): Double = values.sorted().let {
        if(it.size%2==1)it[it.size/2]else(it[it.size/2-1]+it[it.size/2])/2
    }
    internal fun representatives(topology: CpuTopology): List<CpuCore> = topology.allowedCores
        .groupBy { if(it.maxKhz==0L && it.capacity==0L && it.part.isEmpty() && it.frequencyDomain.isEmpty())"cpu${it.id}"
            else "${it.part}:${it.capacity}:${it.maxKhz}:${it.frequencyDomain}" }
        .values.map { it.minBy(CpuCore::id) }.sortedByDescending(CpuCore::maxKhz).take(16)
    internal fun sizes(limit: Long, anchors: List<Long>): List<Long> {
        val sizes=sortedSetOf<Long>(); var bytes=4096L
        while(bytes<=limit){sizes+=bytes;sizes+=bytes*3/2;bytes*=2}
        anchors.forEach { size->listOf(0.75,1.0,1.25,1.5).forEach { sizes+=(size*it).toLong()/256*256 } }
        return sizes.filter { it in 4096..limit }
    }
    internal fun qualityReason(sample: JSONObject): String? {
        if(sample.optString("status")!="COMPLETED" || !sample.optBoolean("verified"))return "NATIVE_INCOMPLETE"
        if(sample.optInt("kind",-1)!=5 || sample.optLong("operations")<=0)return "INVALID_LATENCY_SAMPLE"
        val elapsed=sample.optLong("elapsed_ns");val requested=sample.optLong("requested_duration_ms")*1000000
        if(requested<=0 || elapsed<requested*0.9 || elapsed>requested*1.25+10000000)return "TIMING_OVERRUN"
        val cpuTime=sample.optJSONArray("per_thread_cpu_elapsed_ns")?.optLong(0)?:0
        if(cpuTime<=0)return "THREAD_TIME_UNAVAILABLE"
        if(cpuTime.toDouble()/elapsed<0.90)return "SCHEDULING_INTERFERENCE"
        val stride=sample.optInt("node_stride_bytes")
        val warmed=sample.optJSONArray("per_thread_warmup_operations")?.optLong(0)?:0
        if(stride<=0 || warmed<sample.optLong("working_set_bytes")/stride*2)return "WARMUP_INCOMPLETE"
        return null
    }
    internal fun summarize(bytes: Long, samples: List<JSONObject>): Point? {
        val values=samples.filter { qualityReason(it)==null }.map(RamResults::value).filter { it.isFinite()&&it>0 }
        if(values.isEmpty())return null
        val value=median(values)
        return Point(bytes,value,values.size>=3 && (values.max()-values.min())/value<=0.20,values.min(),values.max(),values.size)
    }
    internal fun edges(points: List<Point>): List<Edge> {
        val sorted=points.sortedBy(Point::bytes)
        return (2 until sorted.size-1).mapNotNull { i->
            val a=sorted[i-2];val b=sorted[i-1];val c=sorted[i];val d=sorted[i+1]
            // 两侧重复延迟及误差范围判定，带宽不参与筛选。 / Compare repeated latency/error ranges independently of bandwidth.
            if(listOf(a,b,c,d).any { !it.stable||it.latency<=0 } || b.bytes>a.bytes*2 || c.bytes>b.bytes*2 || d.bytes>c.bytes*2)return@mapNotNull null
            val low=maxOf(a.latency,b.latency);val high=minOf(c.latency,d.latency)
            if(low/minOf(a.latency,b.latency)>1.25 || high<low*1.30 || minOf(c.low,d.low)<=maxOf(a.high,b.high))null
            else Edge(b.bytes,c.bytes,(c.latency+d.latency)/(a.latency+b.latency))
        }.take(6)
    }
    fun run(topology: CpuTopology, memoryBudget: Long, shouldStop: ()->Boolean,
            sample: (cpu: Int, bytes: Long, stride: Int, seed: Long)->JSONObject,
            progress: (JSONObject)->Unit): JSONObject {
        val groups=JSONArray()
        val report=JSONObject().put("method",METHOD).put("state","RUNNING").put("groups",groups).put("scored",false)
            .put("minimum_repeats",3).put("maximum_repeats",5).put("minimum_warmup_passes",2)
            .put("relative_range_limit",0.20).put("minimum_cpu_time_ratio",0.90)
            .put("interpretation","Measured latency steps only; TLB, prefetch, DVFS and SLC can also cause steps.")
        val limit=minOf(64L*1048576,((memoryBudget-34L*1048576).coerceAtLeast(0)*8/9)/256*256)
        report.put("maximum_working_set_bytes",limit);progress(report)
        for(core in representatives(topology)){
            if(shouldStop())break
            val stride=topology.cache(core.id,1)?.lineBytes?:topology.dataLineBytes.takeIf { it in listOf(32,64,128,256) }?:64
            val records=JSONArray();val coarse=sizes(limit,topology.caches.filter { core.id in it.cpus }.map { it.bytes })
            val group=JSONObject().put("cpu_id",core.id).put("max_khz",core.maxKhz).put("frequency_domain",core.frequencyDomain)
                .put("node_stride_bytes",stride).put("stride_source",topology.cache(core.id,1)?.lineSource?:"probe-parameter")
                .put("samples",records).put("points",JSONArray()).put("transitions",JSONArray()).put("state","RUNNING")
                .put("planned_points",coarse.size).put("processed_points",0).put("rejected_trials",0)
            groups.put(group);val points=mutableListOf<Point>();var incomplete=false
            fun measure(bytes: Long){
                if(shouldStop())return
                val trials=mutableListOf<JSONObject>()
                report.put("current_cpu_id",core.id).put("current_working_set_bytes",bytes)
                for(repeat in 0 until 5){
                    if(shouldStop())return
                    report.put("current_repeat",repeat+1);progress(report)
                    val result=sample(core.id,bytes,stride,0xB16B00B5L+repeat)
                    val reason=qualityReason(result)
                    records.put(JSONObject().put("working_set_bytes",bytes).put("kind",5).put("repeat",repeat+1)
                        .put("accepted",reason==null).put("quality_reason",reason?:JSONObject.NULL).put("result",result))
                    if(reason!=null)group.put("rejected_trials",group.getInt("rejected_trials")+1)
                    trials+=result
                    if(repeat>=2 && summarize(bytes,trials)?.stable==true)break
                }
                val point=summarize(bytes,trials)
                if(point!=null)points+=point else incomplete=true
                group.put("processed_points",group.getInt("processed_points")+1)
                    .put("points",JSONArray(points.sortedBy(Point::bytes).map(Point::toJson)))
                progress(report)
            }
            coarse.forEach { if(!shouldStop())measure(it) }
            val confirmedCoarse=edges(points)
            // 先用中位数寻找疑似阶跃再细扫，波动点只能产生待复测区间。
            // Median-only candidates trigger refinement; noisy points can only yield unconfirmed intervals.
            val initial=edges(points.map { it.copy(stable=true,low=it.latency,high=it.latency) })
            val refined=initial.flatMap { e->(1..3).map { (e.lower+(e.upper-e.lower)*it/4)/256*256 } }
                .distinct().filter { bytes->points.none { it.bytes==bytes } && bytes in 4096..limit }
            group.put("planned_points",coarse.size+refined.size).put("coarse_transitions",JSONArray(confirmedCoarse.map(Edge::toJson)))
                .put("coarse_candidates",JSONArray(initial.map { it.toJson().put("confidence","needs-retest") }))
            refined.forEach { if(!shouldStop())measure(it) }
            // 细化需稳定的低、高两侧；否则保留粗区间。 / Narrow only with stable observations on both sides.
            val narrowed=confirmedCoarse.map { e->
                val base=points.first { it.bytes==e.lower }.latency
                val inside=points.filter { it.stable && it.bytes in e.lower..e.upper }.sortedBy(Point::bytes)
                val high=inside.firstOrNull { it.latency>=base*1.30 }
                val low=high?.let { h->inside.lastOrNull { it.bytes<h.bytes && it.latency<=base*1.20 && it.high<h.low } }
                if(high!=null&&low!=null)Edge(low.bytes,high.bytes,high.latency/low.latency)else e
            }
            val verified=mutableListOf<Edge>()
            (edges(points)+narrowed).sortedBy { it.upper-it.lower }.forEach { edge->
                if(verified.none { it.lower<edge.upper && edge.lower<it.upper })verified+=edge
            }
            val pending=initial.filter { edge->verified.none { it.lower<edge.upper && edge.lower<it.upper } }
            val stable=points.count(Point::stable)
            group.put("transitions",JSONArray(verified.sortedBy(Edge::lower).map(Edge::toJson)))
                .put("candidate_intervals",JSONArray(pending.map { it.toJson().put("confidence","needs-retest") }))
                .put("stable_points",stable)
                .put("state",when { shouldStop()->"CANCELLED";points.isEmpty()->"UNAVAILABLE";incomplete||stable<points.size*0.7->"UNSTABLE";else->"COMPLETED" })
            progress(report)
        }
        report.put("state",when { shouldStop()->"CANCELLED";limit<4096->"BUDGET_UNAVAILABLE";groups.length()==0->"UNAVAILABLE"
            (0 until groups.length()).any { groups.getJSONObject(it).optString("state")!="COMPLETED" }->"UNSTABLE";else->"COMPLETED" })
        progress(report);return report
    }
}
