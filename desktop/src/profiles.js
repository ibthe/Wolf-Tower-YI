const fs = require('fs');

// Имена стратегий -> аргументы ByeDPI. Те же, что в Android (StrategyArgs.kt).
// Флаги, общие для всех запусков ByeDPI (рабочего и пробного). -X: только IPv4 (прокси и mapdns IPv4-only,
// иначе при двойном стеке ByeDPI мог бы уйти на IPv6 мимо проверенного пути).
const BASE = ['-X'];

const LIBRARY = {
  // Для TLS-сервисов (YouTube, Instagram). split + disorder: рекомендация README ByeDPI для Windows,
  // где одиночный disorder не работает; на Linux/macOS тоже подходит.
  'split+disorder': ['-s1+s', '-d3+s'],
  // Цепочки в стиле ByeByeDPI: много разрезов по SNI подряд, часть из них disorder/oob (без UDP-части -a1).
  'chain-oob': ['-o1', '-s1+s', '-s3+s', '-s6+s', '-s9+s', '-s12+s', '-s15+s', '-s20+s', '-s30+s'],
  'chain-mix': ['-d1', '-s1+s', '-d3+s', '-s6+s', '-d9+s', '-s12+s', '-d15+s', '-s20+s', '-d25+s', '-s30+s', '-d35+s'],
  'chain-mix2': ['-o1', '-s1+s', '-d1+s', '-s3+s', '-d6+s', '-s12+s', '-d14+s', '-s20+s', '-d24+s', '-s30+s'],
  'chain-dis': ['-d1', '-d3+s', '-s6+s', '-d9+s', '-s20+s', '-d25+s', '-s30+s'],
  'chain-tlsrec': ['-r1+s', '-s1+s', '-s3+s', '-s6+s', '-s9+s', '-s12+s', '-s15+s', '-s20+s', '-s30+s'],
  'chain-fake': ['-f-1', '-t', '8', '-s1+s', '-s3+s', '-s6+s', '-s9+s', '-s12+s', '-s15+s', '-s20+s', '-s30+s'],
  'chain-fine': ['-s1+s', '-s2+s', '-s3+s', '-s4+s', '-s5+s', '-s6+s', '-s7+s', '-s8+s'],
  'oob-rec-split': ['-o1', '-d1', '-r1+s', '-s1+s', '-d3+s'],
  'oob-sack': ['-s1', '-q1', '-Y', '-f-1', '-r1+s'],
  // Только разделение TLS-записи по SNI, без трюков с TCP: самый мягкий вариант.
  tlsrec: ['-r1+s'],
  split: ['-s1+s'],
  disorder: ['-d1+s'],
  oob: ['-o1+s'],
  disoob: ['-q1+s'],
  'split+tlsrec': ['-s1+s', '-r1+s'],
  'disorder+tlsrec': ['-d1+s', '-r1+s'],
  'oob+tlsrec': ['-o1+s', '-r1+s'],
  'fake+disorder': ['-f-1', '-t', '8', '-d1+s'],
  'fake+oob': ['-f-1', '-t', '8', '-o1+s'],
  'fake+split': ['-f-1', '-t', '8', '-s1+s'],
  'fake+tlsrec': ['-f-1', '-t', '8', '-r1+s'],
  'fake3+disorder': ['-f-1', '-t', '3', '-d1+s'],
  'fake4+disorder': ['-f-1', '-t', '4', '-d1+s'],
  'fake6+disorder': ['-f-1', '-t', '6', '-d1+s'],
  'fake12+disorder': ['-f-1', '-t', '12', '-d1+s'],
  // Абсолютные смещения работают и на трафике без SNI (WhatsApp-чат идёт по Noise, не по TLS).
  disorder0: ['-d1'],
  split0: ['-s1'],
  // Цепочка мелких фрагментов + 1 поддельный UDP-пакет (-a1) для звонков.
  chain: ['-d1', '-s1+s', '-s3+s', '-s6+s', '-s9+s', '-s12+s', '-s15+s', '-s20+s', '-s30+s'],
  'chain+udp': ['-d1', '-s1+s', '-s3+s', '-s6+s', '-s9+s', '-s12+s', '-s15+s', '-s20+s', '-s30+s', '-a1'],
  'disorder0+udp': ['-d1', '-a1'],
  'split+udp': ['-s1+s', '-a1'],
  'disorder+udp': ['-d1+s', '-a1'],
};

