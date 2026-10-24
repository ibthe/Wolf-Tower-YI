const net = require('net');
const tls = require('tls');

/** Страница должна дочитаться до конца или дойти хотя бы до этого размера (ловит обрыв после ~16 КБ). */
const MIN_BYTES = 40000;

class Reader {
  constructor(sock) {
    this.sock = sock;
    this.buf = Buffer.alloc(0);
    this.waiters = [];
    this.err = null;
    this.ended = false;
    this.onData = (d) => {
      this.buf = Buffer.concat([this.buf, d]);
      this.pump();
    };
    this.onError = (e) => {
      this.err = e;
      this.pump();
    };
    this.onClose = () => {
      this.ended = true;
      this.pump();
    };
    sock.on('data', this.onData);
    sock.on('error', this.onError);
    sock.on('close', this.onClose);
  }

  pump() {
    while (this.waiters.length) {
      const w = this.waiters[0];
      if (this.buf.length >= w.n) {
        this.waiters.shift();
        const out = this.buf.subarray(0, w.n);
        this.buf = this.buf.subarray(w.n);
        w.resolve(out);
      } else if (this.err || this.ended) {
        this.waiters.shift();
        w.reject(this.err || new Error('соединение закрыто'));
      } else {
        break;
      }
    }
  }

  read(n) {
    return new Promise((resolve, reject) => {
      this.waiters.push({ n, resolve, reject });
      this.pump();
    });
  }

  detach() {
    this.sock.removeListener('data', this.onData);
    this.sock.removeListener('close', this.onClose);
  }
}

/** Подключение к host:port через локальный SOCKS5 (имя домена передаётся прокси). */
async function socks5Connect(proxyPort, host, destPort, timeoutMs) {
  const sock = net.connect({ host: '127.0.0.1', port: proxyPort });
  sock.setNoDelay(true);
  const timer = setTimeout(() => sock.destroy(new Error('таймаут')), timeoutMs);
  const r = new Reader(sock);
  try {
    await new Promise((resolve, reject) => {
      sock.once('connect', resolve);
      sock.once('error', reject);
    });
    sock.write(Buffer.from([5, 1, 0]));
    const g = await r.read(2);
    if (g[1] !== 0) throw new Error('SOCKS: авторизация не принята');
    const hb = Buffer.from(host, 'ascii');
    sock.write(Buffer.concat([Buffer.from([5, 1, 0, 3, hb.length]), hb, Buffer.from([destPort >> 8, destPort & 255])]));
    const h = await r.read(4);
    if (h[1] !== 0) throw new Error('SOCKS: отказ ' + h[1]);
    const skip = h[3] === 1 ? 4 : h[3] === 4 ? 16 : (await r.read(1))[0];
    await r.read(skip + 2);
    r.detach();
    clearTimeout(timer);
    return sock;
  } catch (e) {
    clearTimeout(timer);
    sock.destroy();
    throw e;
  }
}

function withTimeout(promise, ms) {
  let t;
  return Promise.race([
    promise,
    new Promise((_, reject) => {
      t = setTimeout(() => reject(new Error('таймаут')), ms);
    }),
  ]).finally(() => clearTimeout(t));
}

function readResponse(sock, timeoutMs, minBytes = MIN_BYTES, budgetMs = 0) {
  return new Promise((resolve) => {
    let total = 0;
    let head = '';
    let finished = false;
    let budgetTimer = null;
    const done = (value) => {
      if (finished) return;
      finished = true;
      clearTimeout(budgetTimer);
      sock.removeAllListeners();
      sock.on('error', () => {});
      resolve(value);
    };
    // Лимит по времени: поток, который идёт, но слишком медленно (замедление), считается неуспехом
    if (budgetMs > 0) budgetTimer = setTimeout(() => done(false), budgetMs);
    sock.setTimeout(timeoutMs);
    sock.on('timeout', () => done(false));
    sock.on('error', () => done(false));
    sock.on('data', (d) => {
      if (head.length < 16) head += d.toString('latin1', 0, Math.min(d.length, 16 - head.length));
      total += d.length;
      if (total >= minBytes) done(head.startsWith('HTTP/'));
    });
    sock.on('end', () => done(head.startsWith('HTTP/')));
    sock.on('close', () => done(head.startsWith('HTTP/') && total > 0));
  });
}

/**
 * Успех = рукопожатие TLS прошло (с проверкой сертификата), сервер ответил HTTP-строкой и ответ
 * дочитан до конца или дошёл минимум до MIN_BYTES. tlsOptions: destPort и tls нужны только тестам; minBytes и budgetMs
 * задают порог по размеру и времени (для сервисов с замедлением, YouTube).
 */
async function probeHost(host, proxyPort, timeoutMs = 5000, tlsOptions = {}) {
  let raw = null;
  let secure = null;
  try {
    raw = await socks5Connect(proxyPort, host, tlsOptions.destPort || 443, timeoutMs);
    secure = tls.connect({ socket: raw, servername: host, ...tlsOptions.tls });
    await withTimeout(
      new Promise((resolve, reject) => {
        secure.once('secureConnect', resolve);
        secure.once('error', reject);
      }),
      timeoutMs
    );
    secure.write(
      `GET / HTTP/1.1\r\nHost: ${host}\r\nUser-Agent: Mozilla/5.0\r\nAccept: */*\r\n` +
        'Accept-Encoding: identity\r\nConnection: close\r\n\r\n'
    );
    return await readResponse(secure, timeoutMs, tlsOptions.minBytes || MIN_BYTES, tlsOptions.budgetMs || 0);
  } catch (_) {
    return false;
  } finally {
    try {
      if (secure) secure.destroy();
    } catch (_) {
      /* ignore */
    }
    try {
      if (raw) raw.destroy();
    } catch (_) {
      /* ignore */
    }
  }
}

module.exports = { probeHost, socks5Connect, MIN_BYTES };
