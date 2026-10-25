package com.wolftower.app

import android.content.Context

/** Простое хранилище выбора пользователя. */
object Settings {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("dpi", Context.MODE_PRIVATE)

    /** Выбранный язык интерфейса (ru, en, ce) или null, пока пользователь не выбирал: тогда берётся язык системы. */
    fun language(ctx: Context): String? = prefs(ctx).getString("lang", null)

    fun setLanguage(ctx: Context, code: String) {
        prefs(ctx).edit().putString("lang", code).apply()
    }

    private const val KEY_ENABLED = "enabled_services"

    /** Пока пользователь ничего не выбирал, включены оба сервиса. */
    private val DEFAULT_ENABLED = setOf("youtube", "instagram")

    /** Включённые сервисы (id из profiles.json). Пустой набор допустим: тогда запускать нечего. */
    fun enabledIds(ctx: Context): Set<String> {
        val raw = prefs(ctx).getString(KEY_ENABLED, null) ?: return DEFAULT_ENABLED
        return raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    fun setEnabled(ctx: Context, id: String, on: Boolean) {
        val next = if (on) enabledIds(ctx) + id else enabledIds(ctx) - id
        prefs(ctx).edit().putString(KEY_ENABLED, next.sorted().joinToString(",")).apply()
    }

    /**
     * Стратегия сервиса для ТЕКУЩЕЙ сети: сначала то, что запомнено для этой сети, затем общий выбор.
     * "none" = без обхода.
     */
    fun strategyFor(ctx: Context, profileId: String, default: String): String {
        val p = prefs(ctx)
        val fp = NetId.fingerprint(ctx)
        return p.getString("net_${fp}_$profileId", null)
            ?: p.getString("strategy_$profileId", default)
            ?: default
    }

    /** Запоминает выбор и как общий, и для текущей сети. */
    fun setStrategyFor(ctx: Context, profileId: String, strategy: String) {
        val fp = NetId.fingerprint(ctx)
        prefs(ctx).edit()
            .putString("strategy_$profileId", strategy)
            .putString("net_${fp}_$profileId", strategy)
            .apply()
    }

    /**
     * Блокировка QUIC (UDP): переключателя в интерфейсе нет. Всегда "авто" (null): QUIC блокируется, когда
     * его хотят все включённые сервисы (у YouTube и Instagram block_quic=true), и приложения откатываются на TCP,
     * где работают приёмы обхода. Старые сохранённые значения игнорируются.
     */
    @Suppress("UNUSED_PARAMETER")
    fun blockQuicPref(ctx: Context): Boolean? = null

    fun dohEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean("doh", false)

    fun setDoh(ctx: Context, value: Boolean) = prefs(ctx).edit().putBoolean("doh", value).apply()

    fun notifAsked(ctx: Context): Boolean = prefs(ctx).getBoolean("notif_asked", false)

    fun setNotifAsked(ctx: Context) = prefs(ctx).edit().putBoolean("notif_asked", true).apply()
}