const RAW_PREFIX = 'raw:';
// Разрешены только флаги самого обхода; фильтры групп, порты и режимы задаёт приложение.
const RAW_ALLOWED = 'sdoqftSnlOQeMrmaYR';

/** Строка в стиле ByeByeDPI ("raw:-d1 -s1+s -a1") -> аргументы или null, если есть недопустимые флаги. */
function parseRaw(text) {
  if (typeof text !== 'string' || text.length > 400) return null;
  const tokens = text.trim().split(/\s+/).filter(Boolean);
  if (!tokens.length || tokens.length > 40) return null;
  for (const tk of tokens) {
    if (tk.startsWith('--')) return null;
    if (tk[0] === '-' && tk.length >= 2 && !/[0-9]/.test(tk[1]) && !RAW_ALLOWED.includes(tk[1])) return null;
  }
  return tokens;
}

/** "none" и неизвестные имена = без обхода. */
function forName(name) {
  if (typeof name === 'string' && name.startsWith(RAW_PREFIX)) return parseRaw(name.slice(RAW_PREFIX.length)) || [];
  return LIBRARY[name] || [];
}

/**
 * Своя стратегия для каждого сервиса: группы опций ByeDPI, разделённые --auto=none.
 * Группа по доменам срабатывает по SNI; для сервисов с ip_ranges (Telegram) добавляется
 * вторая группа по IP-диапазонам. selection: [{ profile, strategy }]
 */
function buildGroups(selection) {
  const out = [];
  const group = (flag, value, strategy) => {
    if (out.length) out.push('--auto=none');
    out.push(flag, ':' + value, ...forName(strategy));
  };
  const active = selection.filter((s) => s.strategy !== 'none');
  // Один активный сервис с match_all: без фильтра по SNI, иначе соединения без SNI (чат WhatsApp) не получают обхода.
  if (active.length === 1 && active[0].profile.matchAll) {
    out.push(...forName(active[0].strategy));
    return out;
  }
  for (const { profile, strategy } of selection) {
    if (strategy === 'none') continue;
    if (profile.domains.length) group('--hosts', profile.domains.join(' '), strategy);
    if (profile.ipRanges.length) group('--ipset', profile.ipRanges.join(' '), strategy);
  }
  return out;
}

function parseConfig(json) {
  const profiles = (json.profiles || []).map((o) => ({
    id: o.id,
    name: o.name,
    domains: o.domains || [],
    ipRanges: o.ip_ranges || [],
    probeHosts: o.probe_hosts || [],
    blockQuic: !!o.block_quic,
    matchAll: !!o.match_all,
    // порог пробы при подборе: страница должна дойти до probeMinBytes за probeBudgetMs (0 = без лимита по времени)
    probeMinBytes: o.probe_min_bytes || 40000,
    probeBudgetMs: o.probe_budget_ms || 0,
  }));
  const strategies = json.strategies || [];
  const defaultStrategy = strategies[0];
  return { profiles, strategies, defaultStrategy };
}

function loadConfig(paths) {
  for (const p of paths) {
    try {
      return parseConfig(JSON.parse(fs.readFileSync(p, 'utf8')));
    } catch (_) {
      /* пробуем следующий путь */
    }
  }
  throw new Error('Не найден profiles.json');
}

/**
 * ByeDPI на macOS собирается без FAKE_SUPPORT и TIMEOUT_SUPPORT (params.h): флагов -f -t -S -Y -T там нет,
 * и процесс с ними сразу завершается с ошибкой параметров. Такие стратегии на macOS не предлагаются и не запускаются.
 */
function supported(strategy, platform = process.platform) {
  if (platform !== 'darwin') return true;
  const args = strategy.startsWith('raw:') ? parseRaw(strategy.slice(4)) : forName(strategy);
  return Array.isArray(args) && !args.some((t) => /^-[ftSYT]/.test(t));
}

/** Включённые сервисы: сохранённый выбор, а пока его нет, все из конфигурации. */
function enabledIds(config, store) {
  const all = config.profiles.map((p) => p.id);
  const saved = store.get('enabled', null);
  return Array.isArray(saved) ? all.filter((id) => saved.includes(id)) : all;
}

module.exports = { BASE, supported, enabledIds, LIBRARY, forName, parseRaw, buildGroups, parseConfig, loadConfig };
