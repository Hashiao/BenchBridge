package io.benchbridge.app.compute

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process

/** 驱动探测独立运行，避免影响其他测试。 / Isolate driver discovery from the other benchmarks. */
class ComputeProbeService : Service() {
    private val binder=object:IComputeProbe.Stub(){
        override fun pid()=Process.myPid()
        override fun inspect()=ComputeNative.gpuCapabilities()
    }
    override fun onBind(intent:Intent):IBinder=binder
}
