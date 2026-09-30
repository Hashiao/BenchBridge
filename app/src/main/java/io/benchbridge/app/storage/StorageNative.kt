package io.benchbridge.app.storage

object StorageNative {
    init { System.loadLibrary("benchbridge") }
    external fun createSession(directory: String, fileBytes: Long, writeBudget: Long): Long
    external fun prepare(handle: Long): String
    external fun runRound(handle: Long, random: Boolean, write: Boolean, blockBytes: Int, queue: Int,
        threads: Int, warmupMs: Int, durationMs: Int, direct: Boolean): String
    external fun progress(handle: Long): String
    external fun cancelSession(handle: Long)
    external fun releaseSession(handle: Long)
    external fun probe(directory: String, aio: Boolean): String
    external fun cleanup(root: String, name: String, owner: String): String
}
