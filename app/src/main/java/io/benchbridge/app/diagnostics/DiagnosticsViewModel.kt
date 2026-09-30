package io.benchbridge.app.diagnostics

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.benchbridge.app.BuildConfig
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class DeviceInformation(
    val manufacturer: String = Build.MANUFACTURER,
    val model: String = Build.MODEL,
    val product: String = Build.PRODUCT,
    val androidVersion: String = Build.VERSION.RELEASE,
    val apiLevel: Int = Build.VERSION.SDK_INT,
    val supportedAbis: List<String> = Build.SUPPORTED_ABIS.toList(),
    val socModel: String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Build.SOC_MODEL.takeUnless { it.isBlank() || it == Build.UNKNOWN } ?: "系统未提供"
    } else {
        "系统未提供"
    },
    val appearsToBeEmulator: Boolean = Build.PRODUCT.startsWith("sdk_") ||
        Build.HARDWARE in setOf("ranchu", "goldfish"),
)

data class DiagnosticsState(
    val device: DeviceInformation = DeviceInformation(),
    val running: Boolean = false,
    val report: NativeReport? = null,
    val error: String? = null,
    val checkedAt: String? = null,
) {
    fun toJson(): String = JSONObject().apply {
        put("schema_version", 1)
        put("kind", "environment_check")
        put("is_benchmark_result", false)
        put("app_version", BuildConfig.VERSION_NAME)
        put("checked_at_local_time", checkedAt ?: JSONObject.NULL)
        put("status", when {
            running -> "running"
            error != null -> "failed"
            report?.passed == true -> "passed"
            report != null -> "failed"
            else -> "not_run"
        })
        put("device", JSONObject().apply {
            put("manufacturer", device.manufacturer)
            put("model", device.model)
            put("product", device.product)
            put("android_version", device.androidVersion)
            put("api_level", device.apiLevel)
            put("supported_abis", JSONArray(device.supportedAbis))
            put("soc_model", device.socModel)
            put("emulator_heuristic", device.appearsToBeEmulator)
        })
        put("native", report?.let { JSONObject(it.rawJson) } ?: JSONObject.NULL)
        put("error", error ?: JSONObject.NULL)
    }.toString(2)
}

class DiagnosticsViewModel : ViewModel() {
    private val mutableState = MutableStateFlow(DiagnosticsState())
    val state = mutableState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        if (mutableState.value.running) return
        mutableState.value = mutableState.value.copy(running = true, report = null, error = null)
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                try {
                    Result.success(NativeProbe.inspect())
                } catch (error: LinkageError) {
                    Result.failure(error)
                } catch (error: Exception) {
                    Result.failure(error)
                }
            }
            mutableState.value = mutableState.value.copy(
                running = false,
                report = result.getOrNull(),
                error = result.exceptionOrNull()?.let {
                    "${it.javaClass.simpleName}: ${it.message ?: "未提供错误详情"}"
                },
                checkedAt = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")),
            )
        }
    }
}
