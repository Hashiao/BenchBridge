package io.benchbridge.app.diagnostics

import io.benchbridge.app.i18n.L10n

import org.json.JSONObject

data class NativeReport(
    val passed: Boolean,
    val abi: String,
    val pageSizeBytes: Long,
    val cppStandard: Long,
    val ndkVersion: String,
    val cmakeVersion: String,
    val compiler: String,
    val checkedBytes: Long,
    val memoryOk: Boolean,
    val clockOk: Boolean,
    val checksum: String,
    val rawJson: String,
) {
    companion object {
        fun decode(rawJson: String): NativeReport {
            val json = JSONObject(rawJson)
            require(json.getInt("schemaVersion") == 1) { L10n.t("m_8ef2ac2deae8") }
            if (json.has("error")) {
                error(L10n.t("m_4bc5466918c5", json.getString("error")))
            }
            val pageSize = json.getLong("pageSizeBytes")
            val cppStandard = json.getLong("cppStandard")
            val checkedBytes = json.getLong("checkedBytes")
            val memoryOk = json.getBoolean("memoryOk")
            val clockOk = json.getBoolean("clockOk")
            return NativeReport(
                passed = json.getBoolean("ok") && memoryOk && clockOk &&
                    pageSize > 0 && cppStandard >= 202002 && checkedBytes == 1024L * 1024L,
                abi = json.getString("abi"),
                pageSizeBytes = pageSize,
                cppStandard = cppStandard,
                ndkVersion = json.getString("ndkVersion"),
                cmakeVersion = json.getString("cmakeVersion"),
                compiler = json.getString("compiler"),
                checkedBytes = checkedBytes,
                memoryOk = memoryOk,
                clockOk = clockOk,
                checksum = json.getString("checksum"),
                rawJson = rawJson,
            )
        }
    }
}

object NativeProbe {
    private val loadResult: Result<Unit> by lazy {
        runCatching { System.loadLibrary("benchbridge") }
    }

    fun inspect(): NativeReport {
        loadResult.getOrThrow()
        return NativeReport.decode(nativeInspect())
    }

    private external fun nativeInspect(): String
}
