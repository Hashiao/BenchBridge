package io.benchbridge.app.ram

import io.benchbridge.app.i18n.L10n

import android.content.Context
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.os.Build
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import android.util.Log
import java.io.File
import java.util.UUID
import org.json.JSONObject

class RunStore(private val context: Context, family: String = "ram_results") {
    init { require(family in setOf("ram_results", "storage_results", "compute_results")) }
    private val root = File(context.filesDir, family).apply {
        check(isDirectory || mkdirs()) { L10n.t("m_ab4db7c78d06") }
    }
    private fun file(id: String): File {
        require(UUID.fromString(id).toString() == id) { L10n.t("m_70e977e56223") }
        return File(root, "$id.json")
    }
    @Synchronized fun save(report: JSONObject) {
        val atomic = AtomicFile(file(report.getString("run_id")))
        val stream = atomic.startWrite()
        try {
            stream.write(report.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
            val directory = Os.open(root.absolutePath, OsConstants.O_RDONLY, 0)
            try { Os.fsync(directory) } finally { Os.close(directory) }
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw error
        }
    }
    @Synchronized fun read(id: String): JSONObject? {
        val result = file(id)
        if (!result.exists()) return null
        require(result.length() <= 32L * 1024 * 1024) { L10n.t("m_6aada8368199") }
        // 只读取已原子提交的主文件；openRead 可能删除另一个进程正在写入的 .new 文件。
        // Read only the committed base file; openRead may remove another process's active .new file.
        return result.inputStream().bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
    }
    @Synchronized fun list(): List<JSONObject> = root.listFiles().orEmpty()
        .filter { it.isFile && it.name.matches(Regex("[0-9a-f-]{36}\\.json")) }
        .sortedByDescending { it.lastModified() }.take(200).mapNotNull {
            try { read(it.name.removeSuffix(".json")) } catch (error: Exception) {
                Log.w("BenchBridge", "Unreadable result ${it.name}: ${error.message}")
                null
            }
        }

    @Synchronized fun recoverInterrupted() {
        list().filter { it.optString("state") == "RUNNING" }.forEach {
            it.put("state", "INTERRUPTED").put("phase", "FINISHED")
                .put("error", L10n.t("m_2fe84a5837e6"))
                .put("finished_at_ms", System.currentTimeMillis())
            if (Build.VERSION.SDK_INT >= 30) runCatching {
                val pid = it.optJSONObject("capabilities")?.optInt("worker_pid") ?: 0
                if (pid > 0) context.getSystemService(ActivityManager::class.java)
                    .getHistoricalProcessExitReasons(context.packageName, pid, 8)
                    .firstOrNull { exit -> exit.timestamp >= it.optLong("started_at_ms") && exit.processName.endsWith(":bench") }?.let { exit ->
                        val reason = when (exit.reason) {
                            ApplicationExitInfo.REASON_LOW_MEMORY -> L10n.t("m_43c587192079")
                            ApplicationExitInfo.REASON_CRASH_NATIVE -> L10n.t("m_cb49f42311a0")
                            ApplicationExitInfo.REASON_CRASH -> L10n.t("m_3f6bfaa4c94e")
                            ApplicationExitInfo.REASON_ANR -> L10n.t("m_ed111dab3fb2")
                            ApplicationExitInfo.REASON_USER_REQUESTED -> L10n.t("m_a92563d1479c")
                            ApplicationExitInfo.REASON_SIGNALED -> L10n.t("m_e7659d936ff9")
                            else -> L10n.t("m_72ebc14a58b3", exit.reason)
                        }
                        it.put("process_exit", JSONObject().put("reason", exit.reason).put("label", reason)
                            .put("status", exit.status).put("timestamp_ms", exit.timestamp).put("pss_kib", exit.pss).put("rss_kib", exit.rss))
                            .put("error", L10n.t("m_dd0646b09a48", reason))
                    }
            }.onFailure { error -> Log.w("BenchBridge", "Exit information unavailable: ${error.javaClass.simpleName}") }
            save(it)
        }
    }

    // 仅删除明确指定的记录，设备测试也只删除其自行创建的记录。
    // Delete only explicitly selected records; instrumentation tests delete only their own records.
    @Synchronized fun delete(id: String) { AtomicFile(file(id)).delete() }
}
