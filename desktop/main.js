const { app, BrowserWindow, Tray, Menu, nativeImage, ipcMain, clipboard, shell } = require('electron');
const fs = require('fs');
const path = require('path');
const { Store } = require('./src/store');
const { loadConfig, forName, parseRaw, supported, enabledIds: enabledOf, BASE } = require('./src/profiles');
const { Engine, ByeDpi, freePort } = require('./src/engine');
const { License, readMachineId, loadPublicKey } = require('./src/license');
const autoprobe = require('./src/autoprobe');
const { buildReport } = require('./src/diag');

if (process.env.WT_USER_DATA) app.setPath('userData', process.env.WT_USER_DATA);

const smokeShot = process.env.WT_SMOKE_SHOT || '';
if (!smokeShot && !app.requestSingleInstanceLock()) app.quit();

const resourcesDir = app.isPackaged ? path.join(process.resourcesPath, 'resources') : path.join(__dirname, 'resources');

let win = null;
let tray = null;
let store;
let config;
let engine;
let license;
let quitting = false;
let busy = false;
let probeCancel = false;
let probe = { running: false, done: false, progress: '', report: '', scores: {} };
let clockTimer = null;
const logs = [];

const enabledIds = () => enabledOf(config, store);
const strategyFor = (id) => store.get('strategy_' + id, config.defaultStrategy);

function appendLog(msg) {
  const t = new Date().toTimeString().slice(0, 8);
  const line = `${t} ${msg}`;
  logs.push(line);
  if (logs.length > 300) logs.shift();
  if (win && !win.isDestroyed()) win.webContents.send('log', line);
}

function snapshot() {
  const st = license.status();
  const chosen = {};
  for (const p of config.profiles) chosen[p.id] = strategyFor(p.id);
  return {
    platform: process.platform,
    running: engine.running,
    busy,
    profiles: config.profiles.map((p) => ({ id: p.id, name: p.name })),
    strategies: config.strategies.filter((s) => supported(s)),
    enabled: enabledIds(),
    chosen,
    license: {
      text: license.describe(st),
      licensed: st.kind === 'licensed',
      deviceId: license.deviceId,
      plan: st.kind === 'licensed' ? st.plan : null,
      until: st.kind === 'licensed' ? st.until : null,
      untilMs: st.kind === 'licensed' ? st.untilMs : null,
    },
    probe,
    port: engine.socksPort || store.get('socksPort', 18080),
  };
}

function pushState() {
  if (win && !win.isDestroyed()) win.webContents.send('state', snapshot());
  updateTray();
}

function updateTray() {
  if (!tray) return;
  const menu = Menu.buildFromTemplate([
    { label: 'Открыть Wolf Tower', click: showWindow },
    { label: engine.running ? 'Выключить защиту' : 'Включить защиту', click: () => toggleProtection() },
    { type: 'separator' },
    { label: 'Выход', click: () => app.quit() },
  ]);
  tray.setContextMenu(menu);
  tray.setToolTip(engine.running ? 'Wolf Tower: защита включена' : 'Wolf Tower: защита выключена');
}

function showWindow() {
  if (!win || win.isDestroyed()) createWindow();
  win.show();
  win.focus();
}

async function toggleProtection() {
  if (busy) return;
  busy = true;
  pushState();
  try {
    if (engine.running) {
      await engine.stop();
    } else {
      await license.networkCheck();
      if (!license.canRun()) {
        appendLog('Нужен лицензионный ключ');
      } else {
        await engine.start();
      }
    }
  } finally {
    busy = false;
    pushState();
  }
}

async function runProbe() {
  const ids = enabledIds();
  const profiles = config.profiles.filter((p) => ids.includes(p.id));
  if (probe.running || engine.running || !profiles.length || !license.canRun()) return;
  probeCancel = false;
  probe = { running: true, done: false, progress: 'Подготовка…', report: '', scores: {} };
  pushState();
  try {
    const candidates = ['none', ...config.strategies.filter((s) => supported(s))];
    const counts = await autoprobe.score({
      makeProcess: () => new ByeDpi(engine.bin),
      freePort,
      forName: (s) => [...BASE, ...forName(s)],
      profiles,
      candidates,
      isCancelled: () => probeCancel,
      onProgress: (n) => {
        probe = { ...probe, progress: `Проверено вариантов: ${n} из ${candidates.length}` };
        pushState();
      },
    });
    if (probeCancel) {
      probe = { running: false, done: true, progress: '', report: 'Подбор отменён, настройки не менялись', scores: {} };
    } else {
      const lines = [];
      const scores = {};
      for (const p of profiles) {
        const total = p.probeHosts.length;
        if (!total) {
          lines.push(`${p.name}: нет адресов для проверки`);
          continue;
        }
        // Проба смотрит только TLS: для match_all-сервиса "без обхода" не выбираем (чат идёт без SNI)
        const { best, ok } = autoprobe.chooseBest(counts[p.id], p.matchAll ? candidates.filter((c) => c !== 'none') : candidates);
        if (!best) {
          lines.push(`${p.name}: ничего не сработало (0/${total}). Возможна блокировка по IP или подмена DNS.`);
        } else {
          store.set('strategy_' + p.id, best);
          scores[p.id] = `${ok}/${total}`;
          lines.push(`${p.name}: ${best === 'none' ? 'обход не нужен' : best} (${ok}/${total})`);
        }
      }
      probe = { running: false, done: true, progress: '', report: lines.join('\n'), scores };
    }
  } catch (e) {
    probe = { running: false, done: true, progress: '', report: 'Ошибка подбора: ' + e.message, scores: {} };
  }
  pushState();
}

