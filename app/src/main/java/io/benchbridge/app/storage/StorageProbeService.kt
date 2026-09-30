package io.benchbridge.app.storage

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process

/** 隔离能力探测，防止 SIGSYS 终止界面或测量进程。 / Isolate capability probes so SIGSYS cannot terminate the UI or benchmark worker. */
class StorageProbeService : Service() {
    private val binder = object : IStorageProbe.Stub() {
        override fun pid(): Int = Process.myPid()
        override fun inspect(directory: String, aio: Boolean): String = StorageNative.probe(directory, aio)
    }
    override fun onBind(intent: Intent): IBinder = binder
}
