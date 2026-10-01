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

    @Test fun sweepDetectsPersistentTransitionsButRejectsNoiseAndMissingPoints() {
        fun curve(latency:List<Double>)=latency.mapIndexed { i,n -> CacheProbe.Point(4096L shl i,n,true) }
        val clear=curve(listOf(1.0,1.02,1.0,2.0,2.1,2.0))
        val edges=CacheProbe.edges(clear)
        assertEquals(1,edges.size); assertEquals(16384L,edges[0].lower); assertEquals(32768L,edges[0].upper)
        assertTrue(CacheProbe.edges(curve(listOf(1.0,1.0,1.0,3.0,1.0,1.0))).isEmpty())
        assertTrue(CacheProbe.edges(clear.mapIndexed { i,p -> if(i==3)p.copy(stable=false)else p }).isEmpty())
        assertTrue(CacheProbe.edges(clear.filterIndexed { i,_ -> i!=3 }).isEmpty())
        val sizes=CacheProbe.sizes(2L*1048576,listOf(98304))
        assertTrue(98304L in sizes); assertTrue(sizes.zipWithNext().all { (a,b)->a<b });assertTrue(sizes.all { it%256==0L && it<=2L*1048576 })
    }

    @Test fun nativeSweepProducesVerifiedUnscoredSamplesAndHonorsCancellation() {
        val cpu=JSONObject(RamNative.capabilities()).getJSONArray("allowed_cpu_ids").getInt(0)
        val topology=CpuTopology(listOf(CpuCore(cpu,"cpu$cpu",0,0,"","",true)),emptyList(),null,"test")
        val handle=RamNative.createSession()
        try {
            var stopped=false;var calls=0
            val report=CacheProbe.run(topology,35L*1048576,{stopped},{id,bytes,stride,seed->
                val sample=JSONObject(RamNative.runPinnedRound(handle,5,longArrayOf(bytes),intArrayOf(id),0,50,seed,stride,2))
                assertEquals(sample.toString(),"COMPLETED",sample.getString("status"));assertTrue(sample.getBoolean("verified"))
                assertTrue(sample.getJSONArray("per_thread_warmup_operations").getLong(0)>=bytes/stride*2)
                assertTrue(sample.getJSONArray("per_thread_cpu_elapsed_ns").getLong(0)>0)
                calls++;sample
            },{probe->if(probe.getJSONArray("groups").optJSONObject(0)?.optInt("processed_points")==2)stopped=true})
            assertEquals("CANCELLED",report.getString("state")); assertFalse(report.getBoolean("scored"))
            assertTrue(calls in 6..10);assertEquals(2,report.getJSONArray("groups").getJSONObject(0).getInt("processed_points"))
        } finally { RamNative.releaseSession(handle) }
    }

    private fun sample(latency: Double, bytes: Long=4096):JSONObject=JSONObject().put("kind",5).put("status","COMPLETED").put("verified",true)
        .put("operations",(150000000/latency).toLong()).put("elapsed_ns",150000000).put("requested_duration_ms",150)
        .put("working_set_bytes",bytes).put("node_stride_bytes",64).put("per_thread_cpu_elapsed_ns",JSONArray(listOf(149000000)))
        .put("per_thread_warmup_operations",JSONArray(listOf(bytes/64*2)))

    @Test fun longPauseAndIncompleteWarmupNeverBecomeLatencySteps() {
        val interrupted=sample(1.0).put("elapsed_ns",11129188277L)
        assertEquals("TIMING_OVERRUN",CacheProbe.qualityReason(interrupted))
        assertEquals("SCHEDULING_INTERFERENCE",CacheProbe.qualityReason(sample(1.0).put("per_thread_cpu_elapsed_ns",JSONArray(listOf(70000000)))))
        assertEquals("WARMUP_INCOMPLETE",CacheProbe.qualityReason(sample(1.0).put("per_thread_warmup_operations",JSONArray(listOf(1)))))
        val point=CacheProbe.summarize(4096,listOf(sample(1.0),sample(1.02),sample(1.04),interrupted))!!
        assertTrue(point.stable);assertEquals(3,point.trials);assertEquals(1.02,point.latency,0.001)
        assertFalse(CacheProbe.summarize(4096,listOf(sample(1.0),sample(2.0),sample(3.0)))!!.stable)
    }

    @Test fun curveProtocolKeepsLegacyReportsAndRamRoundCountsSeparate() {
        val config=RamConfig.matrixQuick()
        assertTrue(config.curveMode);assertEquals(4,config.totalRounds);assertEquals(listOf("RAM"),config.scoredLevels)
        assertEquals(config,RamConfig.fromJson(config.toJson().toString()))
        val old=config.toJson().apply { remove("cache_curve");remove("cache_probe_method") }.toString()
        val decoded=RamConfig.fromJson(old)
        assertFalse(decoded.curveMode);assertEquals(16,decoded.totalRounds)
        assertEquals(old,JSONObject(old).toString())
    }

    @Test fun latencySweepRefinesARealStepWithoutBandwidthAndStaysWithinBudget() {
        val cpu=CpuCore(0,"cpu0",1,1000000,"","",true)
        val topology=CpuTopology(listOf(cpu),emptyList(),null,"test",dataLineBytes=64)
        val visited=mutableListOf<Long>()
        val report=CacheProbe.run(topology,35L*1048576,{false},{_,bytes,_,_->
            visited+=bytes;sample(if(bytes<=65536)1.0 else 4.0,bytes)
        },{})
        val group=report.getJSONArray("groups").getJSONObject(0)
        assertEquals("COMPLETED",report.getString("state"))
        val edge=group.getJSONArray("transitions").getJSONObject(0)
        assertEquals(65536L,edge.getLong("lower_bytes"));assertTrue(edge.getLong("upper_bytes")<98304)
        assertTrue(visited.distinct().size>CacheProbe.sizes(report.getLong("maximum_working_set_bytes"),emptyList()).size)
        assertTrue(visited.max()<=report.getLong("maximum_working_set_bytes"))
        assertTrue(edge.isNull("cache_level"))
    }

    @Test fun noisyStepsTriggerRefinementButRemainUnconfirmed() {
        val cpu=CpuCore(0,"cpu0",1,1000000,"","",true)
        val topology=CpuTopology(listOf(cpu),emptyList(),null,"test",dataLineBytes=64)
        val report=CacheProbe.run(topology,35L*1048576,{false},{_,bytes,_,seed->
            sample((if(bytes<=65536)1.0 else 4.0)*(if(seed%2==0L)1.8 else 1.0),bytes)
        },{})
        val group=report.getJSONArray("groups").getJSONObject(0)
        assertEquals("UNSTABLE",report.getString("state"))
        assertEquals(0,group.getJSONArray("transitions").length())
        assertTrue(group.getJSONArray("candidate_intervals").length()>0)
        assertEquals("needs-retest",group.getJSONArray("candidate_intervals").getJSONObject(0).getString("confidence"))
        assertTrue(group.getInt("planned_points")>CacheProbe.sizes(report.getLong("maximum_working_set_bytes"),emptyList()).size)
    }
}
