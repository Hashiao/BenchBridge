package io.benchbridge.app.ram

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

class RunnerClient(context: Context) : AutoCloseable {
    private val application = context.applicationContext
    private val connected = MutableStateFlow<IRamRunner?>(null)
    private var bound = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            connected.value = IRamRunner.Stub.asInterface(binder)
        }
        override fun onServiceDisconnected(name: ComponentName) { connected.value = null }
        override fun onNullBinding(name: ComponentName) { connected.value = null }
        override fun onBindingDied(name: ComponentName) {
            if (bound) application.unbindService(this)
            bound = false
            connected.value = null
            bind()
        }
    }
    init { bind() }
    private fun bind() {
        if (!bound) bound = application.bindService(Intent(application, RamRunnerService::class.java), connection, Context.BIND_AUTO_CREATE)
    }
    suspend fun service(): IRamRunner = withTimeout(15000) {
        connected.filterNotNull().first { it.asBinder().isBinderAlive }
    }
    override fun close() {
        if (bound) application.unbindService(connection)
        bound = false
        connected.value = null
    }
}
