package com.wolftower.app

import android.content.Context
import android.os.Build
import android.os.PowerManager

/** Текст диагностики для поддержки. Не содержит IP-адресов, трафика и самого лицензионного ключа. */
object DiagReport {
    fun build(ctx: Context, config: ProfilesConfig): String {
        val sb = StringBuilder()
        val version = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        } catch (_: Exception) {
            "?"
        }
        sb.appendLine("Wolf Tower $version")
        sb.appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("Сеть: ${NetId.describe(ctx)}")
        val pm = ctx.getSystemService(PowerManager::class.java)
        sb.appendLine("Без ограничений батареи: ${pm?.isIgnoringBatteryOptimizations(ctx.packageName)}")
        sb.appendLine("Лицензия: ${LicenseManager.describe(LicenseManager.status(ctx))}")
        sb.appendLine("ID устройства: ${LicenseManager.deviceId(ctx)}")
        sb.appendLine("DoH: ${Settings.dohEnabled(ctx)}, блокировка QUIC: ${Settings.blockQuicPref(ctx) ?: "авто"}")
        sb.appendLine("Защита запущена: ${DpiVpnService.running.value}")
        sb.appendLine()
        sb.appendLine("Сервисы:")
        val enabled = Settings.enabledIds(ctx)
        for (p in config.profiles) {
            val state = if (p.id in enabled) "вкл" else "выкл"
            sb.appendLine("- ${p.name}: $state, стратегия ${Settings.strategyFor(ctx, p.id, config.defaultStrategy)}")
        }
        val report = AutoProbe.state.value.report
        if (report.isNotBlank()) {
            sb.appendLine()
            sb.appendLine("Последний подбор:")
            sb.appendLine(report)
        }
        sb.appendLine()
        sb.appendLine("Лог (последние строки):")
        DpiVpnService.log.value.takeLast(40).forEach { sb.appendLine(it) }
        CrashInfo.read(ctx)?.let {
            sb.appendLine()
            sb.appendLine("Последний сбой:")
            sb.appendLine(it)
        }
        return sb.toString()
    }
}
