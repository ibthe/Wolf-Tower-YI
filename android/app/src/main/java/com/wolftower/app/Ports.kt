package com.wolftower.app

import java.net.InetAddress
import java.net.ServerSocket

object Ports {
    /** Свободный локальный порт. Каждый запуск берёт новые порты, поэтому остатки прошлого сеанса не мешают. */
    fun free(): Int = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
}