function createWindow() {
  win = new BrowserWindow({
    width: 560,
    height: smokeShot ? 1700 : 820,
    minWidth: 440,
    minHeight: 560,
    title: 'Wolf Tower',
    icon: path.join(resourcesDir, 'icon.png'),
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
    },
  });
  if (process.platform !== 'darwin') win.setMenuBarVisibility(false);
  win.loadFile(path.join(__dirname, 'renderer', 'index.html'));
  win.on('close', (e) => {
    if (!quitting) {
      e.preventDefault();
      win.hide();
    }
  });
  if (smokeShot) {
    win.webContents.once('did-finish-load', () => {
      setTimeout(async () => {
        const img = await win.webContents.capturePage();
        fs.writeFileSync(smokeShot, img.toPNG());
        quitting = true;
        app.quit();
      }, 1500);
    });
  }
}

app.whenReady().then(async () => {
  store = new Store(app.getPath('userData'));
  config = loadConfig([path.join(resourcesDir, 'profiles.json'), path.join(__dirname, '..', 'profiles', 'profiles.json')]);
  engine = new Engine({ resourcesDir, store, config });
  license = new License({
    store,
    publicKeyB64: loadPublicKey(path.join(resourcesDir, 'license_public_key.txt')),
    machineId: await readMachineId(),
  });
  engine.on('log', appendLog);
  engine.on('changed', pushState);

  if (process.platform !== 'darwin') Menu.setApplicationMenu(null);

  // Если прошлый запуск завершился аварийно, возвращаем системные настройки прокси
  try {
    await require('./src/sysproxy').restore(store, appendLog);
  } catch (e) {
    appendLog('Не удалось вернуть прежние настройки прокси: ' + e.message);
  }

  createWindow();
  try {
    const icon = nativeImage.createFromPath(path.join(resourcesDir, 'icon.png')).resize({ width: 18, height: 18 });
    tray = new Tray(icon);
    tray.on('click', showWindow);
    updateTray();
  } catch (_) {
    tray = null;
  }

  // Сверка часов с сетью при запуске и раз в 30 минут, пока защита работает
  license.networkCheck().then(pushState);
  clockTimer = setInterval(async () => {
    if (!engine.running) return;
    if ((await license.networkCheck()) === 'BEHIND') {
      appendLog('Время отстаёт от сетевого, ключ деактивирован');
      await engine.stop();
      pushState();
    }
  }, 30 * 60 * 1000);

  app.on('activate', showWindow);
});

app.on('second-instance', showWindow);

app.on('before-quit', async (e) => {
  if (quitting) return;
  e.preventDefault();
  quitting = true;
  if (clockTimer) clearInterval(clockTimer);
  try {
    await engine.shutdown();
  } catch (_) {
    /* выходим в любом случае */
  }
  app.quit();
});

app.on('window-all-closed', () => {
  /* приложение остаётся в трее */
});

// ------------------------------------------------------------------ IPC

ipcMain.handle('state:get', () => snapshot());
ipcMain.handle('log:get', () => logs.slice());
ipcMain.handle('log:clear', () => {
  logs.length = 0;
});
ipcMain.handle('profile:strategy', (_e, id, s) => {
  if (engine.running) return false;
  if (!config.profiles.some((p) => p.id === id) || typeof s !== 'string') return false;
  // своя строка в стиле ByeByeDPI: "raw:-d1 -s1+s -a1" (только флаги обхода, см. parseRaw)
  const ok = (s === 'none' || config.strategies.includes(s) || (s.startsWith('raw:') && parseRaw(s.slice(4)) !== null)) && supported(s);
  if (ok) store.set('strategy_' + id, s);
  pushState();
  return ok;
});
ipcMain.handle('profile:enabled', (_e, id, on) => {
  if (engine.running || !config.profiles.some((p) => p.id === id)) return false;
  const cur = enabledIds().filter((x) => x !== id);
  store.set('enabled', on ? [...cur, id] : cur);
  pushState();
  return true;
});
ipcMain.handle('protection:toggle', () => toggleProtection());
ipcMain.handle('license:activate', async (_e, key) => {
  const err = await license.activate(key);
  pushState();
  return err;
});
ipcMain.handle('probe:start', () => runProbe());
ipcMain.handle('probe:cancel', () => {
  probeCancel = true;
});
ipcMain.handle('copy', (_e, text) => clipboard.writeText(String(text)));
ipcMain.handle('paste', () => clipboard.readText());
ipcMain.handle('licenses:get', () => {
  try {
    return fs.readFileSync(path.join(resourcesDir, 'third_party_licenses.txt'), 'utf8');
  } catch (_) {
    return 'Не удалось прочитать файл лицензий';
  }
});
ipcMain.handle('diag:copy', () => {
  const text = buildReport({
    version: app.getVersion(),
    license: license.describe(),
    deviceId: license.deviceId,
    config,
    enabled: enabledIds(),
    strategyFor,
    probeReport: probe.report,
    port: engine.socksPort,
    running: engine.running,
    logs,
  });
  clipboard.writeText(text);
  return text.length;
});
