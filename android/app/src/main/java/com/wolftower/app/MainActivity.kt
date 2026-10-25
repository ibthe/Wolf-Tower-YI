package com.wolftower.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlin.concurrent.thread

private fun strategyLabel(s: String): String = when {
    s == "none" -> tr("без обхода")
    s.startsWith(StrategyArgs.RAW_PREFIX) -> tr("своя строка")
    else -> s
}

class MainActivity : ComponentActivity() {
    /** Увеличивается при возврате в приложение: обновляем статусы (батарея, лицензия). */
    private val resumeTick = mutableStateOf(0)

    override fun onResume() {
        super.onResume()
        resumeTick.value = resumeTick.value + 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val config = ProfilesRepository.load(this)
        setContent { WolfTheme { Screen(config) } }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun Screen(config: ProfilesConfig) {
        val ctx = this
        val profiles = config.profiles
        val powerManager = remember { ctx.getSystemService(PowerManager::class.java) }
        fun loadStrategies() = profiles.associate { it.id to Settings.strategyFor(ctx, it.id, config.defaultStrategy) }
        var enabled by remember { mutableStateOf(Settings.enabledIds(ctx)) }
        var strategies by remember { mutableStateOf(loadStrategies()) }
        // Сервис, для которого в «Дополнительно» выбирается стратегия вручную
        var advId by remember { mutableStateOf(profiles.first().id) }
        val strategy = strategies[advId] ?: config.defaultStrategy
        var rawInput by remember {
            mutableStateOf(if (strategy.startsWith(StrategyArgs.RAW_PREFIX)) strategy.removePrefix(StrategyArgs.RAW_PREFIX) else "")
        }
        var rawError by remember { mutableStateOf<String?>(null) }
        var dohOn by remember { mutableStateOf(Settings.dohEnabled(ctx)) }
        var batteryOk by remember { mutableStateOf(powerManager.isIgnoringBatteryOptimizations(ctx.packageName)) }
        var netName by remember { mutableStateOf(NetId.describe(ctx)) }
        var crash by remember { mutableStateOf(CrashInfo.read(ctx)) }
        var status by remember { mutableStateOf(LicenseManager.status(ctx)) }
        var keyInput by remember { mutableStateOf("") }
        var licenseMsg by remember { mutableStateOf<String?>(null) }
        var changingKey by remember { mutableStateOf(false) }
        var justActivated by remember { mutableStateOf(false) }
        var showLicenses by remember { mutableStateOf(false) }
        var advancedOpen by remember { mutableStateOf(false) }
        var logOpen by remember { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }
        val logScroll = rememberScrollState()
        val clipboard = LocalClipboardManager.current
        val running by DpiVpnService.running.collectAsState()
        val log by DpiVpnService.log.collectAsState()
        val probe by AutoProbe.state.collectAsState()
        val tick = resumeTick.value
        val canRun = status.canRun
        val chosenProfiles = profiles.filter { it.id in enabled }
        val chosenNames = chosenProfiles.joinToString(tr(" и ")) { it.name }
        val licensed = status as? LicenseStatus.Licensed
        val deviceId = remember { LicenseManager.deviceId(ctx) }

        val notifPermission = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { }

        // При открытии: просим разрешение на уведомления (один раз) и сверяем часы с сетью
        LaunchedEffect(Unit) {
            if (Build.VERSION.SDK_INT >= 33 && !Settings.notifAsked(ctx) &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                Settings.setNotifAsked(ctx)
                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            thread(isDaemon = true) {
                LicenseManager.networkCheck(ctx)
                runOnUiThread { status = LicenseManager.status(ctx) }
            }
        }
        // Возврат в приложение и смена состояния защиты: обновляем статусы
        LaunchedEffect(tick, running) {
            status = LicenseManager.status(ctx)
            batteryOk = powerManager.isIgnoringBatteryOptimizations(ctx.packageName)
            netName = NetId.describe(ctx)
            enabled = Settings.enabledIds(ctx)
            strategies = loadStrategies()
        }
        // После автоподбора подтягиваем выбранные стратегии в интерфейс
        LaunchedEffect(probe.done) {
            if (probe.done) strategies = loadStrategies()
        }
        // Лог прокручивается к последней строке
        LaunchedEffect(log.size, logOpen) {
            if (logOpen) logScroll.scrollTo(logScroll.maxValue)
        }
        // Подпись «Ключ принят» держится пару секунд
        LaunchedEffect(justActivated) {
            if (justActivated) {
                delay(3000)
                justActivated = false
            }
        }

        fun startVpn() {
            startService(Intent(ctx, DpiVpnService::class.java).setAction(DpiVpnService.ACTION_START))
        }

        val vpnPermission = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { r -> if (r.resultCode == Activity.RESULT_OK) startVpn() }

        Surface(color = WT.Bg, contentColor = WT.Text, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(WT.BgTop, WT.Bg)))) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    // ---------- Шапка ----------
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Image(
                            painter = painterResource(R.drawable.wolf_logo),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(46.dp).clip(RoundedCornerShape(13.dp)),
                        )
                        Column {
                            Text("Wolf Tower", color = WT.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Text(tr("Разблокировка YouTube и Instagram"), color = WT.TextDim, fontSize = 13.sp)
                        }
                    }

                    // ---------- Язык ----------
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        I18n.LANGS.forEach { (code, name) ->
                            WtChip(
                                text = name,
                                selected = I18n.lang == code,
                                enabled = true,
                                onClick = { I18n.set(ctx, code) },
                            )
                        }
                    }

