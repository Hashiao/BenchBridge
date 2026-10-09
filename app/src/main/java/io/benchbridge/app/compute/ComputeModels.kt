package io.benchbridge.app.compute

import io.benchbridge.app.i18n.L10n

import org.json.JSONArray
import org.json.JSONObject

enum class ComputeKind(val code: Int, private val titleKey: String, val unit: String) {
    READ(0,"m_d3fe7efd8de7","MB/s"), WRITE(1,"m_16de4707fe56","MB/s"), COPY(2,"m_15a5be0e2031","MB/s"),
    FP32(3,"m_e44652e35e27","GFLOPS"), FP64(4,"m_e8d9c616b400","GFLOPS"),
    INT24(5,"m_bbe20741baa2","GIOPS"), INT32(6,"m_1c82c39931ab","GIOPS"), INT64(7,"m_b55d49e6860d","GIOPS"),
    AES(8,"AES-256","MB/s"), SHA(9,"SHA-1","MB/s"), JULIA(10,"Julia","MPix/s"), MANDEL(11,"Mandel","MPix/s");
    val title: String get() = if(titleKey.startsWith("m_")) L10n.t(titleKey) else titleKey
}

data class ComputeConfig(val kinds: List<Int> = ComputeKind.entries.map { it.code },
                         val targets: List<String> = listOf("gpu","cpu"), val rounds: Int = 3,
                         val durationMs: Int = 1000, val warmupMs: Int = 200, val memoryMiB: Int = 64,
                         val threads: Int = 0, val imageSize: Int = 512) {
    val totalRounds: Int get() = kinds.size * targets.size * rounds
    val summary: String get() = L10n.t("m_ce77bd24a3c2", targets.joinToString(" + ") { it.uppercase() }, rounds, durationMs / 1000.0)
    val estimatedBytes: Long get() {
        val memorySelected=kinds.any { it<=2 || it==8 || it==9 }
        val gpu=if("gpu" in targets)(if(memorySelected)memoryMiB else 4)*3L*1048576 else 0L
        val cpuMemory=if("cpu" in targets&&memorySelected)memoryMiB*1048576L+memoryMiB*320L else 0L
        val cpuFrames=if("cpu" in targets&&kinds.any { it>=10 })imageSize.toLong()*imageSize*4*(if(threads==0)16 else threads) else 0L
        return maxOf(gpu,cpuMemory,cpuFrames)+96L*1048576
    }
    fun validate() {
        require(kinds.isNotEmpty() && kinds.distinct().size == kinds.size && kinds.all { it in 0..11 }) { L10n.t("m_862ad650feb8") }
        require(targets.isNotEmpty() && targets.distinct().size == targets.size && targets.all { it in listOf("cpu","gpu") }) { L10n.t("m_36468ea7cc89") }
        require(rounds in 1..5 && durationMs in 50..5000 && warmupMs in 0..1000) { L10n.t("m_d6c273e0ec9b") }
        require(memoryMiB in 4..256 && threads in 0..16 && imageSize in listOf(64,128,256,512,1024)) { L10n.t("m_19f1d5e09199") }
    }
    fun toJson() = JSONObject().put("kinds",JSONArray(kinds)).put("targets",JSONArray(targets)).put("rounds",rounds)
        .put("duration_ms",durationMs).put("warmup_ms",warmupMs).put("memory_mib",memoryMiB).put("cpu_threads",threads)
        .put("image_size",imageSize).put("protocol","gpgpu-v3").put("fractal_iterations",128)
        .put("aes_mode","ECB-no-padding").put("aes_key_bits",256).put("aes_message_bytes",65536)
        .put("sha1_message_bytes",65536).put("cpu_timer_scope","measurement-window").put("gpu_timer_scope","device-execution-or-submit-fence")
    companion object {
        fun quick() = ComputeConfig(rounds=1,durationMs=150,warmupMs=25,memoryMiB=16,imageSize=256)
        fun fromJson(text: String): ComputeConfig {
            require(text.length<=8192)
            val j=JSONObject(text)
            fun ints(key:String)=j.getJSONArray(key).let { a -> (0 until a.length()).map { a.getInt(it) } }
            val targets=j.getJSONArray("targets").let { a -> (0 until a.length()).map { a.getString(it) } }
            return ComputeConfig(ints("kinds"),targets,j.getInt("rounds"),j.getInt("duration_ms"),j.getInt("warmup_ms"),
                j.getInt("memory_mib"),j.getInt("cpu_threads"),j.getInt("image_size")).also { it.validate() }
        }
    }
}

object ComputeResults {
    fun cells(report:JSONObject) = report.optJSONArray("cells")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
    fun cell(report:JSONObject,kind:Int,target:String) = cells(report).firstOrNull { it.optInt("kind")==kind && it.optString("target")==target }
    fun samples(report:JSONObject,kind:Int,target:String) = report.optJSONArray("rounds")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        .orEmpty().filter { it.optInt("kind")==kind && it.optString("target")==target && it.optString("status")=="COMPLETED" &&
            it.optBoolean("verified") && it.optLong("work_units")>0 && it.optLong("elapsed_ns")>0 }
    fun value(sample:JSONObject):Double {
        // 历史 FPS 使用当轮图像尺寸换算，原始记录保持不变。
        // Convert legacy FPS using the recorded dimensions without rewriting saved results.
        val factor=when(sample.getString("unit")){
            "MB/s","MPix/s"->1000.0
            "FPS"->{
                val width=sample.optInt("width");val height=sample.optInt("height")
                if(width<=0||height<=0)return Double.NaN
                width.toDouble()*height*1000.0
            }
            else->1.0
        }
        return sample.getLong("work_units").toDouble()/sample.getLong("elapsed_ns")*factor
    }
    fun median(report:JSONObject,kind:Int,target:String):Double? {
        val values=samples(report,kind,target).map(::value).filter(Double::isFinite).sorted();if(values.isEmpty())return null
        return if(values.size%2==1)values[values.size/2]else(values[values.size/2-1]+values[values.size/2])/2
    }
}
