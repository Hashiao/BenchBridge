package io.benchbridge.app.storage

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Process
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject

internal fun probeStorage(context: Context, owned: OwnedStorage): JSONObject {
    val id = UUID.randomUUID().toString()
    var bound = false
    var probePid = -1
    val signal = CountDownLatch(1)
    var api: IStorageProbe? = null
    val executor = Executors.newSingleThreadExecutor()
    val result = JSONObject().put("buffered", false).put("direct_read", false).put("direct_write", false).put("native_aio", false)
    val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) { api = IStorageProbe.Stub.asInterface(binder); signal.countDown() }
        override fun onServiceDisconnected(name: ComponentName) { api = null }
        override fun onNullBinding(name: ComponentName) { signal.countDown() }
        override fun onBindingDied(name: ComponentName) { signal.countDown() }
    }
    try {
        val directory = owned.create(id, "capability-probe").absolutePath
        bound = context.bindService(Intent(context, StorageProbeService::class.java), connection, Context.BIND_AUTO_CREATE)
        check(bound && signal.await(10, TimeUnit.SECONDS)) { "PROBE_BIND_TIMEOUT" }
        val service = checkNotNull(api) { "PROBE_DISCONNECTED" }
        probePid = service.pid()
        check(probePid > 0 && probePid != Process.myPid()) { "PROBE_PROCESS_INVALID" }
        val basic = JSONObject(executor.submit<String> { service.inspect(directory, false) }.get(20, TimeUnit.SECONDS))
        basic.keys().forEach { key -> result.put(key, basic.get(key)) }
        if (basic.optBoolean("direct_read")) {
            // 即使探测进程被 seccomp 终止，也保留已取得的 O_DIRECT 结果。
            // Retain completed O_DIRECT results even if seccomp terminates the probe process.
            val aio = JSONObject(executor.submit<String> { service.inspect(directory, true) }.get(15, TimeUnit.SECONDS))
            result.put("native_aio", aio.optString("status") == "COMPLETED" && aio.optBoolean("verified"))
                .put("aio_probe", aio)
        }
    } catch (error: Exception) {
        result.put("probe_error", (error.cause?.message ?: error.message ?: "PROBE_FAILED").take(400))
        if (probePid > 0 && probePid != Process.myPid()) runCatching { Process.killProcess(probePid) }
    } finally {
        if (bound) context.unbindService(connection)
        executor.shutdownNow()
        result.put("probe_cleanup", owned.cleanup(id))
    }
    return result
}
