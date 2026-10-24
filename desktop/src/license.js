const crypto = require('crypto');
const fs = require('fs');
const https = require('https');
const os = require('os');
const { execFile } = require('child_process');

const DAY = 24 * 60 * 60 * 1000;
const CLOCK_TOLERANCE_LOCAL = 2 * DAY; // откат по записи о прошлом запуске
const CLOCK_TOLERANCE_NET = 60 * 60 * 1000; // расхождение с сетевым временем
const SOURCES = [
  'https://ya.ru/',
  'https://www.google.com/',
  'https://www.microsoft.com/',
  'https://www.cloudflare.com/',
  'https://www.apple.com/',
];

function run(cmd, args) {
  return new Promise((resolve) => {
    execFile(cmd, args, { windowsHide: true, timeout: 8000 }, (err, stdout) => resolve(err ? '' : String(stdout)));
  });
}

/** Идентификатор машины: GUID Windows, UUID macOS, machine-id Linux. */
async function readMachineId() {
  try {
    if (process.platform === 'win32') {
      const out = await run('reg', ['query', 'HKLM\\SOFTWARE\\Microsoft\\Cryptography', '/v', 'MachineGuid']);
      const m = out.match(/MachineGuid\s+REG_SZ\s+(\S+)/);
      if (m) return m[1];
    } else if (process.platform === 'darwin') {
      const out = await run('ioreg', ['-rd1', '-c', 'IOPlatformExpertDevice']);
      const m = out.match(/"IOPlatformUUID"\s*=\s*"([^"]+)"/);
      if (m) return m[1];
    } else {
      return fs.readFileSync('/etc/machine-id', 'utf8').trim();
    }
  } catch (_) {
    /* запасной вариант ниже */
  }
  return os.hostname();
}

/** Тот же формат, что в Android: ABCD-EF01-2345. */
function deviceId(machineId) {
  const h = crypto.createHash('sha256').update('wt:' + machineId).digest('hex').toUpperCase().slice(0, 12);
  return `${h.slice(0, 4)}-${h.slice(4, 8)}-${h.slice(8, 12)}`;
}

function loadPublicKey(file) {
  try {
    const text = fs.readFileSync(file, 'utf8').trim();
    if (!text || text.startsWith('REPLACE')) return null;
    return text;
  } catch (_) {
    return null;
  }
}

function headDateSkew(url) {
  return new Promise((resolve) => {
    const t0 = Date.now();
    let settled = false;
    const finish = (v) => {
      if (!settled) {
        settled = true;
        resolve(v);
      }
    };
    try {
      const req = https.request(url, { method: 'HEAD', timeout: 3000 }, (res) => {
        const t1 = Date.now();
        const net = Date.parse(res.headers.date || '');
        res.resume();
        finish(Number.isNaN(net) ? null : Math.round((t0 + t1) / 2 - net));
      });
      req.on('timeout', () => {
        req.destroy();
        finish(null);
      });
      req.on('error', () => finish(null));
      req.end();
    } catch (_) {
      finish(null);
    }
  });
}

/** Часы компьютера минус сетевое время, мс (отрицательное = компьютер отстаёт); null, если сети нет. */
async function measureSkewMs() {
  const all = await Promise.all(SOURCES.map(headDateSkew));
  const got = all.filter((v) => v !== null).slice(0, 2);
  if (!got.length) return null;
  return got.reduce((a, b) => (Math.abs(a) <= Math.abs(b) ? a : b));
}

/**
 * Офлайн-лицензии (те же ключи, что в Android): подпись ECDSA P-256 / SHA-256, ключ WT1.<payload>.<подпись>.
 * Автоматического пробного периода нет: без ключа защита не запускается.
 */
class License {
  constructor({ store, publicKeyB64, machineId, now = () => Date.now(), skewFn = measureSkewMs }) {
    this.store = store;
    this.publicKeyB64 = publicKeyB64;
    this.machineId = machineId;
    this.now = now;
    this.skewFn = skewFn;
  }

  get deviceId() {
    return deviceId(this.machineId);
  }

