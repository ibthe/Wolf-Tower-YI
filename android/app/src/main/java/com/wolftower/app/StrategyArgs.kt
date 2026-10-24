package com.wolftower.app

/**
 * Имена стратегий из profiles.json -> аргументы ByeDPI (ciadpi).
 * Флаги сверены с `ciadpi --help` (коммит ba53229). "+s" = смещение от начала SNI.
 */
object StrategyArgs {
    /**
     * Флаги, общие для всех запусков ByeDPI (рабочего и пробного). "-X" (--no-ipv6) заставляет ByeDPI
     * резолвить и соединяться только по IPv4: туннель IPv4-only, hev mapdns отдаёт приложениям
     * только A-записи, а при двойном стеке ByeDPI иначе мог бы уйти на IPv6 мимо проверенного пути.
     */
    val BASE = listOf("-X")

    // Должна быть объявлена выше library: поля object инициализируются по порядку.
    private val CHAIN = listOf("-d1", "-s1+s", "-s3+s", "-s6+s", "-s9+s", "-s12+s", "-s15+s", "-s20+s", "-s30+s")

    private val library: Map<String, List<String>> = mapOf(
        // Для TLS-сервисов (YouTube, Instagram). Разбиение по SNI + disorder: рекомендация README ByeDPI
        // для Windows, где одиночный disorder не работает, и нормально работает на Linux/Android.
        "split+disorder" to listOf("-s1+s", "-d3+s"),
        // Цепочки в стиле ByeByeDPI: много разрезов по SNI подряд, часть из них disorder/oob (без UDP-части -a1).
        "chain-oob" to listOf("-o1", "-s1+s", "-s3+s", "-s6+s", "-s9+s", "-s12+s", "-s15+s", "-s20+s", "-s30+s"),
        "chain-mix" to listOf("-d1", "-s1+s", "-d3+s", "-s6+s", "-d9+s", "-s12+s", "-d15+s", "-s20+s", "-d25+s", "-s30+s", "-d35+s"),
        "chain-mix2" to listOf("-o1", "-s1+s", "-d1+s", "-s3+s", "-d6+s", "-s12+s", "-d14+s", "-s20+s", "-d24+s", "-s30+s"),
        "chain-dis" to listOf("-d1", "-d3+s", "-s6+s", "-d9+s", "-s20+s", "-d25+s", "-s30+s"),
        "chain-tlsrec" to listOf("-r1+s", "-s1+s", "-s3+s", "-s6+s", "-s9+s", "-s12+s", "-s15+s", "-s20+s", "-s30+s"),
        "chain-fake" to listOf("-f-1", "-t", "8", "-s1+s", "-s3+s", "-s6+s", "-s9+s", "-s12+s", "-s15+s", "-s20+s", "-s30+s"),
        "chain-fine" to listOf("-s1+s", "-s2+s", "-s3+s", "-s4+s", "-s5+s", "-s6+s", "-s7+s", "-s8+s"),
        "oob-rec-split" to listOf("-o1", "-d1", "-r1+s", "-s1+s", "-d3+s"),
        "oob-sack" to listOf("-s1", "-q1", "-Y", "-f-1", "-r1+s"),
        // Только разделение TLS-записи по SNI, без трюков с TCP: самый мягкий вариант.
        "tlsrec" to listOf("-r1+s"),
        "split" to listOf("-s1+s"),
        "disorder" to listOf("-d1+s"),
        "oob" to listOf("-o1+s"),
        "disoob" to listOf("-q1+s"),
        "split+tlsrec" to listOf("-s1+s", "-r1+s"),
        "disorder+tlsrec" to listOf("-d1+s", "-r1+s"),
        "oob+tlsrec" to listOf("-o1+s", "-r1+s"),
        "fake+disorder" to listOf("-f-1", "-t", "8", "-d1+s"),
        "fake+oob" to listOf("-f-1", "-t", "8", "-o1+s"),
        "fake+split" to listOf("-f-1", "-t", "8", "-s1+s"),
        "fake+tlsrec" to listOf("-f-1", "-t", "8", "-r1+s"),
        "fake3+disorder" to listOf("-f-1", "-t", "3", "-d1+s"),
        "fake4+disorder" to listOf("-f-1", "-t", "4", "-d1+s"),
        "fake6+disorder" to listOf("-f-1", "-t", "6", "-d1+s"),
        "fake12+disorder" to listOf("-f-1", "-t", "12", "-d1+s"),
        // Абсолютные смещения (без +s) работают и на трафике без SNI: WhatsApp-чат идёт не по TLS,
        // а по протоколу Noise, и смещение +s на нём пропускается (gen_offset возвращает -1).
        "disorder0" to listOf("-d1"),
        "split0" to listOf("-s1"),
        // Цепочка мелких фрагментов + 1 поддельный UDP-пакет (-a1) для звонков WhatsApp (STUN/UDP).
        "chain" to CHAIN,
        "chain+udp" to CHAIN + "-a1",
        "disorder0+udp" to listOf("-d1", "-a1"),
        "split+udp" to listOf("-s1+s", "-a1"),
        "disorder+udp" to listOf("-d1+s", "-a1"),
    )

