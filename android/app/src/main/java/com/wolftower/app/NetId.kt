package com.wolftower.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager
import java.security.MessageDigest

/**
 * Отпечаток текущей сети, чтобы помнить стратегии отдельно для каждой сети.
 * Мобильная сеть: код оператора (MCC+MNC). Wi-Fi: хеш DNS-серверов и шлюза (без SSID, чтобы не просить
 * доступ к геолокации). У разных домашних сетей отпечатки могут совпасть: это поправит самопроверка.
 */
object NetId {
    private fun underlying(cm: ConnectivityManager): Network? {
        val active = cm.activeNetwork
        val caps = active?.let { cm.getNetworkCapabilities(it) }
        if (active != null && caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != true) return active
        // активная сеть это VPN: ищем нижележащую
        var best: Network? = null
        for (n in cm.allNetworks) {
            val c = cm.getNetworkCapabilities(n) ?: continue
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            if (!c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return n
            best = n
        }
        return best
    }

    private fun type(cm: ConnectivityManager, net: Network?): String {
        val caps = net?.let { cm.getNetworkCapabilities(it) } ?: return "none"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cell"
            else -> "other"
        }
    }

    fun fingerprint(ctx: Context): String = try {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val net = underlying(cm)
        val t = type(cm, net)
        if (net == null || t == "none") {
            "none"
        } else {
            val extra = if (t == "cell") {
                ctx.getSystemService(TelephonyManager::class.java)?.networkOperator ?: ""
            } else {
                val lp = cm.getLinkProperties(net)
                val dns = lp?.dnsServers?.mapNotNull { it.hostAddress }?.sorted()?.joinToString(",") ?: ""
                val gw = lp?.routes?.firstOrNull { it.isDefaultRoute }?.gateway?.hostAddress ?: ""
                "$dns|$gw"
            }
            val hash = MessageDigest.getInstance("SHA-1").digest(extra.toByteArray())
                .joinToString("") { "%02x".format(it) }.take(10)
            "$t:$hash"
        }
    } catch (_: Exception) {
        "none"
    }

    fun describe(ctx: Context): String = try {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        when (type(cm, underlying(cm))) {
            "wifi" -> "Wi-Fi"
            "cell" -> {
                val name = ctx.getSystemService(TelephonyManager::class.java)?.networkOperatorName
                if (name.isNullOrBlank()) "Мобильная сеть" else "Мобильная сеть ($name)"
            }
            "other" -> "Другая сеть"
            else -> "Нет сети"
        }
    } catch (_: Exception) {
        "Неизвестно"
    }
}
