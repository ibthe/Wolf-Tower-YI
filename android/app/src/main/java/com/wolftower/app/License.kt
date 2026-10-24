package com.wolftower.app

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.LocalDate
import java.time.ZoneOffset

sealed class LicenseStatus {
    /** until == null: бессрочная. plan == "trial": пробная лицензия. */
    data class Licensed(val until: String?, val plan: String, val untilMs: Long? = null) : LicenseStatus()
    object NotActivated : LicenseStatus()
    object LicenseExpired : LicenseStatus()
    object ClockTampered : LicenseStatus()

    val canRun: Boolean get() = this is Licensed
}

/**
 * Офлайн-лицензии: ключ подписан приватным ключом продавца (ECDSA P-256), приложение проверяет подпись
 * публичным ключом из assets/license_public_key.txt. Автоматического пробного периода нет: без ключа
 * защита не запускается. Пробная версия это обычный ключ с plan=trial и коротким сроком, который
 * привязывается к устройству. Срок в ключе абсолютный (дата), поэтому очистка данных или переустановка
 * его не продлевают: приложение просто снова попросит ключ.
 *
 * Сверка времени: когда есть интернет, часы телефона сравниваются с сетевым временем (ClockGuard). Если
 * телефон отстаёт больше чем на час, ключ деактивируется (удаляется), а повторная активация блокируется,
 * пока проверка времени не пройдёт при нормальных часах. Без интернета проверка не работает, и человек с
 * неверным временем и без сети может пользоваться (осознанное решение). Плюс локальная защита от отката
 * по записи о прошлом запуске.
 *
 * Ограничения (честно): очистка данных сбрасывает записи, патч APK убирает проверку, а блокировка доступа
 * приложения к сайтам-источникам времени отключает сверку. Для надёжной защиты нужен сервер активации.
 */
enum class NetCheck { OFFLINE, OK, BEHIND }

object LicenseManager {
    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val CLOCK_TOLERANCE_MS = 2 * DAY_MS

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("license", Context.MODE_PRIVATE)

    /** Короткий идентификатор устройства для ключей с привязкой. */
    fun deviceId(ctx: Context): String {
        val androidId = android.provider.Settings.Secure.getString(
            ctx.contentResolver, android.provider.Settings.Secure.ANDROID_ID
        ) ?: "unknown"
        val hash = MessageDigest.getInstance("SHA-256").digest(("wt:$androidId").toByteArray())
        return hash.joinToString("") { "%02X".format(it) }.take(12).chunked(4).joinToString("-")
    }

    private sealed class VerifyResult {
        class Ok(val plan: String, val untilMs: Long?, val untilText: String?) : VerifyResult()
        class Bad(val reason: String) : VerifyResult()
    }

