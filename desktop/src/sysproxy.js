const { execFile } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');

function run(cmd, args, opts = {}) {
  return new Promise((resolve, reject) => {
    execFile(cmd, args, { windowsHide: true, timeout: 60000, ...opts }, (err, stdout, stderr) => {
      if (err) {
        err.stderr = stderr;
        reject(err);
      } else {
        resolve({ stdout: String(stdout), stderr: String(stderr) });
      }
    });
  });
}

// ------------------------------------------------------------------ Windows (реестр WinINET, без прав администратора)

const WIN_KEY = 'HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings';

/** Значение из вывода `reg query`. */
function parseRegValue(stdout, name) {
  const m = String(stdout).match(new RegExp(name + '\\s+REG_\\w+\\s+(.*)'));
  return m ? m[1].trim() : null;
}

async function winGet(name) {
  try {
    const { stdout } = await run('reg', ['query', WIN_KEY, '/v', name]);
    return parseRegValue(stdout, name);
  } catch (_) {
    return null;
  }
}

async function winSet(name, value) {
  await run('reg', ['add', WIN_KEY, '/v', name, '/t', 'REG_SZ', '/d', value, '/f']);
}

async function winDelete(name) {
  try {
    await run('reg', ['delete', WIN_KEY, '/v', name, '/f']);
  } catch (_) {
    /* значения могло не быть */
  }
}

async function winRefresh() {
  const script =
    "$sig='[DllImport(\"wininet.dll\", SetLastError=true)] public static extern bool InternetSetOption(IntPtr h, int o, IntPtr b, int l);';" +
    '$t=Add-Type -MemberDefinition $sig -Name WI -Namespace WT -PassThru;' +
    '$t::InternetSetOption([IntPtr]::Zero,39,[IntPtr]::Zero,0)|Out-Null;' +
    '$t::InternetSetOption([IntPtr]::Zero,37,[IntPtr]::Zero,0)|Out-Null';
  try {
    await run('powershell', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command', script]);
  } catch (_) {
    /* настройка применится при следующем обновлении системы */
  }
}

// ------------------------------------------------------------------ macOS (networksetup, нужен пароль администратора)

const sq = (s) => `'${String(s).replace(/'/g, `'\\''`)}'`;

async function macServices() {
  const { stdout } = await run('networksetup', ['-listallnetworkservices']);
  return stdout
    .split('\n')
    .slice(1)
    .map((s) => s.trim())
    .filter((s) => s && !s.startsWith('*'));
}

/** Разбор вывода `networksetup -getautoproxyurl`. */
function parseAutoProxy(stdout) {
  const url = (String(stdout).match(/^URL:\s*(.*)$/m) || [])[1] || '';
  const enabled = /yes/i.test((String(stdout).match(/^Enabled:\s*(.*)$/m) || [])[1] || '');
  return { url: url.trim() === '(null)' ? '' : url.trim(), enabled };
}

async function macGetAuto(service) {
  try {
    const { stdout } = await run('networksetup', ['-getautoproxyurl', service]);
    return parseAutoProxy(stdout);
  } catch (_) {
    return { url: '', enabled: false };
  }
}

async function macAdmin(lines) {
  const file = path.join(os.tmpdir(), `wt-proxy-${process.pid}-${Date.now()}.sh`);
  fs.writeFileSync(file, lines.join('\n') + '\n', { mode: 0o700 });
  try {
    const apple = String(file).replace(/\\/g, '\\\\').replace(/"/g, '\\"');
    await run('osascript', ['-e', `do shell script "/bin/sh ${apple}" with administrator privileges`], { timeout: 120000 });
  } finally {
    try {
      fs.unlinkSync(file);
    } catch (_) {
      /* ignore */
    }
  }
}

// ------------------------------------------------------------------ общий интерфейс

/** Включает автоматическую настройку прокси (PAC) в системе. Прежние значения запоминаются для восстановления. */
async function install(pacUrl, store, log) {
  if (process.platform === 'win32') {
    const prev = { AutoConfigURL: await winGet('AutoConfigURL') };
    store.set('sysproxyPrev', prev);
    store.set('sysproxyInstalled', true);
    await winSet('AutoConfigURL', pacUrl);
    await winRefresh();
    log('Системный прокси: включена автонастройка (PAC)');
  } else if (process.platform === 'darwin') {
    const services = await macServices();
    const prev = {};
    for (const s of services) prev[s] = await macGetAuto(s);
    store.set('sysproxyPrev', prev);
    store.set('sysproxyInstalled', true);
    const lines = [];
    for (const s of services) {
      lines.push(`networksetup -setautoproxyurl ${sq(s)} ${sq(pacUrl)}`);
      lines.push(`networksetup -setautoproxystate ${sq(s)} on`);
    }
    await macAdmin(lines);
    log('Системный прокси: включена автонастройка (PAC) для ' + services.length + ' сетевых служб');
  } else {
    log('Системный прокси: на этой ОС не настраивается (только для разработки)');
  }
}

/** Возвращает прежние настройки прокси. */
async function restore(store, log) {
  if (!store.get('sysproxyInstalled', false)) return;
  const prev = store.get('sysproxyPrev', {});
  if (process.platform === 'win32') {
    if (prev.AutoConfigURL) await winSet('AutoConfigURL', prev.AutoConfigURL);
    else await winDelete('AutoConfigURL');
    await winRefresh();
  } else if (process.platform === 'darwin') {
    const lines = [];
    for (const [s, v] of Object.entries(prev)) {
      if (v.url) lines.push(`networksetup -setautoproxyurl ${sq(s)} ${sq(v.url)}`);
      lines.push(`networksetup -setautoproxystate ${sq(s)} ${v.enabled ? 'on' : 'off'}`);
    }
    if (lines.length) await macAdmin(lines);
  }
  store.set('sysproxyInstalled', false);
  log('Системный прокси: настройки возвращены');
}

/** Снимает настройки, оставшиеся после аварийного закрытия прошлого сеанса. */
async function cleanupStale(store, log) {
  if (store.get('sysproxyInstalled', false)) {
    log('Найдены настройки прокси от прошлого сеанса, возвращаю прежние');
    try {
      await restore(store, log);
    } catch (e) {
      log('Не удалось вернуть настройки прокси: ' + e.message);
    }
  }
}

module.exports = { install, restore, cleanupStale, run, sq, parseRegValue, parseAutoProxy };
