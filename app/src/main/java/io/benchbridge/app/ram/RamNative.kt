package io.benchbridge.app.ram

object RamNative {
    init { System.loadLibrary("benchbridge") }
    external fun createSession(): Long
    external fun cancelSession(handle: Long)
    external fun releaseSession(handle: Long)
    external fun phase(handle: Long): Int
    external fun capabilities(): String
    external fun runRound(handle: Long, kind: Int, bytes: Long, threads: Int,
                          warmupMs: Int, durationMs: Int, seed: Long): String
    external fun runPinnedRound(handle: Long, kind: Int, workingSets: LongArray, cpuIds: IntArray,
                                warmupMs: Int, durationMs: Int, seed: Long, nodeStride: Int): String
}