    private fun loadPublicKey(ctx: Context): PublicKey? = try {
        val text = ctx.assets.open("license_public_key.txt").bufferedReader().use { it.readText() }.trim()
        if (text.isEmpty() || text.startsWith("REPLACE")) {
            null
        } else {
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.decode(text, Base64.DEFAULT)))
        }
    } catch (_: Exception) {
        null
    }

    private fun verifyKey(ctx: Context, key: String): VerifyResult {
        try {
            val parts = key.trim().split(".")
            if (parts.size != 3 || parts[0] != "WT1") return VerifyResult.Bad("Неверный формат ключа")
            val flags = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
            val payload = Base64.decode(parts[1], flags)
            val sig = Base64.decode(parts[2], flags)

            val pub = loadPublicKey(ctx) ?: return VerifyResult.Bad("В приложении не задан публичный ключ лицензий")
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(pub)
            verifier.update(payload)
            if (!verifier.verify(sig)) return VerifyResult.Bad("Ключ не прошёл проверку подписи")

            val json = JSONObject(String(payload, Charsets.UTF_8))
            val dev = json.optString("dev", "")
            if (dev.isNotEmpty() && dev != deviceId(ctx)) {
                return VerifyResult.Bad("Ключ выпущен для другого устройства")
            }
            val exp = json.optString("exp", "")
            var untilMs: Long? = null
            if (exp.isNotEmpty()) {
                // действует весь указанный день включительно (UTC)
                untilMs = LocalDate.parse(exp).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            }
            return VerifyResult.Ok(json.optString("plan", "pro"), untilMs, exp.ifEmpty { null })
        } catch (_: Exception) {
            return VerifyResult.Bad("Неверный формат ключа")
        }
    }

    private fun violationFlag(ctx: Context) = prefs(ctx).getBoolean("clock_violation", false)

    /**
     * Сверка часов с сетью. ТОЛЬКО из фонового потока. Телефон отстаёт: ключ удаляется, ставится флаг
     * нарушения. Время в норме: флаг снимается. Нет сети: ничего не меняется.
     */
    fun networkCheck(ctx: Context): NetCheck {
        val skew = ClockGuard.measureSkewMs() ?: return NetCheck.OFFLINE
        val p = prefs(ctx)
        if (skew < -ClockGuard.TOLERANCE_MS) {
            p.edit().remove("license_key").putBoolean("clock_violation", true).apply()
            return NetCheck.BEHIND
        }
        if (p.getBoolean("clock_violation", false)) p.edit().putBoolean("clock_violation", false).apply()
        return NetCheck.OK
    }

    /** Возвращает текст ошибки или null, если ключ принят и сохранён. ТОЛЬКО из фонового потока. */
    fun activate(ctx: Context, key: String): String? {
        if (networkCheck(ctx) == NetCheck.BEHIND) {
            return "Время на телефоне отстаёт от сетевого. Включите автоматическое время и повторите."
        }
        if (violationFlag(ctx)) {
            return "Нужен интернет, чтобы проверить время после прошлого нарушения."
        }
        return when (val r = verifyKey(ctx, key)) {
            is VerifyResult.Bad -> r.reason
            is VerifyResult.Ok -> {
                val until = r.untilMs
                if (until != null && System.currentTimeMillis() >= until) {
                    "Срок действия этого ключа уже истёк"
                } else {
                    prefs(ctx).edit().putString("license_key", key.trim()).apply()
                    null
                }
            }
        }
    }

    fun status(ctx: Context): LicenseStatus {
        val p = prefs(ctx)
        val now = System.currentTimeMillis()

        // Защита от отката времени назад
        val lastSeen = p.getLong("last_seen", 0L)
        val tampered = lastSeen > 0 && now < lastSeen - CLOCK_TOLERANCE_MS
        if (!tampered && now > lastSeen) p.edit().putLong("last_seen", now).apply()

        if (violationFlag(ctx)) return LicenseStatus.ClockTampered

        var licenseExpired = false
        val stored = p.getString("license_key", null)
        if (stored != null) {
            val r = verifyKey(ctx, stored)
            if (r is VerifyResult.Ok) {
                val until = r.untilMs
                if (until == null) return LicenseStatus.Licensed(null, r.plan)
                if (!tampered) {
                    if (now < until) return LicenseStatus.Licensed(r.untilText, r.plan, until)
                    licenseExpired = true
                }
            }
        }
        if (tampered) return LicenseStatus.ClockTampered
        return if (licenseExpired) LicenseStatus.LicenseExpired else LicenseStatus.NotActivated
    }

    fun canRun(ctx: Context): Boolean = status(ctx).canRun

    private fun ruPlural(n: Long, one: String, few: String, many: String): String {
        val m100 = n % 100
        val m10 = n % 10
        return when {
            m100 in 11..14 -> many
            m10 == 1L -> one
            m10 in 2..4 -> few
            else -> many
        }
    }

    /** "142 дня", "5 часов" и т.п. до конца срока; null, если лицензия бессрочная или срок вышел. */
    fun remainingText(s: LicenseStatus.Licensed): String? {
        val until = s.untilMs ?: return null
        val left = until - System.currentTimeMillis()
        if (left <= 0) return null
        val days = left / DAY_MS
        if (days >= 1) return "$days " + ruPlural(days, "день", "дня", "дней")
        val hours = maxOf(1L, left / (60L * 60 * 1000))
        return "$hours " + ruPlural(hours, "час", "часа", "часов")
    }

    fun describe(s: LicenseStatus): String = when (s) {
        is LicenseStatus.Licensed -> {
            val kind = if (s.plan == "trial") "Пробная лицензия" else "Лицензия активна"
            if (s.until == null) "$kind (бессрочная)" else "$kind до ${s.until}"
        }
        LicenseStatus.NotActivated ->
            "Приложение не активировано. Введите лицензионный или пробный ключ."
        LicenseStatus.LicenseExpired -> "Срок лицензии истёк. Введите новый ключ."
        LicenseStatus.ClockTampered ->
            "Время на телефоне отстаёт от сетевого, ключ деактивирован. Включите автоматическое время, подключитесь к интернету и введите ключ заново."
    }
}
