package com.wolftower.app

import org.json.JSONObject
import java.net.InetAddress
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** DoH-запрос A-записей по IP-адресу резолвера (сертификат содержит IP), мимо DNS оператора. */
object Doh {
    private val ENDPOINTS = listOf(
        "https://1.1.1.1/dns-query?name=%s&type=A",
        "https://8.8.8.8/resolve?name=%s&type=A",
    )
    private val HOST_OK = Regex("^[A-Za-z0-9._-]{1,253}$")

    /** Список IPv4-адресов (по 4 байта) или null. Вызывать из фонового потока. */
    fun resolveA(host: String): List<ByteArray>? {
        if (!HOST_OK.matches(host)) return null
        for (template in ENDPOINTS) {
            try {
                val c = URL(String.format(template, host)).openConnection() as HttpsURLConnection
                c.connectTimeout = 3000
                c.readTimeout = 3000
                c.setRequestProperty("accept", "application/dns-json")
                if (c.responseCode != 200) {
                    c.disconnect()
                    continue
                }
                val body = c.inputStream.bufferedReader().use { it.readText() }
                val answers = JSONObject(body).optJSONArray("Answer") ?: continue
                val out = ArrayList<ByteArray>()
                for (i in 0 until answers.length()) {
                    val a = answers.getJSONObject(i)
                    if (a.optInt("type") != 1) continue
                    val bytes = InetAddress.getByName(a.getString("data")).address
                    if (bytes.size == 4) out.add(bytes)
                }
                if (out.isNotEmpty()) return out
            } catch (_: Exception) {
            }
        }
        return null
    }
}
