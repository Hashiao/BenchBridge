package io.benchbridge.app.compute

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Process
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject

internal fun probeGpu(context:Context):JSONObject {
    val signal=CountDownLatch(1);var api:IComputeProbe?=null;var bound=false;var pid=-1
    val executor=Executors.newSingleThreadExecutor()
    val connection=object:ServiceConnection{
        override fun onServiceConnected(name:ComponentName,binder:IBinder){api=IComputeProbe.Stub.asInterface(binder);signal.countDown()}
        override fun onServiceDisconnected(name:ComponentName){api=null}
        override fun onNullBinding(name:ComponentName){signal.countDown()}
        override fun onBindingDied(name:ComponentName){signal.countDown()}
    }
    return try {
        bound=context.bindService(Intent(context,ComputeProbeService::class.java),connection,Context.BIND_AUTO_CREATE)
        check(bound&&signal.await(5,TimeUnit.SECONDS)){"GPU_PROBE_BIND_TIMEOUT"}
        val service=checkNotNull(api);pid=service.pid();check(pid>0&&pid!=Process.myPid())
        JSONObject(executor.submit<String>{service.inspect()}.get(8,TimeUnit.SECONDS))
    }catch(error:Exception){
        JSONObject().put("supported",false).put("error","GPU_PROBE_FAILED").put("detail",(error.cause?.message?:error.message).orEmpty().take(400))
    }finally{
        if(bound)context.unbindService(connection)
        if(pid>0&&pid!=Process.myPid())runCatching{Process.killProcess(pid)}
        executor.shutdownNow()
    }
}
