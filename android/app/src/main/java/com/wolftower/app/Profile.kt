package com.wolftower.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Profile(
    val id: String,
    val name: String,
    val packages: List<String>,
    val domains: List<String>,
    val ipRanges: List<String>,
    val probeHosts: List<String>,
    val blockQuic: Boolean,
    /** true = при единственном активном сервисе стратегия применяется ко всему трафику приложения (без фильтра по SNI). */
    val matchAll: Boolean = false,
    /**
     * Порог пробы при подборе стратегии: страница должна дойти до этого размера за probeBudgetMs
     * (0 = без лимита по времени). Нужен для сервисов, которые не блокируют, а замедляют поток после
     * рукопожатия (YouTube): рукопожатие проходит, но 100 КБ за 4 с через замедление уже не придут.
     */
    val probeMinBytes: Int = Prober.DEFAULT_MIN_BYTES,
    val probeBudgetMs: Int = 0,
)

data class ProfilesConfig(val profiles: List<Profile>, val strategies: List<String>) {
    /** Первая в списке: самый нейтральный вариант (разбиение по SNI + disorder), дальше по росту "агрессивности". */
    val defaultStrategy: String
        get() = strategies.first()
}

object ProfilesRepository {
    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).map { getString(it) }

    fun load(ctx: Context): ProfilesConfig {
        val text = ctx.assets.open("profiles.json").bufferedReader().use { it.readText() }
        val root = JSONObject(text)
        val arr = root.getJSONArray("profiles")
        val profiles = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Profile(
                id = o.getString("id"),
                name = o.getString("name"),
                packages = o.optJSONArray("android_packages").strings(),
                domains = o.optJSONArray("domains").strings(),
                ipRanges = o.optJSONArray("ip_ranges").strings(),
                probeHosts = o.optJSONArray("probe_hosts").strings(),
                blockQuic = o.optBoolean("block_quic", false),
                matchAll = o.optBoolean("match_all", false),
                probeMinBytes = o.optInt("probe_min_bytes", Prober.DEFAULT_MIN_BYTES),
                probeBudgetMs = o.optInt("probe_budget_ms", 0),
            )
        }
        return ProfilesConfig(profiles, root.optJSONArray("strategies").strings())
    }
}
