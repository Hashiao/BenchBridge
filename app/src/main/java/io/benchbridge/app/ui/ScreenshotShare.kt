package io.benchbridge.app.ui

import io.benchbridge.app.i18n.L10n

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

private fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/** 测试结束后按需截取当前 Activity。 / Capture the current activity on demand after measurement. */
internal suspend fun shareScreenshot(context: Context) {
    val activity = checkNotNull(context.activity()) { L10n.t("m_13936b708a67") }
    val view = activity.window.decorView
    check(view.width > 0 && view.height > 0) { L10n.t("m_5b55d0f9cb66") }
    val bitmap = createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
    try {
        // PixelCopy 无法取消，须等回调结束后再释放位图。
        // PixelCopy cannot be cancelled; retain the bitmap until its callback returns.
        val result = withContext(NonCancellable) { suspendCoroutine { continuation ->
            PixelCopy.request(activity.window, bitmap, { result -> continuation.resume(result) }, Handler(Looper.getMainLooper()))
        }
        }
        check(result == PixelCopy.SUCCESS) { L10n.t("m_2ebebf7735d1", result) }
        val file = withContext(Dispatchers.IO) {
            val directory = File(context.cacheDir, "shared_screenshots").apply { check(isDirectory || mkdirs()) }
            val output = File(directory, "BenchBridge-${UUID.randomUUID()}.png")
            output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            output
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("BenchBridge", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(Intent.createChooser(intent, L10n.t("m_c8a64119ed95")))
    } finally { bitmap.recycle() }
}
