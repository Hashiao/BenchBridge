package io.benchbridge.app

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import io.benchbridge.app.i18n.L10n

/** 每个工作进程都使用同一语言策略。 / Apply the same language policy in every worker process. */
class BenchBridgeApplication : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        L10n.initialize(this)
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        L10n.initialize(this)
    }
}
