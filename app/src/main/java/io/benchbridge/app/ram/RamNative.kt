package io.benchbridge.app.ram

object RamNative {
    init { System.loadLibrary("benchbridge") }
    external fun createSession(): Long
    external fun cancelSession(handle: Long)
    external fun releaseSession(handle: Long)
    external fun phase(handle: Long): Int
    external fun capabilities(): String
    external fun runtimeDiagnostics(): String
    // 仅 Debug 构建接受故障注入；正式包始终返回 false。 / Fault injection is Debug-only; Release always returns false.
    external fun setDiagnosticFault(handle: Long, mode: Int): Boolean
    external fun runLatencyPoint(handle: Long, cpu: Int, bytes: Long, stride: Int, seed: Long): String
    external fun runLatencyPointOnce(handle: Long, cpu: Int, bytes: Long, stride: Int, seed: Long): String
    external fun runRound(handle: Long, kind: Int, bytes: Long, threads: Int,
                          warmupMs: Int, durationMs: Int, seed: Long): String
    external fun runPinnedRound(handle: Long, kind: Int, workingSets: LongArray, cpuIds: IntArray,
                                warmupMs: Int, durationMs: Int, seed: Long, nodeStride: Int, minimumWarmupPasses: Int = 0): String
}
