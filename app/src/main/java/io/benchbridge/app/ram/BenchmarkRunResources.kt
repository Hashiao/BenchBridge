package io.benchbridge.app.ram

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import io.benchbridge.app.MainActivity
import io.benchbridge.app.R

/** 接受测试后获取资源，发布终态前释放。 / Acquire resources for accepted runs and release them before publishing the terminal state. */
internal class BenchmarkRunResources(private val service: Service) {
    private val power = service.getSystemService(PowerManager::class.java)
    private val wake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BenchBridge:measurement").apply { setReferenceCounted(false) }
    @Volatile var active = false
        private set
    val wakeHeld: Boolean get() = wake.isHeld

    @Synchronized fun start(family: String) {
        check(!active) { "RUN_RESOURCES_BUSY" }
        val manager = service.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "测试运行", NotificationManager.IMPORTANCE_LOW))
        val launch = PendingIntent.getActivity(service, 0, Intent(service, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(service, 1, Intent(service, RamRunnerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(service, CHANNEL).setSmallIcon(R.drawable.ic_memory)
            .setContentTitle("BenchBridge · $family 测试中").setContentText("点按查看结果，可随时停止")
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(launch)
            .addAction(Notification.Action.Builder(null, "停止测试", stop).build()).build()
        // 可见界面发起测试；启动服务后，界面解绑不会中止已接受的任务。
        // A visible activity initiates the run; the started service survives subsequent UI unbinding.
        service.startService(Intent(service, RamRunnerService::class.java).setAction(ACTION_RETAIN))
        try {
            if (Build.VERSION.SDK_INT >= 34) service.startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else service.startForeground(NOTIFICATION_ID, notification)
            wake.acquire(2 * 60 * 60 * 1000L)
            active = true
        } catch (error: RuntimeException) {
            finish()
            throw error
        }
    }
    @Synchronized fun finish() {
        if (wake.isHeld) wake.release()
        active = false
        service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
        service.stopSelf()
    }
    companion object {
        const val ACTION_RETAIN = "io.benchbridge.app.RETAIN_RUN"
        const val ACTION_STOP = "io.benchbridge.app.STOP_RUN"
        private const val CHANNEL = "benchmark_running"
        private const val NOTIFICATION_ID = 71
    }
}
