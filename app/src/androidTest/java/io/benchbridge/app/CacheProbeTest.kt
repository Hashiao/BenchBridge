package io.benchbridge.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.benchbridge.app.hardware.*
import io.benchbridge.app.ram.*
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CacheProbeTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private fun target()=CpuTopology((0..7).map { id ->
        CpuCore(id,"cpu$id",0,when(id){6,7->5000000;3,4,5->4000000;else->3740000},"","",true)
    },emptyList(),SocCatalog(context).match(listOf("8EE6")),"test",dataLineBytes=64,dataLineSource="runtime-ctr-el0-minimum")

    @Test fun extremeUsesThreeFrequencyGroupsAndOneSharedL2WithoutInventingL3() {
        val topology=CpuTopology.withCatalog(target())
        assertEquals(listOf(2,3,3),topology.soc!!.getJSONArray("cpu_groups").let { a -> (0 until a.length()).map { a.getJSONObject(it).getInt("count") } })
        assertEquals(8,topology.caches.count { it.level==1 })
        assertEquals(98304L,topology.cache(7,1)!!.bytes)
        assertEquals(65536L,topology.cache(0,1)!!.bytes)
        assertEquals(1,topology.caches.count { it.level==2 })
        assertEquals(16777216L,topology.cache(0,2)!!.bytes)
        assertEquals((0..7).toList(),topology.cache(7,2)!!.cpus)
        assertEquals("runtime-ctr-el0-minimum",topology.cache(7,1)!!.lineSource)
        assertNull(topology.cache(0,3))
        val config=RamConfig.matrixQuick()
        for (level in listOf("L1","L2")) for(kind in MemoryPlanner.columns)
            assertTrue("$level $kind",MemoryPlanner.candidates(topology,config,level,kind,512L*1048576).isNotEmpty())
        val plans=MemoryPlanner.candidates(topology,config,"L2",0,512L*1048576)
        assertTrue(plans.any { it.cpus==listOf(0,1,2) }); assertTrue(plans.any { it.cpus==listOf(3,4,5) })
        assertTrue(plans.all { it.bytes<=12L*1048576 })
        assertEquals(3,CacheProbe.representatives(topology).size)
    }

    @Test fun ambiguousCoreNumbersAndUnknownLineDoNotBecomeInventedSpecifications() {
        val unknown=target().copy(cores=target().cores.map { it.copy(maxKhz=0) })
        val topology=CpuTopology.withCatalog(unknown)
        assertNull(topology.cache(0,1));assertNotNull(topology.cache(0,2))
        assertTrue(CpuTopology.withCatalog(target().copy(dataLineBytes=0)).caches.isEmpty())
        val runtime=CpuCache("L2:0",2,1048576,128,listOf(0),"runtime-sysfs")
        val mixed=CpuTopology.withCatalog(target().copy(caches=listOf(runtime)))
        assertEquals(runtime,mixed.cache(0,2)); assertNull(mixed.cache(7,2))
        assertTrue(CpuTopology.withCatalog(target().copy(cores=target().cores.take(7))).caches.isEmpty())
    }


    private fun batch(latency:Double, accepted:Boolean=true):JSONObject=JSONObject().put("status","COMPLETED").put("verified",true)
        .put("trials",JSONArray((0..4).map { JSONObject().put("elapsed_ns",(latency*1000000).toLong()).put("operations",1000000).put("accepted",accepted) }))

    @Test fun fullCurveFindsMultipleTransitionsWithoutRewritingMeasurements() {
        val grid=CacheProbe.sizes(128L*1048576,emptyList())
        assertTrue(grid.size>=120);assertEquals(4096L,grid.first());assertEquals(128L*1048576,grid.last())
        assertTrue(grid.zipWithNext().all { (a,b)->b.toDouble()/a<=1.13 })
        val points=grid.map { bytes->CacheProbe.Point(bytes,when { bytes<=65536->1.0;bytes<=1048576->4.0;bytes<=16777216->15.0;else->140.0 },true) }
        val original=points.map { it.toJson().toString() }
        val analysis=LatencyAnalysis.analyze(points,grid)
        assertEquals("COMPLETE",analysis.getString("status"))
        assertEquals(4,analysis.getJSONArray("regions").length());assertEquals(3,analysis.getJSONArray("transitions").length())
        assertEquals(original,points.map { it.toJson().toString() })
        val incomplete=LatencyAnalysis.analyze(points.filterIndexed { i,_->i!=10 },grid)
        assertEquals("PARTIAL",incomplete.getString("status"));assertEquals(3,incomplete.getJSONArray("transitions").length())
        assertEquals(grid[10],incomplete.getJSONArray("unresolved_intervals").getJSONObject(0).getLong("lower_bytes"))
        val gap=grid.indexOf(1048576L)
        val missingStep=LatencyAnalysis.analyze(points.filterIndexed { i,_->i !in gap-1..gap+1 },grid)
        val remaining=missingStep.getJSONArray("transitions")
        assertEquals(2,remaining.length())
        for(i in 0 until remaining.length())assertFalse(1048576L in remaining.getJSONObject(i).getLong("lower_bytes")..remaining.getJSONObject(i).getLong("upper_bytes"))
    }

    @Test fun smoothTrendAndAnIsolatedSpikeDoNotInventMultipleCacheLevels() {
        val grid=CacheProbe.sizes(16L*1048576,emptyList())
        val ramp=grid.map { CacheProbe.Point(it,kotlin.math.sqrt(it/4096.0),true) }
        assertEquals(1,LatencyAnalysis.analyze(ramp,grid).getJSONArray("regions").length())
        val spike=grid.mapIndexed { i,b->CacheProbe.Point(b,if(i==40)2.0 else 1.0,true) }
        assertEquals(0,LatencyAnalysis.analyze(spike,grid).getJSONArray("transitions").length())
    }

    @Test fun observedFullSweepKeepsVerifiedRegionsWithoutBridgingInterference() {
        val fixture=JSONObject(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets
            .open("cache_curve_observed.json").bufferedReader().use { it.readText() })
        val groups=fixture.getJSONArray("groups")
        for(i in 0 until groups.length()) {
            val group=groups.getJSONObject(i);val data=group.getJSONArray("points");val grid=group.getJSONArray("planned_sizes")
            val points=(0 until data.length()).map { n->val p=data.getJSONObject(n);CacheProbe.Point(p.getLong("working_set_bytes"),p.getDouble("latency_ns"),p.getBoolean("stable")) }
            val analysis=LatencyAnalysis.analyze(points,(0 until grid.length()).map { grid.getLong(it) })
            assertEquals("PARTIAL",analysis.getString("status"));assertTrue(analysis.getJSONArray("regions").length()>0)
            val edges=analysis.getJSONArray("transitions");assertTrue(edges.length()>0)
            for(j in 0 until edges.length()) {
                val e=edges.getJSONObject(j)
                assertTrue(points.filter { it.bytes in e.getLong("lower_bytes")..e.getLong("upper_bytes") }.all { it.stable })
            }
        }
    }

    @Test fun bidirectionalAgreementRejectsDriftAndRetainsMedianRatherThanFastest() {
        val a=batch(10.0);val b=batch(10.5)
        val point=CacheProbe.point(4096,a,b)!!
        assertTrue(point.stable);assertEquals(10.25,point.latency,0.001);assertEquals(10,point.trials)
        assertFalse(CacheProbe.point(4096,a,batch(20.0))!!.stable)
        assertFalse(CacheProbe.point(4096,a,null)!!.stable)
        assertNull(CacheProbe.point(4096,batch(1.0,false),batch(1.0,false)))
    }

    @Test fun nativePointVerifiesFullCycleRecordsTimeAndHonorsCancellation() {
        val cpu=JSONObject(RamNative.capabilities()).getJSONArray("allowed_cpu_ids").getInt(0)
        val handle=RamNative.createSession()
        try {
            val r=JSONObject(RamNative.runLatencyPoint(handle,cpu,65536,64,1234))
            assertEquals(r.toString(),"COMPLETED",r.getString("status"));assertTrue(r.getBoolean("verified"))
            assertEquals("dependent-index32-v1",r.getString("kernel"));assertEquals("global-random-high-entropy",r.getString("pattern"))
            assertEquals(cpu,r.getInt("cpu_id"));assertEquals(1,r.getInt("allocation_count"));assertEquals(1024,r.getInt("chain_verified_nodes"))
            assertTrue(r.getLong("warmup_operations")>=2048);assertTrue(r.getLong("page_size_bytes")>=4096)
            val trials=r.getJSONArray("trials");assertTrue(trials.length() in 5..9)
            for(i in 0 until trials.length()) {
                val t=trials.getJSONObject(i);assertTrue(t.getLong("cpu_elapsed_ns")>0);assertTrue(t.getLong("operations")>0)
                assertTrue(t.has("frequency_before_khz"));assertTrue(t.has("frequency_after_khz"))
                if(t.getBoolean("accepted"))assertTrue(t.getLong("cpu_elapsed_ns")>=t.getLong("elapsed_ns")*0.90)
            }
            RamNative.cancelSession(handle)
            assertEquals("INTERRUPTED",JSONObject(RamNative.runLatencyPoint(handle,cpu,4096,64,1)).getString("status"))
        } finally { RamNative.releaseSession(handle) }
    }

    @Test fun curveProtocolKeepsLegacyReportsAndRamRoundCountsSeparate() {
        val config=RamConfig.matrixQuick()
        assertTrue(config.curveMode);assertTrue(config.curveIncludeRam);assertEquals(4,config.totalRounds);assertEquals(listOf("RAM"),config.scoredLevels)
        assertTrue(RamConfig.aida64().curveIncludeRam);assertEquals(12,RamConfig.aida64().totalRounds)
        assertEquals(config,RamConfig.fromJson(config.toJson().toString()))
        val old=config.toJson().apply { remove("cache_curve");remove("cache_probe_method") }.toString()
        assertEquals(16,RamConfig.fromJson(old).totalRounds)
        val v09=config.toJson().apply { remove("curve_include_ram");remove("curve_steps");remove("curve_max_mib") }
        v09.put("cache_probe_method","latency-step-sweep-v2")
        assertEquals(4,RamConfig.fromJson(v09.toString()).totalRounds)
        assertFalse(RamConfig.fromJson(v09.toString()).summary.contains("正反两遍"))
        assertEquals("latency-step-sweep-v2",RamConfig.fromJson(v09.toString()).toJson().getString("cache_probe_method"))
        val v010=config.toJson().put("curve_include_ram",false)
        val frozen=RamConfig.fromJson(v010.toString())
        assertFalse(frozen.curveIncludeRam);assertEquals(0,frozen.totalRounds)
        assertEquals(v010.toString(),frozen.toJson().toString())
        val historical=JSONObject().put("state","PARTIAL").put("config",v010).put("cache_probe",JSONObject().put("state","INCOMPLETE"))
        assertEquals("曲线部分范围未通过验证",RamResults.stateLabel(historical))
        assertNull(RamResults.statistics(historical,5))
    }

    @Test fun sweepRefinesMultipleStepsAndResumesWithoutRemeasuringCompletedPairs() {
        val topology=CpuTopology(listOf(CpuCore(0,"cpu0",1,1000000,"","",true)),emptyList(),null,"test",dataLineBytes=64)
        val config=RamConfig.matrixQuick().copy(curveMaxMiB=4,curveSteps=4)
        var calls=0;var stop=false
        val sampler={_:Int,b:Long,_:Int,_:Long->calls++;batch(when { b<=65536->1.0;b<=1048576->4.0;else->40.0 })}
        val previous=CacheProbe.run(topology,64L*1048576,{stop},sampler,{p->if(p.optInt("completed_points")==7)stop=true},config)
        assertEquals("CANCELLED",previous.getString("state"));assertEquals(7,calls)
        val original=previous.getJSONArray("groups").getJSONObject(0).getJSONArray("samples").toString()
        stop=false
        val result=CacheProbe.run(topology,64L*1048576,{false},sampler,{},config,JSONObject(previous.toString()))
        val g=result.getJSONArray("groups").getJSONObject(0)
        assertEquals("COMPLETED",result.getString("state"));assertEquals(2,g.getJSONArray("transitions").length())
        assertEquals(calls,g.getJSONArray("samples").length());assertEquals(result.getInt("planned_points"),result.getInt("completed_points"))
        assertEquals(original,previous.getJSONArray("groups").getJSONObject(0).getJSONArray("samples").toString())
        assertTrue(g.getJSONArray("planned_sizes").length()>CacheProbe.sizes(4L*1048576,emptyList(),4).size)
    }

    @Test fun failedVerificationIsBoundedAndNeverBecomesAUserFacingCandidate() {
        val topology=CpuTopology(listOf(CpuCore(0,"cpu0",1,1000000,"","",true)),emptyList(),null,"test",dataLineBytes=64)
        val config=RamConfig.matrixQuick().copy(curveMaxMiB=1,curveSteps=2)
        var calls=0
        val report=CacheProbe.run(topology,64L*1048576,{false},{_,_,_,_->calls++;batch(1.0,false)},{},config)
        val g=report.getJSONArray("groups").getJSONObject(0)
        assertEquals("INCOMPLETE",report.getString("state"));assertEquals(0,g.getJSONArray("transitions").length())
        assertFalse(g.has("candidate_intervals"));assertEquals(report.getInt("planned_points")*3,calls)
        val resumed=CacheProbe.run(topology,64L*1048576,{false},{_,_,_,_->error("retry budget reset")},{},config,report)
        assertEquals("INCOMPLETE",resumed.getString("state"))
    }
}
