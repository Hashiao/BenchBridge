package io.benchbridge.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.benchbridge.app.hardware.*
import io.benchbridge.app.ram.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 根据用户报告重建最小调度夹具，不包含用户标识或真实跑分。
 * Reconstruct a minimal scheduling fixture without user identifiers or actual benchmark scores. */
@RunWith(AndroidJUnit4::class)
class AffinityRecoveryTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val topology=CpuTopology((0..7).map { cpu ->
        val group=when(cpu){6,7->"6,7";3,4,5->"3,4,5";else->"0,1,2"}
        CpuCore(cpu,"$cpu",if(cpu>=6)1024 else if(cpu>=3)647 else 600,
            if(cpu>=6)5011200 else if(cpu>=3)4032000 else 3744000,"3",group,true)
    },emptyList(),null,"fixture",dataLineBytes=64)
    private val config=RamConfig.aida64().copy(warmupMs=0,durationMs=150,cooldownMs=0,calibrationMs=50,curveMaxMiB=1,curveSteps=2)
    private val budget=1024L*1048576
    private fun failure(cpu:Int)=JSONObject().put("status","FAILED").put("error","AFFINITY_VERIFY_FAILED")
        .put("affinity_diagnostics",JSONObject().put("workers",JSONArray().put(JSONObject().put("requested_cpu",cpu)
            .put("failure_reason","readback_not_singleton").put("readback",JSONObject().put("return_code",0).put("errno",0)
                .put("cpu_ids",JSONArray(listOf(3,4,5,6)))))))
    private fun measured(plan:MemoryPlan):JSONObject {
        val weight=plan.cpus.firstOrNull()?.let { topology.cores[it].capacity }?:500L
        return JSONObject().put("status","COMPLETED").put("verified",true).put("elapsed_ns",1000000000)
            .put("operations",weight*1000000).put("logical_bytes",weight*1000000)
            .put("threads",plan.threads).put("working_set_bytes",plan.bytes).put("requested_cpus",JSONArray(plan.cpus))
    }
    private fun curve()=JSONObject().put("status","COMPLETED").put("verified",true)
        .put("trials",JSONArray().put(JSONObject().put("elapsed_ns",10000000).put("operations",1000000).put("accepted",true)))
    private fun report()=JSONObject().put("run_id","test").put("config",config.toJson()).put("rounds",JSONArray())
        .put("quality_flags",JSONArray()).put("total_rounds",12).put("completed_rounds",0).put("processed_rounds",0)
    private fun execute(report:JSONObject, pinned:(MemoryPlan,Int,Int)->JSONObject,
                        system:(MemoryPlan,Int,Int)->JSONObject={_,_,_->error("unexpected fallback")}):String =
        MemoryMatrixRunner(context,config,report,0,topology,budget,{false},{0},{error(it)},{},
            samplePinned=pinned,sampleSystem=system,sampleCurve={_,_,_,_->curve()}).execute()

    @Test fun healthyPathKeepsOriginalCandidateOrderSizesAndRoundCounts() {
        val report=report();val calibration=mutableListOf<MemoryPlan>();val formal=mutableListOf<MemoryPlan>()
        assertEquals("COMPLETED",execute(report,{plan,_,_->
            if(report.optString("phase")=="CALIBRATING")calibration+=plan else formal+=plan
            measured(plan)
        }))
        val expected=MemoryPlanner.columns.flatMap { kind->MemoryPlanner.candidates(topology,config,"RAM",kind,budget).flatMap { listOf(it,it) } }
        assertEquals(expected,calibration);assertEquals(12,formal.size)
        assertTrue(formal.all { it.cpus==listOf(6) && it.bytes==64L*1048576 && !it.systemScheduled })
        assertFalse(report.has("affinity_recovery"));assertFalse(report.has("discarded_rounds"))
        assertEquals(12,report.getInt("completed_rounds"))
    }

    @Test fun expandedMaskRetriesSameGroupAndKeepsFailureEvidence() {
        val report=report();val requested=mutableListOf<Int>()
        assertEquals("COMPLETED",execute(report,{plan,_,_->
            requested+=plan.cpus.first();if(plan.cpus==listOf(6))failure(6) else measured(plan)
        }))
        assertEquals(listOf(6,7),requested.take(2));assertEquals(1,requested.count { it==6 })
        for(kind in MemoryPlanner.columns)assertEquals("[7]",RamResults.cell(report,"RAM",kind)!!.getJSONObject("plan").getJSONArray("cpu_ids").toString())
        assertEquals(7,report.getJSONObject("cache_probe").getJSONArray("groups").getJSONObject(0).getInt("cpu_id"))
        assertEquals("[3,4,5,6]",report.getJSONObject("failure_context").getJSONObject("sample").getJSONObject("affinity_diagnostics")
            .getJSONArray("workers").getJSONObject(0).getJSONObject("readback").getJSONArray("cpu_ids").toString())
    }

    @Test fun allBindingFailuresFallBackOncePerCellAndNeverCreateCoreCurves() {
        val report=report();var attempts=0;var fallback=0
        assertEquals("PARTIAL",execute(report,{plan,_,_->attempts++;failure(plan.cpus.first())},{plan,_,_->fallback++;measured(plan)}))
        assertEquals(8,attempts);assertEquals(12,fallback);assertEquals(12,report.getInt("completed_rounds"))
        for(kind in MemoryPlanner.columns) {
            val plan=RamResults.cell(report,"RAM",kind)!!.getJSONObject("plan")
            assertEquals("system_scheduled",plan.getString("binding_mode"));assertEquals(0,plan.getJSONArray("cpu_ids").length())
            assertEquals(1,plan.getInt("threads"));assertEquals(64L*1048576,plan.getLong("working_set_bytes"))
            if(kind==5)assertEquals(128,plan.getInt("node_stride_bytes"))
        }
        val probe=report.getJSONObject("cache_probe");assertEquals(0,probe.getInt("completed_points"))
        val groups=probe.getJSONArray("groups")
        for(i in 0 until groups.length())assertEquals("AFFINITY_UNAVAILABLE",groups.getJSONObject(i).getString("state"))
    }

    @Test fun formalAffinityLossRestartsAllRoundsWithoutMixingScores() {
        val report=report();var failed=false
        assertEquals("COMPLETED",execute(report,{plan,_,_->
            if(!failed&&plan.kind==0&&report.optString("phase")=="PREPARING"&&report.optInt("current_round")==2) {
                failed=true;failure(plan.cpus.first())
            } else measured(plan)
        }))
        assertTrue(failed);assertEquals(12,report.getInt("completed_rounds"));assertEquals(12,report.getInt("processed_rounds"))
        assertEquals(12,report.getJSONArray("rounds").length());assertEquals(2,report.getJSONArray("discarded_rounds").length())
        assertEquals(1,RamResults.cell(report,"RAM",0)!!.getInt("affinity_restarts"))
        assertTrue(RamResults.validRounds(report,0).all { it.getJSONArray("requested_cpus").toString()=="[7]" })
        for(i in 0..1)assertFalse(report.getJSONArray("discarded_rounds").getJSONObject(i).getBoolean("scored"))
        assertEquals(4,report.getJSONArray("effective_plan").length())
    }

    @Test fun dataVerificationErrorsRemainFatalAndInstabilityDoesNotTriggerFallback() {
        val report=report()
        try { execute(report,{_,_,_->JSONObject().put("status","FAILED").put("error","RAM_VERIFY_MISMATCH")});fail("Data errors must remain fatal") }
        catch(expected:IllegalStateException) { assertEquals("RAM_VERIFY_MISMATCH",expected.message) }
        assertFalse(report.has("affinity_recovery"))
        var calls=0;val unstable=report()
        assertEquals("FAILED",execute(unstable,{plan,_,_->measured(plan).put("logical_bytes",if(calls++%2==0)100 else 1000)
            .put("operations",if(calls%2==0)100 else 1000)}))
        assertFalse(unstable.has("affinity_recovery"));assertEquals(0,unstable.getInt("completed_rounds"))
    }

    @Test fun curveRetriesBeforeFirstPointThenResumesOnTheSameReplacementCpu() {
        var stop=false;var calls=0
        val sampler={cpu:Int,_:Long,_:Int,_:Long->calls++;if(cpu==6)failure(cpu)else curve()}
        val first=CacheProbe.run(topology,budget,{stop},sampler,{if(it.optInt("completed_points")==5)stop=true},config)
        assertEquals("CANCELLED",first.getString("state"));assertEquals(7,first.getJSONArray("groups").getJSONObject(0).getInt("cpu_id"))
        stop=false
        val result=CacheProbe.run(topology,budget,{false},sampler,{},config,JSONObject(first.toString()))
        assertEquals("COMPLETED",result.getString("state"));assertEquals(result.getInt("planned_points")+1,calls)
        assertEquals(1,result.getJSONArray("groups").getJSONObject(0).getJSONArray("affinity_failures").length())
    }

    @Test fun midCurveAffinityLossPreservesValidPointsAndContinuesOtherGroups() {
        val ids=mutableListOf<Int>()
        val result=CacheProbe.run(topology,budget,{false},{cpu,bytes,_,_->ids+=cpu;if(cpu==6&&bytes>4096)failure(cpu)else curve()},{},config)
        assertEquals("INCOMPLETE",result.getString("state"));assertFalse(7 in ids)
        val groups=result.getJSONArray("groups");val restricted=groups.getJSONObject(0)
        assertEquals("AFFINITY_UNAVAILABLE",restricted.getString("state"));assertEquals(1,restricted.getJSONArray("points").length())
        assertEquals(0,restricted.getJSONArray("transitions").length());assertEquals(2,ids.count { it==6 })
        assertEquals("COMPLETED",groups.getJSONObject(1).getString("state"));assertEquals("COMPLETED",groups.getJSONObject(2).getString("state"))
    }
}
