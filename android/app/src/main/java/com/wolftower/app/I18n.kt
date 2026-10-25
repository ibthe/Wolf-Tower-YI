package com.wolftower.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.util.Locale

/**
 * Три языка: русский (текст в коде служит ключом), английский и чеченский.
 * Переводы лежат в i18n/translations.json (общий файл для Android и компьютера, копируется в assets).
 * Подстановки в строках: {0}, {1}... Нет перевода: показывается русский текст.
 * Технические строки журнала (log) не переводятся.
 */
object I18n {
    const val RU = "ru"
    const val EN = "en"
    const val CE = "ce"

    /** Код языка и его название на нём самом (для переключателя). */
    val LANGS = listOf(EN to "English", CE to "Нохчийн", RU to "Русский")

    /** Состояние Compose: смена языка сама перерисовывает интерфейс. */
    var lang by mutableStateOf(RU)
        private set

    private var dict: Map<String, Map<String, String>> = emptyMap()

    fun init(ctx: Context) {
        if (dict.isEmpty()) {
            dict = try {
                val json = JSONObject(ctx.assets.open("translations.json").bufferedReader(Charsets.UTF_8).use { it.readText() })
                json.keys().asSequence().associateWith { k ->
                    val o = json.getJSONObject(k)
                    o.keys().asSequence().associateWith { l -> o.getString(l) }
                }
            } catch (_: Exception) {
                emptyMap()
            }
        }
        lang = Settings.language(ctx) ?: fromSystem()
    }

    fun set(ctx: Context, code: String) {
        Settings.setLanguage(ctx, code)
        lang = code
    }

    private fun fromSystem(): String = when (Locale.getDefault().language) {
        "ru" -> RU
        "ce" -> CE
        else -> EN
    }

    fun tr(ru: String): String {
        val l = lang // чтение состояния: Compose подпишется и перерисует при смене языка
        if (l == RU) return ru
        return dict[ru]?.get(l) ?: ru
    }

    fun trf(ru: String, vararg args: Any?): String {
        var s = tr(ru)
        args.forEachIndexed { i, a -> s = s.replace("{$i}", a.toString()) }
        return s
    }

    /** Форма слова при числе: русский (3 формы), английский (1 / много), чеченский (после числа форма не меняется). */
    fun plural(n: Long, one: String, few: String, many: String): String = when (lang) {
        EN -> tr(if (n == 1L) one else few)
        CE -> tr(one)
        else -> {
            val m100 = n % 100
            val m10 = n % 10
            when {
                m100 in 11..14 -> many
                m10 == 1L -> one
                m10 in 2..4 -> few
                else -> many
            }
        }
    }
}

fun tr(ru: String): String = I18n.tr(ru)

fun trf(ru: String, vararg args: Any?): String = I18n.trf(ru, *args)
