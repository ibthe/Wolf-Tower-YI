const { spawn } = require('child_process');
const EventEmitter = require('events');
const fs = require('fs');
const net = require('net');
const path = require('path');
const { buildGroups, enabledIds, BASE } = require('./profiles');
const { PacServer } = require('./pac');
const sysproxy = require('./sysproxy');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function binaryPath(resourcesDir) {
  const name = process.platform === 'win32' ? 'ciadpi.exe' : 'ciadpi';
  return path.join(resourcesDir, 'bin', process.platform, name);
}

function freePort() {
  return new Promise((resolve, reject) => {
    const s = net.createServer();
    s.once('error', reject);
    s.listen(0, '127.0.0.1', () => {
      const p = s.address().port;
      s.close(() => resolve(p));
    });
  });
}

function canListen(port) {
  return new Promise((resolve) => {
    const s = net.createServer();
    s.once('error', () => resolve(false));
    s.listen(port, '127.0.0.1', () => s.close(() => resolve(true)));
  });
}

function canConnect(port) {
  return new Promise((resolve) => {
    const s = net.connect({ host: '127.0.0.1', port });
    const finish = (ok) => {
      s.destroy();
      resolve(ok);
    };
    s.setTimeout(150, () => finish(false));
    s.once('connect', () => finish(true));
    s.once('error', () => finish(false));
  });
}

/** Один процесс ByeDPI (SOCKS5 на localhost). */
class ByeDpi extends EventEmitter {
  constructor(bin) {
    super();
    this.bin = bin;
    this.proc = null;
    this.exited = true;
    this.exitCode = null;
  }

  /** true только если процесс жив и порт принимает соединения. */
  async start(port, args) {
    if (!fs.existsSync(this.bin)) {
      this.emit('log', `Не найден ${this.bin}`);
      return false;
    }
    this.emit('log', `Запуск ByeDPI на порту ${port}: ${args.join(' ')}`);
    let proc;
    try {
      proc = spawn(this.bin, ['-i', '127.0.0.1', '-p', String(port), ...args], { windowsHide: true });
    } catch (e) {
      this.emit('log', `Ошибка запуска: ${e.message}`);
      return false;
    }
    this.proc = proc;
    this.exited = false;
    this.exitCode = null;
    const onData = (d) => String(d).split(/\r?\n/).filter(Boolean).forEach((l) => this.emit('log', l));
    proc.stdout.on('data', onData);
    proc.stderr.on('data', onData);
    proc.on('error', (e) => {
      this.exited = true;
      this.emit('log', `Ошибка процесса: ${e.message}`);
    });
    proc.on('exit', (code) => {
      this.exited = true;
      this.exitCode = code;
      this.emit('exit', code);
    });

    const deadline = Date.now() + 2500;
    while (Date.now() < deadline) {
      if (this.exited) {
        this.emit('log', `ByeDPI завершился сразу после запуска (код ${this.exitCode}): порт занят или неверные параметры`);
        return false;
      }
      if (await canConnect(port)) return true;
      await sleep(80);
    }
    this.emit('log', `ByeDPI не открыл порт ${port} за 2,5 с`);
    await this.stop();
    return false;
  }

  /** Мягкая остановка, затем принудительная: иначе старый процесс может держать порт. */
  async stop() {
    const p = this.proc;
    if (!p) return;
    this.proc = null;
    if (this.exited) return;
    const exited = new Promise((resolve) => p.once('exit', resolve));
    try {
      p.kill();
    } catch (_) {
      /* ignore */
    }
    const ok = await Promise.race([exited.then(() => true), sleep(700).then(() => false)]);
    if (!ok) {
      try {
        p.kill('SIGKILL');
      } catch (_) {
        /* ignore */
      }
      await Promise.race([exited, sleep(1000)]);
    }
  }
}

/** Защита: ByeDPI + PAC-сервер + системная автонастройка прокси. */
class Engine extends EventEmitter {
  constructor({ resourcesDir, store, config }) {
    super();
    this.bin = binaryPath(resourcesDir);
    this.store = store;
    this.config = config;
    this.pac = new PacServer();
    this.bye = null;
    this.running = false;
    this.socksPort = 0;
  }

  log(msg) {
    this.emit('log', msg);
  }

  /** Включённые сервисы (выбор пользователя; по умолчанию все). */
  enabledIds() {
    return enabledIds(this.config, this.store);
  }

  strategyFor(id) {
    return this.store.get('strategy_' + id, this.config.defaultStrategy);
  }

  selection() {
    const ids = this.enabledIds();
    return this.config.profiles
      .filter((p) => ids.includes(p.id))
      .map((profile) => ({ profile, strategy: this.strategyFor(profile.id) }));
  }

  async start() {
    if (this.running) return true;
    const sel = this.selection();
    if (!sel.length) {
      this.log('Не выбран ни один сервис');
      return false;
    }
    this.log('Стратегии: ' + sel.map((s) => `${s.profile.name}=${s.strategy}`).join(', '));
    const args = buildGroups(sel);

    const preferred = this.store.get('socksPort', 18080);
    const port = (await canListen(preferred)) ? preferred : await freePort();
    if (port !== preferred) this.log(`Порт ${preferred} занят, используется ${port}`);

    const bye = new ByeDpi(this.bin);
    bye.on('log', (l) => this.log('byedpi: ' + l));
    if (!(await bye.start(port, [...BASE, ...args]))) return false;
    this.bye = bye;
    this.socksPort = port;

    try {
      await this.pac.start();
      this.pac.update(sel.flatMap((s) => s.profile.domains), port);
      if (!this.store.get('sysproxyInstalled', false)) {
        await sysproxy.install(this.pac.url, this.store, (m) => this.log(m));
      }
    } catch (e) {
      this.log('Не удалось настроить системный прокси: ' + e.message);
      this.pac.update([], 0);
      await bye.stop();
      this.bye = null;
      return false;
    }

    bye.on('exit', () => {
      if (this.bye === bye && this.running) {
        this.log('ByeDPI остановился неожиданно, защита выключена');
        this.running = false;
        this.pac.update([], 0);
        this.bye = null;
        this.emit('changed');
      }
    });
    this.running = true;
    this.log(`Защита включена. SOCKS5 127.0.0.1:${port}`);
    this.emit('changed');
    return true;
  }

  /** Выключает защиту. Системная настройка PAC остаётся, но отдаёт "напрямую" для всех адресов. */
  async stop() {
    const wasRunning = this.running || this.bye;
    this.running = false;
    this.pac.update([], 0);
    if (this.bye) {
      const b = this.bye;
      this.bye = null;
      await b.stop();
    }
    if (wasRunning) this.log('Защита выключена');
    this.emit('changed');
  }

  /** Полное завершение: остановка, возврат системных настроек, закрытие PAC-сервера. */
  async shutdown() {
    await this.stop();
    try {
      await sysproxy.restore(this.store, (m) => this.log(m));
    } catch (e) {
      this.log('Не удалось вернуть системные настройки прокси: ' + e.message);
    }
    await this.pac.stop();
  }
}

module.exports = { Engine, ByeDpi, binaryPath, freePort, canListen };
