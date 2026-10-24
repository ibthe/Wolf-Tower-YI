package com.wolftower.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Объясняет, почему сайт недоступен, проверяя напрямую (мимо ByeDPI) по шагам:
 * DNS системы и DoH, TCP-соединение с IP, TLS с именем сайта.
 * Это подсказка, а не приговор: оператор может вести себя по-разному в разное время.
 */
object Diagnose {
    private const val TIMEOUT = 4000

    fun verdict(host: String): String {
        val system = try {
            InetAddress.getAllByName(host).mapNotNull { it.hostAddress }.filter { !it.contains(':') }.toSet()
        } catch (_: Exception) {
            emptySet()
        }
        val doh = dohResolve(host)

        if (system.isEmpty() && doh.isEmpty()) {
            return "адрес не определился ни через DNS телефона, ни через DoH (возможно, нет сети или заблокированы оба)"
        }
        if (system.isEmpty()) {
            return "DNS телефона не отвечает на это имя, а через DoH адрес есть (${doh.first()}): DNS оператора блокирует имя"
        }
        val dnsNote = if (doh.isNotEmpty() && system.intersect(doh).isEmpty()) {
            "адреса DNS телефона ($system) и DoH ($doh) не совпадают, возможна подмена DNS (но у CDN это бывает и в норме). "
        } else {
            ""
        }

        val ip = (if (doh.isNotEmpty()) doh else system).first()
        if (!tcpConnect(ip)) {
            return dnsNote + "TCP до $ip:443 не устанавливается (таймаут или отказ): похоже на блокировку по IP. ByeDPI такое не обходит, нужен прокси/VPN вне сети оператора"
        }
        return dnsNote + when (tlsDirect(ip, host)) {
            TlsResult.OK -> "напрямую всё открывается, проблема, вероятно, в самом тесте или временная"
            TlsResult.CERT -> "TLS отвечает, но сертификат не подходит: на пути подставной сервер (подмена DNS или перехват)"
            TlsResult.FAIL -> "TCP до $ip есть, но TLS с этим именем обрывается: блокировка по SNI/содержимому, а обход не помог"
        }
    }

    private enum class TlsResult { OK, CERT, FAIL }

    private fun tcpConnect(ip: String): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(ip, 443), TIMEOUT) }
        true
    } catch (_: Exception) {
        false
    }

    private fun tlsDirect(ip: String, host: String): TlsResult {
        var raw: Socket? = null
        return try {
            raw = Socket()
            raw.connect(InetSocketAddress(ip, 443), TIMEOUT)
            raw.soTimeout = TIMEOUT
            val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(raw, host, 443, true) as SSLSocket
            val params = ssl.sslParameters
            params.endpointIdentificationAlgorithm = "HTTPS"
            ssl.sslParameters = params
            ssl.soTimeout = TIMEOUT
            ssl.startHandshake()
            TlsResult.OK
        } catch (e: javax.net.ssl.SSLPeerUnverifiedException) {
            TlsResult.CERT
        } catch (e: javax.net.ssl.SSLHandshakeException) {
            if (e.cause is java.security.cert.CertificateException) TlsResult.CERT else TlsResult.FAIL
        } catch (_: Exception) {
            TlsResult.FAIL
        } finally {
            try {
                raw?.close()
            } catch (_: Exception) {
            }
        }
    }

    /** DoH по IP-адресу резолвера (сертификат содержит IP), чтобы не зависеть от DNS оператора. */
    private fun dohResolve(host: String): Set<String> {
        val endpoints = listOf(
            "https://1.1.1.1/dns-query?name=$host&type=A",
            "https://8.8.8.8/resolve?name=$host&type=A",
        )
        for (url in endpoints) {
            try {
                val c = java.net.URL(url).openConnection() as HttpsURLConnection
                c.connectTimeout = TIMEOUT
                c.readTimeout = TIMEOUT
                c.setRequestProperty("accept", "application/dns-json")
                if (c.responseCode != HttpURLConnection.HTTP_OK) continue
                val answers = JSONObject(c.inputStream.bufferedReader().readText()).optJSONArray("Answer") ?: continue
                val ips = (0 until answers.length())
                    .map { answers.getJSONObject(it) }
                    .filter { it.optInt("type") == 1 }
                    .map { it.getString("data") }
                    .toSet()
                if (ips.isNotEmpty()) return ips
            } catch (_: Exception) {
            }
        }
        return emptySet()
    }
}
