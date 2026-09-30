package io.benchbridge.app.compute

import android.content.res.AssetManager

object ComputeNative {
    init { System.loadLibrary("benchcompute") }
    external fun cpuCapabilities(): String
    external fun gpuCapabilities(allowSoftware: Boolean = false): String
    external fun selfTest(): Boolean
    // 软件设备仅供着色器校验，正常测试始终使用默认的硬件筛选。
    // Software devices are for shader validation; normal runs retain the hardware filter.
    external fun createSession(assets: AssetManager, allowSoftware: Boolean = false): Long
    external fun cancel(handle: Long)
    external fun release(handle: Long)
    external fun releaseGpu(handle: Long)
    external fun runCpu(handle: Long, kind: Int, cpus: IntArray, warmupMs: Int, durationMs: Int, width: Int, height: Int): String
    external fun runGpu(handle: Long, kind: Int, memoryMiB: Int, warmupMs: Int, durationMs: Int, width: Int, height: Int): String
}
