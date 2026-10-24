package com.wolftower.app

import android.content.Context
import android.os.ParcelFileDescriptor
import hev.htproxy.TProxyService
import java.io.File
import kotlin.random.Random

/**
 * tun2socks на базе hev-socks5-tunnel (MIT).
 * DNS: приложения спрашивают адрес у hev (mapdns), тот отдаёт фейковый IP и передаёт в SOCKS5
 * имя домена. Диапазон фейковых адресов выбирается заново при каждом запуске: иначе после перезапуска
 * адрес, который приложение запомнило в прошлом сеансе, мог бы означать уже другой сайт.
 */
class HevTun2Socks(private val ctx: Context) : Tun2Socks {

    companion object {
        const val MAPDNS_ADDRESS = "198.18.0.2"
        const val TUN_ADDRESS = "10.10.10.10"
        const val MTU = 1500
    }

    override fun start(
        tun: ParcelFileDescriptor,
        socksHost: String,
        socksPort: Int,
        onLog: (String) -> Unit,
    ): Boolean {
        val second = 64 + Random.nextInt(64) // 100.64.x.x .. 100.127.x.x
        val cfg = File(ctx.filesDir, "hev.yml")
        cfg.writeText(
            """
            tunnel:
              mtu: $MTU
              ipv4: $TUN_ADDRESS
            socks5:
              port: $socksPort
              address: $socksHost
              udp: 'udp'
            mapdns:
              address: $MAPDNS_ADDRESS
              port: 53
              network: 100.$second.0.0
              netmask: 255.255.0.0
              cache-size: 10000
            misc:
              task-stack-size: 81920
              connect-timeout: 5000
              log-level: warn
            """.trimIndent() + "\n"
        )
        return try {
            var ok = TProxyService.TProxyStartService(cfg.absolutePath, tun.fd)
            if (!ok) {
                // Остаток прошлого сеанса в этом же процессе: останавливаем и пробуем ещё раз
                runCatching { TProxyService.TProxyStopService() }
                ok = TProxyService.TProxyStartService(cfg.absolutePath, tun.fd)
            }
            onLog(if (ok) "tun2socks запущен (адреса 100.$second.x.x)" else "tun2socks: не удалось запуститься")
            ok
        } catch (t: Throwable) {
            onLog("tun2socks: ошибка загрузки библиотеки: ${t.message}")
            false
        }
    }

    override fun stop() {
        try {
            TProxyService.TProxyStopService()
        } catch (_: Throwable) {
        }
    }
}
