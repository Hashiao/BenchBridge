package io.benchbridge.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.benchbridge.app.hardware.*
import io.benchbridge.app.ram.*
import org.json.JSONObject
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
        fun curve(latency:List<Double>)=latency.mapIndexed { i,n -> CacheProbe.Point(4096L shl i,n,if(i<3)100.0 else 60.0,true) }
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
            val report=CacheProbe.run(topology,35L*1048576,{stopped},{id,bytes,kind,stride,seed->
                val sample=JSONObject(RamNative.runPinnedRound(handle,kind,longArrayOf(bytes),intArrayOf(id),0,50,seed,stride))
                assertEquals(sample.toString(),"COMPLETED",sample.getString("status"));assertTrue(sample.getBoolean("verified"))
                calls++;sample
            },{probe->if(probe.getJSONArray("groups").optJSONObject(0)?.getJSONArray("points")?.length()==2)stopped=true})
            assertEquals("CANCELLED",report.getString("state")); assertFalse(report.getBoolean("scored"))
            assertEquals(8,calls);assertEquals(2,report.getJSONArray("groups").getJSONObject(0).getJSONArray("points").length())
        } finally { RamNative.releaseSession(handle) }
    }
}
