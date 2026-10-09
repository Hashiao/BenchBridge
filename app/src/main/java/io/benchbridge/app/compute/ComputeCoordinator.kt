package io.benchbridge.app.compute

import io.benchbridge.app.i18n.L10n

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.util.Log
import io.benchbridge.app.BuildConfig
import io.benchbridge.app.ram.RamNative
import io.benchbridge.app.ram.RamResults
import io.benchbridge.app.ram.RunStore
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

class ComputeCoordinator(private val context:Context,private val executor:ExecutorService,private val thermal:()->Int,
                         private val onStarting:()->Unit,private val onFinished:()->Unit) {
    private val store=RunStore(context,"compute_results").also { it.recoverInterrupted() }
    private val capsLock=Any()
    @Volatile private var cachedCaps:JSONObject?=null
    @Volatile private var current:Run?=null
    private class Run(val id:String,val config:ComputeConfig,val handle:Long,val report:JSONObject){
        val cancelled=AtomicBoolean(false)
        @Volatile var reason="RUN_CANCELLED"
        @Volatile var text=report.toString()
        @Volatile var finished=false
        @Volatile var memoryHandle=0L
        var restart=false
    }
    val activeId:String? get()=current?.takeUnless { it.finished&&!it.restart }?.id
    fun capabilities():JSONObject=synchronized(capsLock){
        cachedCaps?.let { return@synchronized JSONObject(it.toString()) }
        val gpu=probeGpu(context)
        JSONObject().put("cpu",JSONObject(ComputeNative.cpuCapabilities())).put("gpu",gpu).also {
            if(gpu.optString("error")!="GPU_PROBE_FAILED")cachedCaps=it
        }
    }
    fun start(text:String):String {
        var handle=0L
        return try {
            check(activeId==null){"COMPUTE_BUSY"}
            val config=ComputeConfig.fromJson(text)
            val info=ActivityManager.MemoryInfo();context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
            require(config.estimatedBytes<=minOf(info.availMem/3,(info.availMem-maxOf(info.threshold,256L*1048576)).coerceAtLeast(0))){L10n.t("m_ef9a012bd0b7")}
            require(thermal()<PowerManager.THERMAL_STATUS_SEVERE){L10n.t("m_c856ee25bdc6")}
            val id=UUID.randomUUID().toString()
            val report=JSONObject().put("schema_version",1).put("kind","compute_benchmark").put("run_id",id).put("state","RUNNING").put("phase","PREPARING")
                .put("started_at_ms",System.currentTimeMillis()).put("app_version",BuildConfig.VERSION_NAME).put("config",config.toJson())
                .put("device",JSONObject().put("manufacturer",Build.MANUFACTURER).put("model",Build.MODEL).put("api",Build.VERSION.SDK_INT))
                .put("cells",JSONArray()).put("rounds",JSONArray()).put("completed_rounds",0).put("processed_rounds",0).put("total_rounds",config.totalRounds).put("error",JSONObject.NULL)
            handle=ComputeNative.createSession(context.assets)
            onStarting();store.save(report)
            val run=Run(id,config,handle,report);current=run
            try { executor.execute{execute(run)} } catch(error:Exception){report.put("state","FAILED").put("error","START_FAILED");publish(run);throw error}
            JSONObject().put("accepted",true).put("run_id",id).put("worker_pid",Process.myPid()).toString()
        }catch(error:Exception){if(handle!=0L){ComputeNative.release(handle);onFinished()};JSONObject().put("accepted",false).put("error",error.message).toString()}
    }
    private fun publish(run:Run){
        store.save(run.report)
        val snapshot=JSONObject(run.report.toString())
        if(snapshot.toString().length>200000){snapshot.getJSONArray("rounds").let { a -> repeat(a.length()){ a.getJSONObject(it).remove("calibration") } };snapshot.put("full_report_in_storage",true)}
        run.text=snapshot.toString();if(run.report.optString("state") in RamResults.terminalStates)run.finished=true
    }
    fun snapshot(id:String):String?=current?.takeIf { it.id==id }?.text?:store.read(id)?.toString()
    fun cancel(id:String,reason:String){current?.takeIf { it.id==id&&!it.finished }?.let { it.reason=reason;it.cancelled.set(true);ComputeNative.cancel(it.handle);if(it.memoryHandle!=0L)RamNative.cancelSession(it.memoryHandle) }}
    private fun execute(run:Run){
        var completed=0;var processed=0;var failed=false
        val c=run.config;val report=run.report;val power=context.getSystemService(PowerManager::class.java)
        try {
            val caps=if("gpu" in c.targets)capabilities() else JSONObject().put("gpu",JSONObject().put("supported",false).put("not_requested",true))
            caps.put("cpu",JSONObject(ComputeNative.cpuCapabilities()));report.put("capabilities",caps)
            val ids=caps.getJSONObject("cpu").getJSONArray("cpu_ids")
            val cpus=(0 until ids.length()).map { ids.getInt(it) }.take(if(c.threads==0)16 else c.threads)
            require("cpu" !in c.targets||cpus.isNotEmpty()&&(c.threads==0||cpus.size==c.threads)){L10n.t("m_97be46dba289")}
            check(ComputeNative.selfTest()){"CRYPTO_SELF_TEST_FAILED"}
            val gpu=caps.getJSONObject("gpu")
            for(target in c.targets)for(kind in c.kinds)report.getJSONArray("cells").put(JSONObject().put("target",target).put("kind",kind).put("state","PENDING"))
            publish(run)
            outer@ for(target in listOf("gpu","cpu").filter(c.targets::contains)){
                for(kind in c.kinds){
                    if(run.cancelled.get())break@outer
                    if(thermal()>=PowerManager.THERMAL_STATUS_SEVERE){cancel(run.id,L10n.t("m_678aa85c0014"));break@outer}
                    val cell=ComputeResults.cell(report,kind,target)!!
                    report.put("current_target",target).put("current_kind",kind).put("current_round",0)
                    val unsupported=target=="gpu"&&(!gpu.optBoolean("supported")||kind in listOf(4,11)&&!gpu.optBoolean("fp64")||kind==7&&!gpu.optBoolean("int64"))
                    if(unsupported||target=="gpu"&&run.restart){
                        val probeFailed=gpu.optString("error")=="GPU_PROBE_FAILED"
                        if(probeFailed||run.restart)failed=true
                        cell.put("state",if(run.restart||probeFailed)"FAILED"else"UNSUPPORTED").put("reason",if(run.restart)"GPU_RESTART_REQUIRED"else if(probeFailed)"GPU_PROBE_FAILED"else"GPU_FEATURE_UNSUPPORTED")
                        processed+=c.rounds;report.put("processed_rounds",processed);publish(run);continue
                    }
                    cell.put("state","RUNNING");publish(run)
                    var count=0
                    for(round in 1..c.rounds){
                        if(run.cancelled.get())break
                        report.put("current_round",round).put("phase","MEASURING");publish(run)
                        val before=thermal()
                        val sample=if(target=="gpu")JSONObject(ComputeNative.runGpu(run.handle,kind,c.memoryMiB,c.warmupMs,c.durationMs,c.imageSize,c.imageSize))
                        else if(kind<=2){
                            run.memoryHandle=RamNative.createSession()
                            try {
                                if(run.cancelled.get())RamNative.cancelSession(run.memoryHandle)
                                JSONObject(RamNative.runRound(run.memoryHandle,kind,c.memoryMiB*1048576L,cpus.size,c.warmupMs,c.durationMs,0xB16B00B5L)).apply {
                                    val bytes=c.memoryMiB*1048576L
                                    put("work_units",optLong("logical_bytes")).put("unit","MB/s").put("backend","native-cpu-memory-v2")
                                    put("protocol","gpgpu-v3").put("timer_scope","measurement-window").put("working_set_bytes",bytes)
                                    put("input_bytes",if(kind==1)0 else if(kind==2)bytes/2 else bytes)
                                    put("output_bytes",if(kind==0)0 else if(kind==2)bytes/2 else bytes)
                                    put("input_prepared_before_timing",true)
                                }
                            }finally{RamNative.releaseSession(run.memoryHandle);run.memoryHandle=0}
                        }else JSONObject(ComputeNative.runCpu(run.handle,kind,cpus.toIntArray(),c.warmupMs,c.durationMs,c.imageSize,c.imageSize,c.memoryMiB))
                        sample.put("kind",kind).put("target",target).put("round",round).put("thermal_before",before).put("thermal_after",thermal()).put("screen_interactive_after",power.isInteractive)
                        report.getJSONArray("rounds").put(sample);processed++
                        if(sample.optString("status")=="COMPLETED"&&sample.optBoolean("verified")){count++;completed++}
                        else if(!run.cancelled.get()) {failed=true;cell.put("reason",sample.optString("error","COMPUTE_FAILED"))}
                        if(sample.optBoolean("restart_worker"))run.restart=true
                        report.put("completed_rounds",completed).put("processed_rounds",processed);cell.put("completed_rounds",count);publish(run)
                        Log.i("BenchBridge","COMPUTE_ROUND ${run.id} $target kind=$kind round=$round ${sample.optString("status")}")
                        if(sample.optString("status")!="COMPLETED")break
                    }
                    cell.put("state",if(count==c.rounds)"COMPLETED"else if(run.cancelled.get())"CANCELLED"else"FAILED");publish(run)
                }
                if(target=="gpu")ComputeNative.releaseGpu(run.handle)
            }
            report.put("state",when{run.cancelled.get()->"CANCELLED";failed->"FAILED";completed==c.totalRounds->"COMPLETED";processed==c.totalRounds->"PARTIAL";else->"INTERRUPTED"})
            if(run.cancelled.get())report.put("error",run.reason)
        }catch(error:Exception){report.put("state",if(run.cancelled.get())"CANCELLED"else"FAILED").put("error",error.message);Log.e("BenchBridge","COMPUTE_ERROR ${run.id}",error)}
        finally {
            ComputeNative.release(run.handle);onFinished();report.put("phase","FINISHED").put("finished_at_ms",System.currentTimeMillis())
            try{publish(run)}catch(error:Exception){report.put("state","FAILED").put("persistence_error",true).put("error","REPORT_SAVE_FAILED");run.text=report.toString();run.finished=true}
            Log.i("BenchBridge","COMPUTE_FINISH ${run.id} ${report.optString("state")} $completed/${c.totalRounds}")
            if(run.restart)Handler(Looper.getMainLooper()).postDelayed({Process.killProcess(Process.myPid())},1500)
        }
    }
}
