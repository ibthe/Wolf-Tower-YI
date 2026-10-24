package com.wolftower.app

import android.content.Context
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Запускает ByeDPI (libciadpi.so) как отдельный процесс с SOCKS5 на localhost. */
class ByeDpiProcess(private val ctx: Context) {
    private var proc: Process? = null

    /** true только если процесс жив и порт уже принимает соединения. */
    fun start(port: Int, args: List<String>, onLog: (String) -> Unit): Boolean {
        val bin = File(ctx.applicationInfo.nativeLibraryDir, "libciadpi.so")
        if (!bin.exists()) {
            onLog("Не найден ${bin.path}. Проверь сборку CMake и third_party/byedpi.")
            return false
        }
        val cmd = listOf(bin.path, "-i", "127.0.0.1", "-p", port.toString()) + args
        onLog("Запуск ByeDPI на порту $port: ${args.joinToString(" ")}")
        try {
            val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
            proc = p
            thread(isDaemon = true) {
                // После destroy() поток чтения получает IOException ("Stream closed").
                try {
                    p.inputStream.bufferedReader().forEachLine { onLog("byedpi: $it") }
                } catch (_: Exception) {
                }
            }
            // Ждём, пока порт откроется, либо процесс упадёт (например, порт занят)
            val deadline = System.currentTimeMillis() + 2500
            while (System.currentTimeMillis() < deadline) {
                if (!p.isAlive) {
                    onLog("ByeDPI завершился сразу после запуска (код ${p.exitValue()}): порт занят или неверные параметры")
                    proc = null
                    return false
                }
                try {
                    Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 150) }
                    return true
                } catch (_: Exception) {
                    Thread.sleep(80)
                }
            }
            onLog("ByeDPI не открыл порт $port за 2,5 с")
            stop()
            return false
        } catch (e: Exception) {
            onLog("Ошибка запуска ByeDPI: ${e.message}")
            return false
        }
    }

    /** Мягкая остановка, затем принудительная: иначе старый процесс может держать порт. */
    fun stop() {
        val p = proc ?: return
        proc = null
        try {
            p.destroy()
            if (!p.waitFor(700, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly()
                p.waitFor(1000, TimeUnit.MILLISECONDS)
            }
        } catch (_: Exception) {
        }
    }
}
