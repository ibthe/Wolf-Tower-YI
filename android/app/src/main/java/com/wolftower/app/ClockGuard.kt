package com.wolftower.app

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Сверка часов телефона с сетевым временем без собственного сервера: берём заголовок Date
 * у нескольких известных сайтов по HTTPS (сертификат проверяется, подделать ответ нельзя).
 * Вызывать ТОЛЬКО из фонового потока.
 */
object ClockGuard {
    /** Допустимое расхождение. Откат "на дни" ловится, обычный дрейф часов нет. */
    const val TOLERANCE_MS = 60L * 60 * 1000

    private val SOURCES = listOf(
        "https://ya.ru/",
        "https://www.google.com/",
        "https://www.microsoft.com/",
        "https://www.cloudflare.com/",
        "https://www.apple.com/",
    )

    /**
     * Разница "часы телефона минус сетевое время" в мс (отрицательная = телефон отстаёт)
     * или null, если сеть недоступна. Если источники расходятся, берём меньшую по модулю,
     * чтобы не деактивировать ключ по ошибке одного сайта.
     */
    fun measureSkewMs(): Long? {
        val pool = Executors.newFixedThreadPool(SOURCES.size)
        try {
            val futures = SOURCES.map { url -> pool.submit(Callable { headDateSkew(url) }) }
            val deadline = System.currentTimeMillis() + 4500
            val skews = ArrayList<Long>()
            for (f in futures) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) break
                try {
                    f.get(left, TimeUnit.MILLISECONDS)?.let { skews.add(it) }
                } catch (_: Exception) {
                }
                if (skews.size >= 2) break
            }
            return skews.minByOrNull { abs(it) }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun headDateSkew(url: String): Long? = try {
        val t0 = System.currentTimeMillis()
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "HEAD"
        c.connectTimeout = 3000
        c.readTimeout = 3000
        c.instanceFollowRedirects = false
        c.responseCode
        val net = c.getHeaderFieldDate("Date", 0L)
        val t1 = System.currentTimeMillis()
        c.disconnect()
        if (net == 0L) null else (t0 + t1) / 2 - net
    } catch (_: Exception) {
        null
    }
}
