package io.benchbridge.app.ram

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import java.io.File
import java.util.UUID
import org.json.JSONObject

/** 报告保存在磁盘，界面恢复状态仅传递 UUID。 / Store reports on disk and pass only a UUID through saved state. */
internal class PreparedExport(private val context: Context) {
    private fun file(token: String): File {
        require(UUID.fromString(token).toString() == token)
        val root = File(context.cacheDir, "pending_exports").apply { check(isDirectory || mkdirs()) }
        return File(root, "$token.json")
    }
    fun prepare(report: JSONObject): String {
        val token = UUID.randomUUID().toString()
        val atomic = AtomicFile(file(token))
        val stream = atomic.startWrite()
        try { stream.write(report.toString(2).toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
        catch (error: Exception) { atomic.failWrite(stream); throw error }
        return token
    }
    fun finish(uri: Uri?, token: String) {
        val source = file(token)
        if (uri != null) {
            context.contentResolver.openOutputStream(uri, "wt")?.use { output -> source.inputStream().use { it.copyTo(output) } }
                ?: error("无法打开导出文件")
        }
        source.delete()
    }
}
