package com.wolftower.app

import java.io.EOFException
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Промежуточный SOCKS5-прокси между tun2socks и ByeDPI. Принимает от tun2socks запросы с именем домена,
 * определяет адрес через DoH (с запасным системным DNS) и передаёт ByeDPI уже IP-адрес. ByeDPI всё равно
 * видит SNI в TLS, поэтому группы по доменам работают. UDP (в том числе QUIC) не поддерживается и
 * отклоняется: приложения откатываются на TCP.
 */
class DohSocksShim(
    private val listenPort: Int,
    private val upstreamPort: Int,
    private val onLog: (String) -> Unit,
) {
    @Volatile
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    private val cache = ConcurrentHashMap<String, Pair<Long, List<ByteArray>>>()

    fun start(): Boolean {
        return try {
            val s = ServerSocket()
            s.reuseAddress = true
            s.bind(InetSocketAddress("127.0.0.1", listenPort))
            server = s
            thread(isDaemon = true) {
                while (!s.isClosed) {
                    try {
                        val c = s.accept()
                        pool.execute { handle(c) }
                    } catch (_: Exception) {
                        break
                    }
                }
            }
            true
        } catch (e: Exception) {
            onLog("DoH-прокси не запустился: ${e.message}")
            false
        }
    }

    fun stop() {
        try {
            server?.close()
        } catch (_: Exception) {
        }
        pool.shutdownNow()
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) throw EOFException()
            off += n
        }
    }

    private fun reply(out: java.io.OutputStream, code: Int) {
        out.write(byteArrayOf(5, code.toByte(), 0, 1, 0, 0, 0, 0, 0, 0))
        out.flush()
    }

    private fun resolve(host: String): ByteArray? {
        val now = System.currentTimeMillis()
        cache[host]?.let { if (it.first > now) return it.second.random() }
        val ips = Doh.resolveA(host) ?: try {
            InetAddress.getAllByName(host).filterIsInstance<Inet4Address>().map { it.address }
        } catch (_: Exception) {
            null
        }
        if (ips.isNullOrEmpty()) return null
        cache[host] = (now + 5 * 60_000L) to ips
        return ips.random()
    }

    private fun handle(client: Socket) {
        var up: Socket? = null
        try {
            client.tcpNoDelay = true
            client.soTimeout = 15000
            val cin = client.getInputStream()
            val cout = client.getOutputStream()

            if (cin.read() != 5) return
            val n = cin.read()
            if (n < 0) return
            readFully(cin, ByteArray(n))
            cout.write(byteArrayOf(5, 0))
            cout.flush()

            val hdr = ByteArray(4)
            readFully(cin, hdr)
            if (hdr[0].toInt() != 5) return
            val cmd = hdr[1].toInt()
            var addrType = hdr[3].toInt()
            var host: String? = null
            var addr: ByteArray? = null
            when (addrType) {
                1 -> addr = ByteArray(4).also { readFully(cin, it) }
                4 -> addr = ByteArray(16).also { readFully(cin, it) }
                3 -> {
                    val len = cin.read()
                    if (len <= 0) return
                    host = String(ByteArray(len).also { readFully(cin, it) }, Charsets.US_ASCII)
                }
                else -> {
                    reply(cout, 8)
                    return
                }
            }
            val portBytes = ByteArray(2)
            readFully(cin, portBytes)
            if (cmd != 1) {
                reply(cout, 7) // UDP ASSOCIATE и остальное не поддерживается
                return
            }
            if (host != null) {
                addr = resolve(host)
                addrType = 1
                if (addr == null) {
                    reply(cout, 4)
                    return
                }
            }

            val u = Socket()
            up = u
            u.connect(InetSocketAddress("127.0.0.1", upstreamPort), 5000)
            u.tcpNoDelay = true
            u.soTimeout = 15000
            val uin = u.getInputStream()
            val uout = u.getOutputStream()
            uout.write(byteArrayOf(5, 1, 0))
            uout.flush()
            val g = ByteArray(2)
            readFully(uin, g)
            if (g[1].toInt() != 0) {
                reply(cout, 1)
                return
            }
            uout.write(byteArrayOf(5, 1, 0, addrType.toByte()) + addr!! + portBytes)
            uout.flush()

            val rh = ByteArray(4)
            readFully(uin, rh)
            val skip = when (rh[3].toInt()) {
                1 -> 4
                4 -> 16
                3 -> uin.read()
                else -> 0
            }
            readFully(uin, ByteArray(skip + 2))
            val code = rh[1].toInt()
            if (code != 0) {
                reply(cout, code)
                return
            }
            reply(cout, 0)

            client.soTimeout = 0
            u.soTimeout = 0
            val done = CountDownLatch(1)
            pool.execute {
                pump(u, client)
                done.countDown()
            }
            pump(client, u)
            done.await(2, TimeUnit.MINUTES)
        } catch (_: Exception) {
        } finally {
            try {
                client.close()
            } catch (_: Exception) {
            }
            try {
                up?.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun pump(from: Socket, to: Socket) {
        try {
            val buf = ByteArray(16384)
            val i = from.getInputStream()
            val o = to.getOutputStream()
            while (true) {
                val n = i.read(buf)
                if (n < 0) break
                o.write(buf, 0, n)
                o.flush()
            }
        } catch (_: Exception) {
        }
        try {
            to.shutdownOutput()
        } catch (_: Exception) {
        }
    }
}
