package com.wolftower.app

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.concurrent.thread

data class ProbeState(
    val running: Boolean = false,
    val done: Boolean = false,
    val progress: String = "",
    val report: String = "",
    /** id сервиса -> "успешно/всего" для выбранной стратегии */
    val scores: Map<String, String> = emptyMap(),
)

/**
 * Автоподбор: для каждого варианта стратегии поднимается отдельный ByeDPI на своём порту (по 4 одновременно),
 * через него проверяются тестовые адреса каждого выбранного сервиса. Для сервиса берётся вариант
 * с наибольшим числом успехов (при равенстве, более ранний в списке). "none" проверяется первым:
 * если без обхода всё открывается, обход не нужен.
 */
object AutoProbe {
    val state = MutableStateFlow(ProbeState())

    @Volatile
    private var cancelled = false
    private const val BATCH = 4

    fun cancel() {
        cancelled = true
    }

    fun start(appCtx: Context, profiles: List<Profile>, strategies: List<String>) {
        if (state.value.running) return
        cancelled = false
        state.value = ProbeState(running = true, progress = tr("Подготовка…"))
        thread(isDaemon = true) {
            try {
                run(appCtx, profiles, strategies)
            } catch (t: Throwable) {
                state.value = ProbeState(done = true, report = trf("Ошибка подбора: {0}", t.message))
            }
        }
    }

    /**
     * Считает, сколько тестовых адресов каждого сервиса открылось при каждой стратегии.
     * Возвращает: id сервиса -> стратегия -> число успехов. Используется и интерфейсом, и самопроверкой.
     */
    fun score(
        ctx: Context,
        profiles: List<Profile>,
        candidates: List<String>,
        isCancelled: () -> Boolean,
        onProgress: (Int) -> Unit,
    ): Map<String, Map<String, Int>> {
        val okCounts = HashMap<String, HashMap<String, Int>>()
        profiles.forEach { okCounts[it.id] = HashMap() }
        var checked = 0

        for (batch in candidates.chunked(BATCH)) {
            if (isCancelled()) break
            val procs = ArrayList<Pair<String, ByeDpiProcess>>()
            val ports = HashMap<String, Int>()
            try {
                batch.forEach { strategy ->
                    val port = Ports.free()
                    val proc = ByeDpiProcess(ctx)
                    if (proc.start(port, StrategyArgs.BASE + StrategyArgs.forName(strategy)) { }) {
                        procs.add(strategy to proc)
                        ports[strategy] = port
                    }
                }
                if (procs.isEmpty()) throw IllegalStateException(tr("не удалось запустить ByeDPI"))
                ports.values.forEach { waitPort(it) }

                val pool = Executors.newFixedThreadPool(12)
                try {
                    val jobs = ArrayList<Triple<String, String, Future<Boolean>>>()
                    for ((strategy, _) in procs) {
                        for (profile in profiles) {
                            for (host in profile.probeHosts) {
                                val port = ports.getValue(strategy)
                                jobs.add(
                                    Triple(
                                        strategy,
                                        profile.id,
                                        pool.submit(
                                            Callable {
                                                Prober.probe(
                                                    host, port,
                                                    minBytes = profile.probeMinBytes,
                                                    budgetMs = profile.probeBudgetMs,
                                                )
                                            }
                                        ),
                                    )
                                )
                            }
                        }
                    }
                    for ((strategy, profileId, future) in jobs) {
                        val success = try {
                            future.get()
                        } catch (e: InterruptedException) {
                            throw e
                        } catch (_: Exception) {
                            false
                        }
                        if (success) {
                            val m = okCounts.getValue(profileId)
                            m[strategy] = (m[strategy] ?: 0) + 1
                        }
                    }
                } finally {
                    pool.shutdownNow()
                }
            } finally {
                procs.forEach { it.second.stop() }
            }
            checked += batch.size
            onProgress(checked)
            // Раньше найденный вариант с полным баллом не может проиграть более позднему (при равенстве
            // выигрывает более ранний), поэтому остальные пачки можно не проверять: быстрее и меньше трафика.
            val settled = profiles.all { p ->
                p.probeHosts.isEmpty() || okCounts.getValue(p.id).values.any { it >= p.probeHosts.size }
            }
            if (settled) break
        }
        return okCounts
    }

    private fun run(ctx: Context, profiles: List<Profile>, strategies: List<String>) {
        val candidates = listOf("none") + strategies
        val okCounts = score(ctx, profiles, candidates, { cancelled }) { n ->
            state.value = state.value.copy(progress = trf("Проверено вариантов: {0} из {1}", n, candidates.size))
        }

        if (cancelled) {
            state.value = ProbeState(done = true, report = tr("Подбор отменён, настройки не менялись"))
            return
        }

        val lines = ArrayList<String>()
        val scores = HashMap<String, String>()
        for (profile in profiles) {
            val total = profile.probeHosts.size
            if (total == 0) {
                lines.add(trf("{0}: нет адресов для проверки", profile.name))
                continue
            }
            val counts = okCounts.getValue(profile.id)
            var best: String? = null
            var bestOk = 0
            for (s in candidates) {
                // Проба смотрит только TLS-адреса. Для match_all-сервиса (WhatsApp) "без обхода" не выбираем:
                // сайт может открываться, а чат (Noise, без SNI) нет.
                if (s == "none" && profile.matchAll) continue
                val c = counts[s] ?: 0
                if (c > bestOk) {
                    best = s
                    bestOk = c
                }
            }
            if (best == null) {
                state.value = state.value.copy(progress = trf("Диагностика: {0}…", profile.name))
                val host = profile.probeHosts.first()
                lines.add(trf("{0}: ничего не сработало (0/{1}).\n   Диагностика {2}: {3}", profile.name, total, host, Diagnose.verdict(host)))
            } else {
                Settings.setStrategyFor(ctx, profile.id, best)
                scores[profile.id] = "$bestOk/$total"
                val label = if (best == "none") tr("обход не нужен") else best
                lines.add(trf("{0}: {1} ({2}/{3})", profile.name, label, bestOk, total))
            }
        }
        state.value = ProbeState(done = true, report = lines.joinToString("\n"), scores = scores)
    }

    private fun waitPort(port: Int) {
        val end = System.currentTimeMillis() + 2000
        while (System.currentTimeMillis() < end) {
            try {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 200) }
                return
            } catch (_: Exception) {
                Thread.sleep(100)
            }
        }
    }
}
