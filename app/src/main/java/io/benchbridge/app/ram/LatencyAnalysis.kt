package io.benchbridge.app.ram

import io.benchbridge.app.i18n.L10n

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.*

/** 全曲线分段，仅用于解释实测中位数，不重写绘图数据。
 * Segment the entire measured curve for interpretation without rewriting plotted medians. */
object LatencyAnalysis {
    fun size(bytes: Long): String = if(bytes>=1048576)"%.3g MiB".format(Locale.US,bytes/1048576.0)else"%.3g KiB".format(Locale.US,bytes/1024.0)
    fun analyze(points: List<CacheProbe.Point>, expected: List<Long>): JSONObject {
        val sorted=points.filter { it.stable && it.latency>0 }.sortedBy { it.bytes }
        val coverage=if(expected.isEmpty())0.0 else sorted.count { it.bytes in expected }.toDouble()/expected.size
        val output=JSONObject().put("method","penalized-log-linear-segments-v1").put("coverage",coverage)
            .put("transitions",JSONArray()).put("regions",JSONArray()).put("unresolved_intervals",JSONArray())
        if(sorted.size<8)
            return output.put("status","INCOMPLETE").put("summary",L10n.t("m_2251b8722155", sorted.size, expected.size))
        if(coverage<1.0) {
            // 仅分析连续有效片段，缺口两侧绝不拼接成阶跃。 / Analyze contiguous valid spans without inferring transitions across gaps.
            val valid=sorted.associateBy { it.bytes };val regions=output.getJSONArray("regions");val edges=output.getJSONArray("transitions")
            var start=0
            while(start<expected.size) {
                val accepted=valid.containsKey(expected[start]);var end=start+1
                while(end<expected.size && valid.containsKey(expected[end])==accepted)end++
                if(accepted && end-start>=8) {
                    val grid=expected.subList(start,end);val part=analyze(grid.map { valid.getValue(it) },grid)
                    val r=part.getJSONArray("regions");val e=part.getJSONArray("transitions")
                    for(i in 0 until r.length())regions.put(r.getJSONObject(i).put("index",regions.length()+1))
                    for(i in 0 until e.length())edges.put(e.getJSONObject(i))
                } else {
                    output.getJSONArray("unresolved_intervals").put(JSONObject().put("lower_bytes",expected[start]).put("upper_bytes",expected[end-1])
                        .put("reason",if(accepted)"insufficient_contiguous_points"else"inconsistent_or_missing_measurement"))
                }
                start=end
            }
            return output.put("status","PARTIAL").put("summary",L10n.t("m_570ccb623ee4", sorted.size, expected.size, regions.length(), edges.length(), output.getJSONArray("unresolved_intervals").length()))
        }
        val n=sorted.size;val y=sorted.map { ln(it.latency) };val x=sorted.map { ln(it.bytes.toDouble()) }
        val prefix=DoubleArray(n+1);val squares=DoubleArray(n+1)
        val px=DoubleArray(n+1);val pxx=DoubleArray(n+1);val pxy=DoubleArray(n+1)
        for(i in 0 until n){prefix[i+1]=prefix[i]+y[i];squares[i+1]=squares[i]+y[i]*y[i]
            px[i+1]=px[i]+x[i];pxx[i+1]=pxx[i]+x[i]*x[i];pxy[i+1]=pxy[i]+x[i]*y[i]}
        fun slope(start:Int,end:Int):Double {
            val count=end-start;val sumX=px[end]-px[start];val sumY=prefix[end]-prefix[start]
            return (pxy[end]-pxy[start]-sumX*sumY/count)/(pxx[end]-pxx[start]-sumX*sumX/count).coerceAtLeast(1e-12)
        }
        val differences=y.zipWithNext { a,b->abs(b-a) }
        val noise=(CacheProbe.median(differences)/0.954).coerceIn(0.02,0.20)
        val penalty=maxOf(0.10,3*ln(n.toDouble())*noise*noise)
        val dp=DoubleArray(n+1){Double.POSITIVE_INFINITY};val previous=IntArray(n+1){-1};dp[0]=-penalty
        for(end in 4..n)for(start in 0..end-4){
            if(!dp[start].isFinite())continue
            val sum=prefix[end]-prefix[start]
            val covariance=pxy[end]-pxy[start]-(px[end]-px[start])*sum/(end-start)
            val cost=(squares[end]-squares[start]-sum*sum/(end-start)-slope(start,end)*covariance).coerceAtLeast(0.0)
            if(dp[start]+cost+penalty<dp[end]){dp[end]=dp[start]+cost+penalty;previous[end]=start}
        }
        val parts=mutableListOf<Pair<Int,Int>>();var end=n
        while(end>0){val start=previous[end];if(start<0)break;parts+=start to end;end=start}
        parts.reverse()
        // 合并幅度过小的拟合分段，保留多处可重复的转换。 / Merge insignificant splits while retaining multiple reproducible transitions.
        val merged=mutableListOf<Pair<Int,Int>>()
        for(part in parts){
            val last=merged.lastOrNull()
            val a=last?.let { CacheProbe.median(sorted.subList(it.first,it.second).map { p->p.latency }) }
            val b=CacheProbe.median(sorted.subList(part.first,part.second).map { it.latency })
            if(last!=null && maxOf(a!!,b)/minOf(a,b)<1.25)merged[merged.lastIndex]=last.first to part.second else merged+=part
        }
        val regions=merged.mapIndexed { index,part->
            val values=sorted.subList(part.first,part.second)
            JSONObject().put("index",index+1).put("lower_bytes",values.first().bytes).put("upper_bytes",values.last().bytes)
                .put("latency_ns",CacheProbe.median(values.map { it.latency })).put("sample_points",values.size)
                .put("log_slope",slope(part.first,part.second)).put("trend",if(abs(slope(part.first,part.second))<0.15)"plateau"else if(slope(part.first,part.second)>0)"rising"else"falling")
                .put("minimum_ns",values.minOf { it.low }).put("maximum_ns",values.maxOf { it.high })
        }
        val transitions=mutableListOf<JSONObject>()
        for(i in 1 until merged.size){
            val left=merged[i-1];val right=merged[i]
            val a=sorted[left.second-1];val b=sorted[right.first]
            val before=regions[i-1].getDouble("latency_ns");val after=regions[i].getDouble("latency_ns")
            if(b.bytes.toDouble()/a.bytes>1.6 || maxOf(before,after)/minOf(before,after)<1.25)continue
            transitions+=JSONObject().put("lower_bytes",a.bytes).put("upper_bytes",b.bytes).put("before_ns",before)
                .put("after_ns",after).put("latency_ratio",after/before).put("direction",if(after>before)"increase"else"decrease")
                .put("kind",if(maxOf(a.latency,b.latency)/minOf(a.latency,b.latency)>=1.25)"step"else"slope_change")
                .put("confidence","bidirectional-repeated").put("cache_level",JSONObject.NULL)
        }
        output.put("regions",JSONArray(regions)).put("transitions",JSONArray(transitions)).put("status","COMPLETE")
            .put("summary",L10n.t("m_dcd0dc09bb78", size(expected.first()), size(expected.last()), regions.size, transitions.size))
        return output
    }
}
