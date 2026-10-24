package com.wolftower.app

import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketTimeoutException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Проверка доступности сайта через локальный SOCKS5 ByeDPI. */
object Prober {
    /** Если страница больше этого размера, она должна дойти хотя бы до него (ловит обрыв после ~16 КБ). */
    const val DEFAULT_MIN_BYTES = 40_000

    /**
     * Успех = TLS-рукопожатие прошло (с проверкой сертификата), сервер ответил HTTP-строкой и
     * ответ либо дочитан до конца, либо дошёл минимум до minBytes (при budgetMs > 0 ещё и вовремя).
     * Страница, которая "замерзает"
     * на первых килобайтах (типичная блокировка), считается неуспехом.
     * Имя домена передаётся прокси без локального DNS, как это делает приложение через туннель.
     */
    fun probe(
        host: String,
        socksPort: Int,
        timeoutMs: Int = 5000,
        minBytes: Int = DEFAULT_MIN_BYTES,
        budgetMs: Int = 0,
    ): Boolean {
        var raw: Socket? = null
        return try {
            raw = Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort)))
            raw.connect(InetSocketAddress.createUnresolved(host, 443), timeoutMs)
            raw.soTimeout = timeoutMs
            val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(raw, host, 443, true) as SSLSocket
            val params = ssl.sslParameters
            params.endpointIdentificationAlgorithm = "HTTPS"
            ssl.sslParameters = params
            ssl.soTimeout = timeoutMs
            ssl.startHandshake()

            val out = ssl.outputStream
            out.write(
                ("GET / HTTP/1.1\r\nHost: $host\r\nUser-Agent: Mozilla/5.0\r\nAccept: */*\r\n" +
                    "Accept-Encoding: identity\r\nConnection: close\r\n\r\n").toByteArray()
            )
            out.flush()

            val input = ssl.inputStream
            val buf = ByteArray(8192)
            val head = ByteArrayOutputStream()
            var total = 0
            var eof = false
            val startNs = System.nanoTime()
            while (total < minBytes) {
                // Лимит по времени: поток, который идёт, но слишком медленно (замедление), считается неуспехом
                if (budgetMs > 0) {
                    val left = budgetMs - (System.nanoTime() - startNs) / 1_000_000
                    if (left <= 0) break
                    ssl.soTimeout = minOf(timeoutMs.toLong(), left).toInt()
                }
                val n = try {
                    input.read(buf)
                } catch (_: SocketTimeoutException) {
                    -2
                }
                if (n == -2) break
                if (n < 0) {
                    eof = true
                    break
                }
                if (head.size() < 16) head.write(buf, 0, minOf(n, 16 - head.size()))
                total += n
            }
            val statusOk = head.toString(Charsets.ISO_8859_1).startsWith("HTTP/")
            statusOk && (eof || total >= minBytes)
        } catch (_: Exception) {
            false
        } finally {
            try {
                raw?.close()
            } catch (_: Exception) {
            }
        }
    }
}
