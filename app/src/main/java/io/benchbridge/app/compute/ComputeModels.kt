package io.benchbridge.app.compute

import org.json.JSONArray
import org.json.JSONObject

enum class ComputeKind(val code: Int, val title: String, val unit: String) {
    READ(0,"内存读取","MB/s"), WRITE(1,"内存写入","MB/s"), COPY(2,"内存拷贝","MB/s"),
    FP32(3,"FP32 浮点","GFLOPS"), FP64(4,"FP64 浮点","GFLOPS"),
    INT24(5,"INT24 整数","GIOPS"), INT32(6,"INT32 整数","GIOPS"), INT64(7,"INT64 整数","GIOPS"),
    AES(8,"AES-256","MB/s"), SHA(9,"SHA-1","MB/s"), JULIA(10,"Julia","FPS"), MANDEL(11,"Mandel","FPS")
}

data class ComputeConfig(val kinds: List<Int> = ComputeKind.entries.map { it.code },
                         val targets: List<String> = listOf("gpu","cpu"), val rounds: Int = 3,
                         val durationMs: Int = 1000, val warmupMs: Int = 200, val memoryMiB: Int = 64,
                         val threads: Int = 0, val imageSize: Int = 512) {
    val totalRounds: Int get() = kinds.size * targets.size * rounds
    val summary: String get() = "${targets.joinToString(" + ") { it.uppercase() }} · $rounds 次 · ${durationMs / 1000.0} 秒"
    val estimatedBytes: Long get() {
        val memorySelected=kinds.any { it<=2 }
        val gpu=if("gpu" in targets)(if(memorySelected)memoryMiB else 4)*3L*1048576 else 0L
        val cpuMemory=if("cpu" in targets&&memorySelected)memoryMiB*1048576L else 0L
        val cpuFrames=if("cpu" in targets&&kinds.any { it>=10 })imageSize.toLong()*imageSize*4*(if(threads==0)16 else threads) else 0L
        return maxOf(gpu,cpuMemory,cpuFrames)+96L*1048576
    }
    fun validate() {
        require(kinds.isNotEmpty() && kinds.distinct().size == kinds.size && kinds.all { it in 0..11 }) { "请选择测试项目" }
        require(targets.isNotEmpty() && targets.distinct().size == targets.size && targets.all { it in listOf("cpu","gpu") }) { "请选择 CPU 或 GPU" }
        require(rounds in 1..5 && durationMs in 50..5000 && warmupMs in 0..1000) { "测试时长或次数无效" }
        require(memoryMiB in 4..256 && threads in 0..16 && imageSize in listOf(64,128,256,512,1024)) { "测试配置无效" }
    }
    fun toJson() = JSONObject().put("kinds",JSONArray(kinds)).put("targets",JSONArray(targets)).put("rounds",rounds)
        .put("duration_ms",durationMs).put("warmup_ms",warmupMs).put("memory_mib",memoryMiB).put("cpu_threads",threads)
        .put("image_size",imageSize).put("protocol","gpgpu-v1").put("fractal_iterations",128)
        .put("aes_mode","independent-blocks").put("aes_key_bits",256).put("sha1_message_bytes",64)
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
        val factor=when(sample.getString("unit")){"MB/s"->1000.0;"FPS"->1e9;else->1.0}
        return sample.getLong("work_units").toDouble()/sample.getLong("elapsed_ns")*factor
    }
    fun median(report:JSONObject,kind:Int,target:String):Double? {
        val values=samples(report,kind,target).map(::value).sorted();if(values.isEmpty())return null
        return if(values.size%2==1)values[values.size/2]else(values[values.size/2-1]+values[values.size/2])/2
    }
}