                    // ---------- Главная кнопка и статус ----------
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        PowerButton(
                            running = running,
                            busy = busy || probe.running,
                            enabled = running || (canRun && chosenProfiles.isNotEmpty()),
                            onClick = {
                                if (running) {
                                    startService(
                                        Intent(ctx, DpiVpnService::class.java).setAction(DpiVpnService.ACTION_STOP)
                                    )
                                } else {
                                    busy = true
                                    thread(isDaemon = true) {
                                        LicenseManager.networkCheck(ctx)
                                        runOnUiThread {
                                            status = LicenseManager.status(ctx)
                                            busy = false
                                            if (status.canRun) {
                                                val prepare = VpnService.prepare(ctx)
                                                if (prepare != null) vpnPermission.launch(prepare) else startVpn()
                                            }
                                        }
                                    }
                                }
                            },
                        )
                        Text(
                            when {
                                running -> tr("Защита включена")
                                probe.running -> tr("Подбор стратегии…")
                                busy -> tr("Проверка лицензии…")
                                else -> tr("Защита выключена")
                            },
                            color = if (running) WT.AccentGlow else WT.Text,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.size(4.dp))
                        Text(
                            when {
                                running -> trf(
                                    if (chosenProfiles.size == 1) "{0} идёт через обход. Сеть: {1}" else "{0} идут через обход. Сеть: {1}",
                                    chosenNames, netName,
                                )
                                !canRun -> tr("Для запуска введите лицензионный ключ")
                                chosenProfiles.isEmpty() -> tr("Включите хотя бы один сервис ниже")
                                else -> trf("Нажмите кнопку, чтобы разблокировать {0}", chosenNames)
                            },
                            color = if (!running && (!canRun || chosenProfiles.isEmpty())) WT.Amber else WT.TextDim,
                            fontSize = 13.sp,
                        )
                    }

                    // ---------- Сервисы ----------
                    WtCard {
                        Text(tr("Сервисы"), color = WT.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        profiles.forEach { p ->
                            val score = probe.scores[p.id]?.let { trf(" (проверка: {0})", it) } ?: ""
                            ServiceRow(
                                id = p.id,
                                name = p.name,
                                subtitle = tr("Стратегия: ") + strategyLabel(strategies[p.id] ?: config.defaultStrategy) + score,
                                checked = p.id in enabled,
                                enabled = !running,
                                onChange = { on ->
                                    Settings.setEnabled(ctx, p.id, on)
                                    enabled = Settings.enabledIds(ctx)
                                },
                            )
                        }
                        if (running) {
                            Text(tr("Чтобы изменить набор сервисов, сначала остановите защиту."), color = WT.TextDim, fontSize = 12.sp)
                        }
                    }

                    // ---------- Лицензия ----------
                    if (licensed != null && !changingKey) {
                        // Активирована: только компактная плашка со сроком, поля ключа убраны
                        val untilMs = licensed.untilMs
                        val left = LicenseManager.remainingText(licensed)
                        val soon = untilMs != null && untilMs - System.currentTimeMillis() < 7L * 24 * 3600 * 1000
                        val shape = RoundedCornerShape(16.dp)
                        Row(
                            Modifier.fillMaxWidth().clip(shape).background(WT.Surface).border(1.dp, WT.Outline, shape)
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            KeyGlyph(if (soon) WT.Amber else WT.Ok, 22.dp)
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    when {
                                        justActivated -> tr("Ключ принят")
                                        licensed.plan == "trial" -> tr("Пробная лицензия")
                                        else -> tr("Лицензия активна")
                                    },
                                    color = WT.Text,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 15.sp,
                                )
                                Text(
                                    if (left != null) trf("Осталось {0}", left) else tr("Бессрочная"),
                                    color = if (soon) WT.Amber else WT.TextDim,
                                    fontSize = 13.sp,
                                )
                            }
                        }
                    } else {
                        WtCard {
                            Text(tr("Активация"), color = WT.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                            if (licensed == null) {
                                Text(LicenseManager.describe(status), color = WT.TextDim, fontSize = 13.sp)
                            }
                            Text(tr("ID устройства (нужен для ключа с привязкой)"), color = WT.TextDim, fontSize = 12.sp)
                            Box(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(WT.SurfaceHigh).padding(14.dp)
                            ) {
                                SelectionContainer {
                                    Text(
                                        deviceId,
                                        color = WT.Text,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        letterSpacing = 1.sp,
                                    )
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CopyPill(
                                    label = tr("Копировать ID"),
                                    doneLabel = tr("Скопировано"),
                                    onCopy = { clipboard.setText(AnnotatedString(deviceId)) },
                                )
                                SharePill(label = tr("Поделиться"), onClick = {
                                    val send = Intent(Intent.ACTION_SEND)
                                        .setType("text/plain")
                                        .putExtra(Intent.EXTRA_TEXT, trf("ID устройства Wolf Tower: {0}", deviceId))
                                    startActivity(Intent.createChooser(send, tr("Отправить ID")))
                                })
                            }
                            WtTextField(value = keyInput, onValueChange = { keyInput = it }, label = tr("Лицензионный ключ"))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                WtButton(
                                    text = if (busy) tr("Проверка…") else tr("Активировать"),
                                    enabled = keyInput.isNotBlank() && !busy,
                                    onClick = {
                                        busy = true
                                        val input = keyInput
                                        thread(isDaemon = true) {
                                            val error = LicenseManager.activate(ctx, input)
                                            runOnUiThread {
                                                licenseMsg = error
                                                if (error == null) {
                                                    keyInput = ""
                                                    changingKey = false
                                                    justActivated = true
                                                }
                                                status = LicenseManager.status(ctx)
                                                busy = false
                                            }
                                        }
                                    },
                                )
                                WtButton(
                                    text = tr("Вставить"),
                                    primary = false,
                                    onClick = { clipboard.getText()?.text?.let { keyInput = it.trim() } },
                                )
                                if (licensed != null) {
                                    WtButton(text = tr("Отмена"), primary = false, onClick = {
                                        changingKey = false
                                        licenseMsg = null
                                    })
                                }
                            }
                            licenseMsg?.let { Text(it, color = WT.Amber, fontSize = 13.sp) }
                        }
                    }

                    // ---------- Сбой прошлого запуска ----------
                    if (crash != null) {
                        WtCard {
                            Text(tr("Последний сбой (можно скопировать и прислать)"), color = WT.Text, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                            SelectionContainer { Text(crash ?: "", color = WT.TextDim, fontSize = 12.sp) }
                            WtButton(text = tr("Скрыть"), primary = false, onClick = {
                                CrashInfo.dismiss(ctx)
                                crash = null
                            })
                        }
                    }

                    // ---------- Стратегия ----------
                    WtCard {
                        Text(tr("Стратегия обхода"), color = WT.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            tr("Для каждого сервиса подбирается свой вариант. Проверка YouTube учитывает и скорость: замедление, при котором страница открывается, но медленно, не засчитывается как успех."),
                            color = WT.TextDim,
                            fontSize = 12.sp,
                        )
                        WtButton(
                            text = tr("Подобрать автоматически"),
                            primary = false,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !running && !probe.running && canRun && chosenProfiles.isNotEmpty(),
                            onClick = { AutoProbe.start(ctx.applicationContext, chosenProfiles, config.strategies) },
                        )
                        if (running) {
                            Text(
                                tr("Защита работает: приложение само проверяет доступность и временно меняет стратегию, если сервис перестаёт открываться. Для ручного подбора сначала остановите защиту."),
                                color = WT.TextDim,
                                fontSize = 12.sp,
                            )
                        }
                        if (probe.running) {
                            LinearProgressIndicator(Modifier.fillMaxWidth(), color = WT.Accent, trackColor = WT.SurfaceHigh)
                            Text(probe.progress, color = WT.TextDim, fontSize = 12.sp)
                            WtButton(text = tr("Отмена"), primary = false, onClick = { AutoProbe.cancel() })
                        }
                        if (probe.report.isNotEmpty()) {
                            Text(tr("Результат подбора"), color = WT.Text, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                            Text(probe.report, color = WT.TextDim, fontSize = 12.sp)
                        }
                    }

                    // ---------- Работа в фоне ----------
                    if (!batteryOk) {
                        WtCard {
                            Text(tr("Работа в фоне"), color = WT.Text, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                            Text(
                                tr("Чтобы система не отключала защиту, разрешите приложению работать без ограничений батареи. На Xiaomi, Huawei, Samsung и других дополнительно включите автозапуск и снимите ограничения фона в настройках приложения."),
                                color = WT.TextDim,
                                fontSize = 13.sp,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                WtButton(text = tr("Разрешить"), onClick = {
                                    try {
                                        startActivity(
                                            Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                                .setData(Uri.parse("package:${ctx.packageName}"))
                                        )
                                    } catch (_: Exception) {
                                    }
                                })
                                WtButton(text = tr("Настройки"), primary = false, onClick = {
                                    startActivity(
                                        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                            .setData(Uri.parse("package:${ctx.packageName}"))
                                    )
                                })
                            }
                        }
                    }

                    // ---------- Дополнительно ----------
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { advancedOpen = !advancedOpen }
                            .padding(horizontal = 4.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(tr("Дополнительно"), color = WT.TextDim, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        ChevronGlyph(WT.TextDim, 22.dp, advancedOpen)
                    }
                    if (advancedOpen) {
                        WtCard {
                            Text(tr("Выбрать стратегию вручную"), color = WT.Text, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                profiles.forEach { p ->
                                    WtChip(
                                        text = p.name,
                                        selected = p.id == advId,
                                        enabled = true,
                                        onClick = {
                                            advId = p.id
                                            val cur = strategies[p.id] ?: config.defaultStrategy
                                            rawInput = if (cur.startsWith(StrategyArgs.RAW_PREFIX)) cur.removePrefix(StrategyArgs.RAW_PREFIX) else ""
                                            rawError = null
                                        },
                                    )
                                }
                            }
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                (listOf("none") + config.strategies).forEach { s ->
                                    WtChip(
                                        text = strategyLabel(s),
                                        selected = s == strategy,
                                        enabled = !running,
                                        onClick = {
                                            Settings.setStrategyFor(ctx, advId, s)
                                            strategies = strategies + (advId to s)
                                        },
                                    )
                                }
                            }
                            Text(
                                tr("Своя строка (формат ByeByeDPI), например: -d1 -s1+s -s3+s -a1"),
                                color = WT.TextDim,
                                fontSize = 12.sp,
                            )
                            WtTextField(
                                value = rawInput,
                                onValueChange = {
                                    rawInput = it
                                    rawError = null
                                },
                                label = tr("Аргументы обхода"),
                            )
                            rawError?.let { Text(it, color = WT.Amber, fontSize = 12.sp) }
                            WtButton(
                                text = tr("Применить"),
                                primary = false,
                                enabled = !running && rawInput.isNotBlank(),
                                onClick = {
                                    if (StrategyArgs.parseRaw(rawInput) == null) {
                                        rawError = tr("Допустимы только флаги обхода: -s -d -o -q -f -t -r -a и т.п.")
                                    } else {
                                        val value = StrategyArgs.RAW_PREFIX + rawInput.trim()
                                        Settings.setStrategyFor(ctx, advId, value)
                                        strategies = strategies + (advId to value)
                                        rawError = null
                                    }
                                },
                            )
                        }
                        WtCard {
                            WtSwitchRow(
                                title = tr("Защищённый DNS (DoH)"),
                                subtitle = tr("Экспериментально. Помогает, если DNS оператора подменяет ответы, но отключает UDP: звонки в Instagram работать не будут. Видео YouTube и ленты идут по TCP и не страдают."),
                                checked = dohOn,
                                enabled = !running,
                                onChange = {
                                    Settings.setDoh(ctx, it)
                                    dohOn = it
                                },
                            )
                        }
                        WtCard {
                            Row(
                                Modifier.fillMaxWidth().clickable { logOpen = !logOpen },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(trf("Лог ({0})", log.size), color = WT.Text, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                                ChevronGlyph(WT.TextDim, 22.dp, logOpen)
                            }
                            if (!logOpen) {
                                Text(
                                    log.lastOrNull() ?: tr("Пока пусто"),
                                    color = WT.TextDim,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            } else {
                                Box(Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(logScroll)) {
                                    SelectionContainer {
                                        Text(
                                            log.takeLast(100).joinToString("\n"),
                                            color = WT.TextDim,
                                            fontSize = 12.sp,
                                            fontFamily = FontFamily.Monospace,
                                        )
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    CopyPill(
                                        label = tr("Копировать лог"),
                                        doneLabel = tr("Скопировано"),
                                        onCopy = { clipboard.setText(AnnotatedString(log.joinToString("\n"))) },
                                    )
                                    WtButton(text = tr("Очистить"), primary = false, onClick = { DpiVpnService.clearLog() })
                                }
                            }
                        }
                        WtCard {
                            if (licensed != null && !changingKey) {
                                WtButton(
                                    text = tr("Ввести другой ключ"),
                                    primary = false,
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = { changingKey = true },
                                )
                            }
                            WtButton(
                                text = tr("Отправить диагностику"),
                                primary = false,
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    val send = Intent(Intent.ACTION_SEND)
                                        .setType("text/plain")
                                        .putExtra(Intent.EXTRA_SUBJECT, tr("Wolf Tower: диагностика"))
                                        .putExtra(Intent.EXTRA_TEXT, DiagReport.build(ctx, config))
                                    startActivity(Intent.createChooser(send, tr("Отправить диагностику")))
                                },
                            )
                            Text(
                                tr("В отчёт попадают версия Android, модель, тип сети и оператор, выбранная стратегия и лог. IP-адреса, трафик и сам ключ не включаются."),
                                color = WT.TextDim,
                                fontSize = 12.sp,
                            )
                            WtButton(
                                text = if (showLicenses) tr("Скрыть лицензии компонентов") else tr("Лицензии компонентов"),
                                primary = false,
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { showLicenses = !showLicenses },
                            )
                            if (showLicenses) {
                                val text = remember {
                                    try {
                                        ctx.assets.open("third_party_licenses.txt").bufferedReader().use { it.readText() }
                                    } catch (_: Exception) {
                                        tr("Не удалось прочитать файл лицензий")
                                    }
                                }
                                SelectionContainer { Text(text, color = WT.TextDim, fontSize = 12.sp) }
                            }
                        }
                    }
                    Spacer(Modifier.size(12.dp))
                }
            }
        }
    }
}
