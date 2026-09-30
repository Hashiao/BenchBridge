package io.benchbridge.app.storage

import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import java.io.File
import java.util.UUID
import org.json.JSONObject

/** 控制记录独立于临时数据目录，并先于数据提交。 / Commit control records outside the temporary data directory before writing test data. */
class OwnedStorage(context: Context) {
    val root = File(context.noBackupFilesDir, "bench_tmp").apply { check(isDirectory || mkdirs()) }
    private val controls = File(context.noBackupFilesDir, "bench_control").apply { check(isDirectory || mkdirs()) }
    private fun checkId(id: String) { require(UUID.fromString(id).toString() == id) }
    fun directory(id: String): File { checkId(id); return File(root, "bb-run-$id") }
    private fun control(id: String): File { checkId(id); return File(controls, "$id.json") }
    private fun syncDir(directory: File) {
        val fd = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
        try { Os.fsync(fd) } finally { Os.close(fd) }
    }
    private fun write(file: File, text: String) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(text.toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream); syncDir(file.parentFile!!) }
        catch (error: Exception) { atomic.failWrite(stream); throw error }
    }
    fun create(id: String, purpose: String): File {
        check(!control(id).exists() && !directory(id).exists()) { "RUN_DIRECTORY_EXISTS" }
        val owner = JSONObject().put("run_id", id).put("purpose", purpose).put("schema", 1)
            .put("nonce", UUID.randomUUID().toString()).toString()
        write(control(id), owner)
        val run = directory(id)
        check(run.mkdir()) { "CREATE_RUN_DIRECTORY_FAILED" }
        syncDir(root)
        write(File(run, "owner.json"), owner)
        return run
    }
    fun cleanup(id: String): JSONObject {
        val ledger = control(id)
        if (!ledger.exists()) return JSONObject().put("state", if (directory(id).exists()) "REFUSED" else "CLEANED")
        if (ledger.length() > 8192) return JSONObject().put("state", "REFUSED").put("error", "CONTROL_TOO_LARGE")
        val result = JSONObject(StorageNative.cleanup(root.absolutePath, directory(id).name, ledger.readText(Charsets.UTF_8)))
        if (!result.has("state")) result.put("state", "PENDING")
        if (result.optString("state") == "CLEANED") { AtomicFile(ledger).delete(); syncDir(controls) }
        return result
    }
    fun recover(): Map<String, JSONObject> = controls.listFiles().orEmpty()
        .filter { it.name.matches(Regex("[0-9a-f-]{36}\\.json")) }.associate { file ->
            val id = file.name.removeSuffix(".json")
            id to runCatching { cleanup(id) }.getOrElse { JSONObject().put("state", "PENDING").put("error", it.message) }
        }
}
