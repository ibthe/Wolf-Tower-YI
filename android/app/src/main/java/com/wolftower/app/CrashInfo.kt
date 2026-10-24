package com.wolftower.app

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File

/** Сохраняет причину сбоя, чтобы показать её при следующем запуске. */
object CrashInfo {
    private fun file(ctx: Context) = File(ctx.filesDir, "crash.txt")
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("crash", Context.MODE_PRIVATE)

    fun install(ctx: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                file(ctx).writeText("поток: ${thread.name}\n" + error.stackTraceToString())
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun read(ctx: Context): String? {
        val dismissed = prefs(ctx).getLong("dismissed", 0L)
        val sb = StringBuilder()
        val f = file(ctx)
        if (f.exists()) sb.append(f.readText().take(3000))
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val info = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 5).firstOrNull {
                    (it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
                        it.reason == ApplicationExitInfo.REASON_CRASH) && it.timestamp > dismissed
                }
                if (info != null) sb.append("\nСистема: причина=${info.reason} ${info.description ?: ""}")
            } catch (_: Throwable) {
            }
        }
        return sb.toString().ifBlank { null }
    }

    fun dismiss(ctx: Context) {
        file(ctx).delete()
        prefs(ctx).edit().putLong("dismissed", System.currentTimeMillis()).apply()
    }
}