    /** Своя строка в стиле ByeByeDPI: имя стратегии вида "raw:-d1 -s1+s -a1". */
    const val RAW_PREFIX = "raw:"

    // Разрешены только флаги самого обхода. Фильтры групп, порты, режимы и прочее задаёт приложение.
    private const val RAW_ALLOWED = "sdoqftSnlOQeMrmaYR"

    /** Возвращает список аргументов или null, если строка содержит недопустимые флаги. */
    fun parseRaw(text: String): List<String>? {
        if (text.length > 400) return null
        val tokens = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty() || tokens.size > 40) return null
        for (tk in tokens) {
            if (tk.startsWith("--")) return null
            if (tk.startsWith("-") && tk.length >= 2 && !tk[1].isDigit() && tk[1] !in RAW_ALLOWED) return null
        }
        return tokens
    }

    /** "none" и неизвестные имена = без обхода. */
    fun forName(name: String): List<String> =
        if (name.startsWith(RAW_PREFIX)) parseRaw(name.removePrefix(RAW_PREFIX)) ?: emptyList()
        else library[name] ?: emptyList()

    /**
     * Своя стратегия для каждого сервиса: группы опций ByeDPI, разделённые --auto=none.
     * Группа по доменам срабатывает по SNI. Для сервисов с ip_ranges (Telegram подключается по IP
     * без имени сайта) добавляется вторая группа с фильтром по IP-диапазонам: в ByeDPI фильтры
     * внутри одной группы работают по "И", поэтому домены и IP идут отдельными группами.
     * Остальной трафик идёт без изменений.
     */
    fun buildGroups(selection: List<Pair<Profile, String>>): List<String> {
        val out = ArrayList<String>()
        fun group(filterFlag: String, filterValue: String, strategy: String) {
            if (out.isNotEmpty()) out.add("--auto=none")
            out.add(filterFlag)
            out.add(":$filterValue")
            out.addAll(forName(strategy))
        }
        val active = selection.filter { it.second != "none" }
        // Один активный сервис с match_all: фильтр по SNI не ставим, иначе соединения без SNI
        // (чат WhatsApp, Noise) не получают обхода. В туннель и так попадает только трафик выбранных приложений.
        if (active.size == 1 && active[0].first.matchAll) {
            out.addAll(forName(active[0].second))
            return out
        }
        for ((profile, strategy) in selection) {
            if (strategy == "none") continue
            if (profile.domains.isNotEmpty()) group("--hosts", profile.domains.joinToString(" "), strategy)
            if (profile.ipRanges.isNotEmpty()) group("--ipset", profile.ipRanges.joinToString(" "), strategy)
        }
        return out
    }
}
