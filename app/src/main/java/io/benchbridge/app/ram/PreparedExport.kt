package io.benchbridge.app.ram

import io.benchbridge.app.i18n.L10n
import io.benchbridge.app.ui.resultText

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
        // 原始字段保持不变，另附当前语言摘要。 / Preserve original fields and add a summary in the current language.
        val exported = JSONObject().apply { report.keys().forEach { put(it, report.get(it)) } }
            .put("export_locale", L10n.tag).put("localized_summary", runCatching { resultText(report) }.getOrDefault(L10n.display(report.optString("error"))))
        val token = UUID.randomUUID().toString()
        val atomic = AtomicFile(file(token))
        val stream = atomic.startWrite()
        try { stream.write(exported.toString(2).toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
        catch (error: Exception) { atomic.failWrite(stream); throw error }
        return token
    }
    fun finish(uri: Uri?, token: String) {
        val source = file(token)
        if (uri != null) {
            context.contentResolver.openOutputStream(uri, "wt")?.use { output -> source.inputStream().use { it.copyTo(output) } }
                ?: error(L10n.t("m_f2aabf4a63c7"))
        }
        source.delete()
    }
}
