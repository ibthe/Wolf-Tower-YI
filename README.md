# Wolf Tower

Приложение для снятия DPI-блокировок. Эта версия настроена на **YouTube** и **Instagram** (оформление в стиле обоих сервисов). Подробности и ограничения: [ANALYSIS_YT_IG.md](ANALYSIS_YT_IG.md).
Этап 1: Android. Далее Windows и macOS.

Подробности архитектуры: SPEC.md.

## Сборка
    gradle -p android assembleDebug

Исходники ByeDPI лежат в third_party/byedpi (MIT, коммит ba53229 репозитория hufrea/byedpi).

Компоненты: ByeDPI (MIT), zapret (MIT), hev-socks5-tunnel (MIT, вендорен в third_party), WinDivert (LGPL-3 / GPL-2).