  verifyKey(key) {
    try {
      const parts = String(key || '').trim().split('.');
      if (parts.length !== 3 || parts[0] !== 'WT1') return { ok: false, reason: 'Неверный формат ключа' };
      const payload = Buffer.from(parts[1], 'base64url');
      const sig = Buffer.from(parts[2], 'base64url');
      if (!this.publicKeyB64) return { ok: false, reason: 'В приложении не задан публичный ключ лицензий' };
      const pub = crypto.createPublicKey({
        key: Buffer.from(this.publicKeyB64.replace(/\s+/g, ''), 'base64'),
        format: 'der',
        type: 'spki',
      });
      if (!crypto.verify('sha256', payload, { key: pub, dsaEncoding: 'der' }, sig)) {
        return { ok: false, reason: 'Ключ не прошёл проверку подписи' };
      }
      const json = JSON.parse(payload.toString('utf8'));
      const dev = json.dev || '';
      if (dev && dev !== this.deviceId) return { ok: false, reason: 'Ключ выпущен для другого устройства' };
      const exp = json.exp || '';
      let untilMs = null;
      if (exp) {
        const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(exp);
        if (!m) return { ok: false, reason: 'Неверный формат ключа' };
        untilMs = Date.UTC(+m[1], +m[2] - 1, +m[3]) + DAY; // действует весь указанный день (UTC)
      }
      return { ok: true, plan: json.plan || 'pro', untilMs, untilText: exp || null };
    } catch (_) {
      return { ok: false, reason: 'Неверный формат ключа' };
    }
  }

  violation() {
    return this.store.get('clock_violation', false);
  }

  /** kind: licensed | not_activated | expired | tampered */
  status() {
    const now = this.now();
    const lastSeen = this.store.get('last_seen', 0);
    const tampered = lastSeen > 0 && now < lastSeen - CLOCK_TOLERANCE_LOCAL;
    if (!tampered && now > lastSeen) this.store.set('last_seen', now);
    if (this.violation()) return { kind: 'tampered' };

    let expired = false;
    const stored = this.store.get('license_key', null);
    if (stored) {
      const r = this.verifyKey(stored);
      if (r.ok) {
        if (r.untilMs === null) return { kind: 'licensed', until: null, plan: r.plan, untilMs: null };
        if (!tampered) {
          if (now < r.untilMs) return { kind: 'licensed', until: r.untilText, plan: r.plan, untilMs: r.untilMs };
          expired = true;
        }
      }
    }
    if (tampered) return { kind: 'tampered' };
    return { kind: expired ? 'expired' : 'not_activated' };
  }

  canRun() {
    return this.status().kind === 'licensed';
  }

  /** Сверка часов с сетью. BEHIND: компьютер отстаёт, ключ удаляется. OFFLINE: сети нет, ничего не меняется. */
  async networkCheck() {
    const skew = await this.skewFn();
    if (skew === null || skew === undefined) return 'OFFLINE';
    if (skew < -CLOCK_TOLERANCE_NET) {
      this.store.delete('license_key');
      this.store.set('clock_violation', true);
      return 'BEHIND';
    }
    if (this.violation()) this.store.set('clock_violation', false);
    return 'OK';
  }

  /** Возвращает текст ошибки или null, если ключ принят и сохранён. */
  async activate(key) {
    if ((await this.networkCheck()) === 'BEHIND') {
      return 'Время на компьютере отстаёт от сетевого. Включите автоматическое время и повторите.';
    }
    if (this.violation()) return 'Нужен интернет, чтобы проверить время после прошлого нарушения.';
    const r = this.verifyKey(key);
    if (!r.ok) return r.reason;
    if (r.untilMs !== null && this.now() >= r.untilMs) return 'Срок действия этого ключа уже истёк';
    this.store.set('license_key', String(key).trim());
    return null;
  }

  describe(s = this.status()) {
    switch (s.kind) {
      case 'licensed': {
        const kind = s.plan === 'trial' ? 'Пробная лицензия' : 'Лицензия активна';
        return s.until ? `${kind} до ${s.until}` : `${kind} (бессрочная)`;
      }
      case 'not_activated':
        return 'Приложение не активировано. Введите лицензионный или пробный ключ.';
      case 'expired':
        return 'Срок лицензии истёк. Введите новый ключ.';
      default:
        return 'Время на компьютере отстаёт от сетевого, ключ деактивирован. Включите автоматическое время, подключитесь к интернету и введите ключ заново.';
    }
  }
}

module.exports = { License, deviceId, readMachineId, loadPublicKey, measureSkewMs };
