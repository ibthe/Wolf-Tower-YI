// Проверки логики без Electron: node test/run.js
const assert = require('assert');
const fs = require('fs');
const http = require('http');
const https = require('https');
const net = require('net');
const os = require('os');
const path = require('path');
const vm = require('vm');
const { execFileSync } = require('child_process');

const { buildGroups, loadConfig, forName, parseRaw, enabledIds, supported, BASE } = require('../src/profiles');
const { buildPac, PacServer } = require('../src/pac');
const { Store } = require('../src/store');
const { License } = require('../src/license');
const { probeHost } = require('../src/probe');
const { chooseBest } = require('../src/autoprobe');
const { Engine, ByeDpi, freePort } = require('../src/engine');

let passed = 0;
const results = [];
async function test(name, fn) {
  try {
    await fn();
    passed++;
    results.push('OK   ' + name);
  } catch (e) {
    results.push('FAIL ' + name + ' :: ' + (e && e.message));
    process.exitCode = 1;
  }
}

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'wt-test-'));
const root = path.join(__dirname, '..', '..');
const config = loadConfig([path.join(root, 'profiles', 'profiles.json')]);

(async () => {
  await test('профили и стратегии загружаются, по умолчанию split+disorder', () => {
    assert.deepStrictEqual(config.profiles.map((p) => p.id), ['youtube', 'instagram']);
    assert.strictEqual(config.defaultStrategy, 'split+disorder');
    assert.deepStrictEqual(forName('split+disorder'), ['-s1+s', '-d3+s']);
    assert.deepStrictEqual(forName('fake+oob'), ['-f-1', '-t', '8', '-o1+s']);
    assert.deepStrictEqual(forName('none'), []);
    assert.deepStrictEqual(BASE, ['-X']);
  });

  await test('профили: оба сервиса по SNI, QUIC блокируется, у YouTube порог скорости пробы', () => {
    const [yt, ig] = config.profiles;
    for (const p of [yt, ig]) {
      assert.strictEqual(p.matchAll, false);
      assert.strictEqual(p.blockQuic, true);
      assert(p.domains.length && p.probeHosts.length >= 3);
    }
    assert(yt.domains.includes('googlevideo.com') && ig.domains.includes('cdninstagram.com'));
    assert.strictEqual(yt.probeMinBytes, 100000);
    assert.strictEqual(yt.probeBudgetMs, 4000);
    assert.strictEqual(ig.probeMinBytes, 40000);
    assert.strictEqual(ig.probeBudgetMs, 0);
  });

  await test('macOS: стратегии с fake/ttl/sack недоступны, остальные работают', () => {
    const bad = config.strategies.filter((n) => !supported(n, 'darwin'));
    assert(bad.includes('fake+disorder') && bad.includes('oob-sack') && bad.includes('chain-fake'));
    assert(supported('split+disorder', 'darwin') && supported('chain', 'darwin') && supported('disorder', 'darwin'));
    assert(supported('none', 'darwin'));
    assert.strictEqual(supported('raw:-f-1 -t 8 -d1', 'darwin'), false);
    assert.strictEqual(supported('raw:-d1 -s1+s -a1', 'darwin'), true);
    assert(config.strategies.every((n) => supported(n, 'linux') && supported(n, 'win32')));
    assert(config.strategies.filter((n) => supported(n, 'darwin')).length >= 15, 'на macOS остаётся достаточно вариантов');
  });

  await test('подбор: причина неудачного запуска ByeDPI попадает в сообщение об ошибке', async () => {
    const { score } = require('../src/autoprobe');
    await assert.rejects(
      score({ makeProcess: () => ({ start: async () => false, lastError: 'не найден файл ciadpi', stop: async () => {} }),
        freePort: async () => 1, forName: () => [], profiles: config.profiles, candidates: ['none'], isCancelled: () => false, onProgress() {} }),
      /не удалось запустить ByeDPI: не найден файл ciadpi/);
  });

  await test('включённые сервисы: по умолчанию оба, затем выбор пользователя', () => {
    const st = new Store(path.join(tmp, 'en'));
    assert.deepStrictEqual(enabledIds(config, st), ['youtube', 'instagram']);
    st.set('enabled', ['instagram', 'whatsapp']);
    assert.deepStrictEqual(enabledIds(config, st), ['instagram']);
    st.set('enabled', []);
    assert.deepStrictEqual(enabledIds(config, st), []);
  });

  await test('переводы: у каждого ключа есть английский и чеченский, подстановки совпадают', () => {
    const dict = JSON.parse(fs.readFileSync(path.join(root, 'i18n', 'translations.json'), 'utf8'));
    const ph = (t) => (t.match(/\{\d+\}/g) || []).sort().join();
    for (const [k, v] of Object.entries(dict)) {
      assert(v.en && v.ce, 'нет перевода: ' + k);
      assert.strictEqual(ph(v.en), ph(k), 'подстановки en: ' + k);
      assert.strictEqual(ph(v.ce), ph(k), 'подстановки ce: ' + k);
    }
    assert(Object.keys(dict).length > 100);
  });

  await test('переводы: все строки tr()/trf() из Android, компьютера и HTML есть в словаре', () => {
    const dict = JSON.parse(fs.readFileSync(path.join(root, 'i18n', 'translations.json'), 'utf8'));
    const files = [];
    const walk = (d) => {
      for (const f of fs.readdirSync(d, { withFileTypes: true })) {
        const full = path.join(d, f.name);
        if (f.isDirectory()) { if (!['node_modules', 'dist', 'test', 'build'].includes(f.name)) walk(full); }
        else if (/\.(kt|js|html)$/.test(f.name)) files.push(full);
      }
    };
    walk(path.join(root, 'android', 'app', 'src', 'main', 'java'));
    walk(path.join(root, 'desktop', 'renderer'));
    walk(path.join(root, 'desktop', 'src'));
    files.push(path.join(root, 'desktop', 'main.js'));
    const unesc = (t) => t.replace(/\\n/g, '\n').replace(/\\"/g, '"').replace(/\\'/g, "'");
    const cyr = /[А-Яа-яЁё]/;
    const missing = new Set();
    let used = 0;
    for (const f of files) {
      const src = fs.readFileSync(f, 'utf8');
      const keys = [];
      for (const m of src.matchAll(/\btrf?\(\s*(["'`])((?:\\.|(?!\1)[^\\])*)\1/g)) keys.push(unesc(m[2]));
      for (const m of src.matchAll(/data-i18n(?:-ph|-aria)?="([^"]+)"/g)) keys.push(m[1]);
      if (f.endsWith('.html')) for (const m of src.matchAll(/<[^>]*\bdata-i18n(?![-\w])[^>]*>([^<]+)</g)) keys.push(m[1].trim().replace(/\s+/g, ' '));
      for (const m of src.matchAll(/plural\([^,]+,\s*'([^']+)',\s*'([^']+)',\s*'([^']+)'/g)) keys.push(m[1], m[2], m[3]);
      for (const m of src.matchAll(/I18n\.plural\([^,]+,\s*"([^"]+)",\s*"([^"]+)",\s*"([^"]+)"/g)) keys.push(m[1], m[2], m[3]);
      for (const k of keys) { if (cyr.test(k)) { used++; if (!dict[k]) missing.add(path.basename(f) + ': ' + k); } }
    }
    for (const k of ['{0} идёт через обход. Сеть: {1}', '{0} идут через обход. Сеть: {1}', '{0} идёт через обход', '{0} идут через обход']) assert(dict[k], k);
    assert(used > 80, 'нашлось слишком мало строк: ' + used);
    assert.deepStrictEqual([...missing], [], 'нет в словаре:\n' + [...missing].join('\n'));
  });

  await test('переводы: tr/trf/plural на трёх языках', () => {
    const i18n = require('../src/i18n');
    assert(i18n.load([path.join(root, 'i18n', 'translations.json')]));
    i18n.setLang('ru');
    assert.strictEqual(i18n.tr('Сервисы'), 'Сервисы');
    assert.strictEqual(i18n.trf('Осталось {0}', '5 дней'), 'Осталось 5 дней');
    assert.strictEqual(i18n.plural(5, 'день', 'дня', 'дней'), 'дней');
    i18n.setLang('en');
    assert.strictEqual(i18n.tr('Сервисы'), 'Services');
    assert.strictEqual(i18n.trf('Осталось {0}', 3), '3 left');
    assert.strictEqual(i18n.plural(1, 'день', 'дня', 'дней'), 'day');
    assert.strictEqual(i18n.plural(5, 'день', 'дня', 'дней'), 'days');
    assert.strictEqual(i18n.tr('нет такого ключа'), 'нет такого ключа');
    i18n.setLang('ce');
    assert.strictEqual(i18n.tr('Сервисы'), 'Сервисаш');
    assert.strictEqual(i18n.plural(5, 'день', 'дня', 'дней'), 'де');
    assert.strictEqual(Object.keys(i18n.table()).length > 100, true);
    i18n.setLang('xx');
    assert.strictEqual(i18n.getLang(), 'ce', 'неизвестный язык игнорируется');
    assert.deepStrictEqual([i18n.fromLocale('ru-RU'), i18n.fromLocale('ce'), i18n.fromLocale('de-DE'), i18n.fromLocale('')], ['ru', 'ce', 'en', 'en']);
    i18n.setLang('ru');
  });

  await test('лицензия: сообщения переводятся вслед за языком', () => {
    const i18n = require('../src/i18n');
    i18n.setLang('en');
    const { License } = require('../src/license');
    const lic = new License({ store: new Store(path.join(tmp, 'li18n')), publicKeyB64: '', machineId: 'x' });
    assert.strictEqual(lic.verifyKey('bad').reason, 'Invalid key format');
    assert.strictEqual(lic.describe({ kind: 'not_activated' }), 'The app is not activated. Enter a license or trial key.');
    i18n.setLang('ru');
    assert.strictEqual(lic.verifyKey('bad').reason, 'Неверный формат ключа');
  });

  await test('группы ByeDPI: hosts на сервис, между группами --auto=none', () => {
    const [yt, ig] = config.profiles;
    const out = buildGroups([
      { profile: yt, strategy: 'split+disorder' },
      { profile: ig, strategy: 'disorder' },
    ]);
    assert.strictEqual(out[0], '--hosts');
    assert(out[1].startsWith(':youtube.com') && out[1].includes('googlevideo.com'));
    assert.deepStrictEqual(out.slice(2, 5), ['-s1+s', '-d3+s', '--auto=none']);
    assert.strictEqual(out[5], '--hosts');
    assert(out[6].startsWith(':instagram.com'));
    assert.strictEqual(out[7], '-d1+s');
    // none не попадает в аргументы
    const one = buildGroups([{ profile: yt, strategy: 'none' }, { profile: ig, strategy: 'split' }]);
    assert(!one.join(' ').includes('youtube.com'));
  });

  await test('строка в стиле ByeByeDPI: разрешены флаги обхода, опасные отклоняются', () => {
    const s = '-d1 -s1+s -s3+s -s6+s -s9+s -s12+s -s15+s -s20+s -s30+s -a1';
    assert.deepStrictEqual(forName('raw:' + s), s.split(' '));
    assert.deepStrictEqual(forName('chain+udp'), s.split(' '));
    assert.deepStrictEqual(parseRaw('-f -1 -t 8 -d1+s'), ['-f', '-1', '-t', '8', '-d1+s']);
    for (const bad of ['-i 0.0.0.0', '-X', '-p 80', '--hosts :a.com', '-H :a.com', '-A t', '-U', '-D', '-x 2', '', '-d1 -c 9']) {
      assert.strictEqual(parseRaw(bad), null, bad);
    }
    assert.deepStrictEqual(forName('raw:-D'), []);
  });

  await test('все стратегии из profiles.json известны и не содержат управляющих флагов', () => {
    for (const name of config.strategies) {
      const args = forName(name);
      assert(args.length > 0, name);
      assert.notStrictEqual(parseRaw(args.join(' ')), null, name);
    }
  });

  await test('PAC: только домены выбранных сервисов идут через прокси', () => {
    const pac = buildPac(['youtube.com', 'bad domain;"'], 18080);
    assert(!pac.includes('bad domain'), 'мусорные домены отбрасываются');
    const ctx = { dnsDomainIs: (h, d) => h.endsWith(d) };
    vm.runInNewContext(pac, ctx);
    assert(ctx.FindProxyForURL('http://www.youtube.com/', 'www.youtube.com').startsWith('SOCKS5 127.0.0.1:18080'));
    assert(ctx.FindProxyForURL('http://youtube.com/', 'YouTube.com').startsWith('SOCKS5'));
    assert.strictEqual(ctx.FindProxyForURL('http://example.com/', 'example.com'), 'DIRECT');
    assert.strictEqual(ctx.FindProxyForURL('http://notyoutube.com/', 'notyoutube.com'), 'DIRECT');
    const empty = {};
    empty.dnsDomainIs = ctx.dnsDomainIs;
    vm.runInNewContext(buildPac([], 0), empty);
    assert.strictEqual(empty.FindProxyForURL('http://youtube.com/', 'youtube.com'), 'DIRECT');
  });

  await test('PAC-сервер отдаёт актуальный файл', async () => {
    const s = new PacServer();
    await s.start();
    s.update(['youtube.com'], 1234);
    const body = await new Promise((resolve) =>
      http.get(s.url, (r) => {
        let d = '';
        r.on('data', (c) => (d += c));
        r.on('end', () => resolve({ d, type: r.headers['content-type'] }));
      })
    );
    assert(body.d.includes('127.0.0.1:1234'));
    assert.strictEqual(body.type, 'application/x-ns-proxy-autoconfig');
    await s.stop();
  });

  // ---------------- лицензии: ключи выпускает настоящий keygen (Python), проверяет Node
  const lic = {};
  try {
    const keysDir = path.join(tmp, 'keys');
    fs.mkdirSync(keysDir);
    const env = { ...process.env, WT_KEYS_DIR: keysDir, WT_PRIVATE_KEY: path.join(keysDir, 'private_key.pem') };
    const kg = path.join(root, 'tools', 'license', 'keygen.py');
    const py = (...a) => execFileSync('python3', [kg, ...a], { env, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim();
    lic.pub = py('init').split('\n').pop().trim();
    lic.any = py('issue', '--days', '30', '--any-device');
    lic.perp = py('issue', '--perpetual', '--any-device');
    lic.dev = py('issue', '--trial', '--device', 'ABCD-EF01-2345');
    lic.other = py('issue', '--days', '30', '--device', 'ABCD-EF01-2345');
  } catch (e) {
    lic.error = e.message;
  }
  const mk = (extra = {}) =>
    new License({ store: new Store(fs.mkdtempSync(path.join(tmp, 's-'))), publicKeyB64: lic.pub, machineId: 'test-machine', skewFn: async () => 0, ...extra });

  await test('лицензия: ключ из Python проверяется в Node', () => {
    assert(!lic.error, lic.error);
    const l = mk();
    assert(l.verifyKey(lic.any).ok);
    assert(l.verifyKey(lic.perp).ok);
    assert.strictEqual(l.verifyKey(lic.perp).untilMs, null);
  });

  await test('лицензия: подделка, чужой формат, чужое устройство', () => {
    const l = mk();
    const parts = lic.any.split('.');
    const forged = `${parts[0]}.${parts[1].slice(0, -2)}AA.${parts[2]}`;
    assert(!l.verifyKey(forged).ok);
    assert(!l.verifyKey('мусор').ok);
    assert(!l.verifyKey(lic.dev).ok, 'ключ для другого устройства не должен подойти');
    assert(/другого устройства/.test(l.verifyKey(lic.dev).reason));
  });

  await test('лицензия: привязка к устройству совпадает с форматом Android', () => {
    const l = mk();
    assert(/^[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}$/.test(l.deviceId));
  });

  await test('лицензия: активация, статус, срок, откат часов', async () => {
    const t0 = Date.now();
    let now = t0;
    const l = mk({ now: () => now });
    assert.strictEqual(l.status().kind, 'not_activated');
    assert.strictEqual(await l.activate('мусор'), 'Неверный формат ключа');
    assert(/проверку подписи/.test(await l.activate('WT1.xxx.yyy')));
    assert.strictEqual(await l.activate(lic.any), null);
    assert.strictEqual(l.status().kind, 'licensed');
    now = t0 + 40 * 86400000; // ключ на 30 дней уже истёк
    assert.strictEqual(l.status().kind, 'expired');
    now = t0 - 3 * 86400000; // откат на 3 дня назад
    assert.strictEqual(l.status().kind, 'tampered');
    // бессрочный ключ работает независимо от отката
    const l2 = mk({ now: () => now });
    assert.strictEqual(await l2.activate(lic.perp), null);
    assert.strictEqual(l2.status().kind, 'licensed');
  });

  await test('лицензия: сверка с сетью деактивирует ключ при отставании часов', async () => {
    const store = new Store(fs.mkdtempSync(path.join(tmp, 's-')));
    let skew = 0;
    const l = new License({ store, publicKeyB64: lic.pub, machineId: 'm', skewFn: async () => skew });
    assert.strictEqual(await l.activate(lic.any), null);
    skew = -2 * 3600 * 1000; // компьютер отстаёт на 2 часа
    assert.strictEqual(await l.networkCheck(), 'BEHIND');
    assert.strictEqual(l.status().kind, 'tampered');
    assert(/отстаёт/.test(await l.activate(lic.any)));
    skew = null; // сети нет: ключ ввести нельзя, пока флаг нарушения не снят
    assert(/Нужен интернет/.test(await l.activate(lic.any)));
    skew = 5000; // часы в норме, флаг снимается
    assert.strictEqual(await l.networkCheck(), 'OK');
    assert.strictEqual(await l.activate(lic.any), null);
    assert.strictEqual(l.status().kind, 'licensed');
    skew = 3 * 3600 * 1000; // часы спешат: ключ не трогаем
    assert.strictEqual(await l.networkCheck(), 'OK');
    assert.strictEqual(l.status().kind, 'licensed');
  });

  await test('автоподбор: максимум успехов, при равенстве более ранний', () => {
    const cands = ['none', 'disorder', 'oob'];
    assert.deepStrictEqual(chooseBest({ none: 2, disorder: 2, oob: 1 }, cands), { best: 'none', ok: 2 });
    assert.deepStrictEqual(chooseBest({ disorder: 1, oob: 3 }, cands), { best: 'oob', ok: 3 });
    assert.deepStrictEqual(chooseBest({}, cands), { best: null, ok: 0 });
  });

  // ---------------- проба через SOCKS5 + TLS (локальные серверы, самоподписанный сертификат)
  let tls = null;
  try {
    const d = path.join(tmp, 'tls');
    fs.mkdirSync(d);
    execFileSync('openssl', ['req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-keyout', path.join(d, 'k.pem'), '-out', path.join(d, 'c.pem'),
      '-days', '2', '-subj', '/CN=test.local', '-addext', 'subjectAltName=DNS:test.local'], { stdio: 'ignore' });
    tls = { key: fs.readFileSync(path.join(d, 'k.pem')), cert: fs.readFileSync(path.join(d, 'c.pem')) };
  } catch (e) {
    results.push('SKIP проба SOCKS5+TLS (нет openssl): ' + e.message);
  }
  if (tls) {
    const servers = [];
    const listen = (srv) => new Promise((r) => srv.listen(0, '127.0.0.1', () => r(srv.address().port)));
    const big = https.createServer(tls, (q, s) => s.end('x'.repeat(60000)));
    const small = https.createServer(tls, (q, s) => s.end('ok'));
    const freeze = https.createServer(tls, (q, s) => {
      s.writeHead(200, { 'Content-Length': 100000 });
      s.write('x'.repeat(16000)); // и дальше молчит, как при блокировке после первых килобайт
    });
    const reset = https.createServer(tls, (q, s) => q.socket.destroy());
    // замедление: поток идёт, но ~10 КБ/с (как при троттлинге YouTube): рукопожатие и первые байты проходят
    const slow = https.createServer(tls, (q, s) => {
      s.writeHead(200, { 'Content-Length': 400000 });
      const t = setInterval(() => s.write('x'.repeat(1000)), 100);
      s.on('close', () => clearInterval(t));
    });
    const ports = {};
    for (const [name, srv] of Object.entries({ big, small, freeze, reset, slow })) {
      ports[name] = await listen(srv);
      servers.push(srv);
    }
    // простейший SOCKS5: принимает имя домена, подключается к локальному порту из запроса
    const socks = net.createServer((c) => {
      c.once('data', () => {
        c.write(Buffer.from([5, 0]));
        c.once('data', (req) => {
          const port = req.readUInt16BE(req.length - 2);
          const up = net.connect(port, '127.0.0.1', () => {
            c.write(Buffer.from([5, 0, 0, 1, 0, 0, 0, 0, 0, 0]));
            c.pipe(up);
            up.pipe(c);
          });
          up.on('error', () => c.destroy());
          c.on('error', () => up.destroy());
        });
      });
    });
    const socksPort = await listen(socks);
    const opts = (n) => ({ destPort: ports[n], tls: { ca: tls.cert } });
    await test('проба: большая страница доходит (успех)', async () => assert.strictEqual(await probeHost('test.local', socksPort, 3000, opts('big')), true));
    await test('проба: короткая страница дочитана до конца (успех)', async () => assert.strictEqual(await probeHost('test.local', socksPort, 3000, opts('small')), true));
    await test('проба: поток замерзает после 16 КБ (неуспех)', async () => assert.strictEqual(await probeHost('test.local', socksPort, 1500, opts('freeze')), false));
    await test('проба: медленный поток без лимита проходит, с порогом скорости нет (замедление)', async () => {
      const o = opts('slow');
      assert.strictEqual(await probeHost('test.local', socksPort, 4000, { ...o, minBytes: 20000 }), true, 'без бюджета: 20 КБ набираются');
      assert.strictEqual(await probeHost('test.local', socksPort, 4000, { ...o, minBytes: 60000, budgetMs: 1500 }), false, 'с бюджетом: 60 КБ за 1,5 с не успевают');
      assert.strictEqual(await probeHost('test.local', socksPort, 3000, { ...opts('big'), minBytes: 60000, budgetMs: 1500 }), true, 'быстрый поток проходит бюджет');
    });
    await test('проба: соединение сброшено (неуспех)', async () => assert.strictEqual(await probeHost('test.local', socksPort, 1500, opts('reset')), false));
    await test('проба: чужой сертификат отклоняется', async () => assert.strictEqual(await probeHost('test.local', socksPort, 1500, { destPort: ports.big }), false));
    socks.close();
    servers.forEach((s) => s.close());
  }

  // ---------------- движок на настоящем процессе ByeDPI (если есть собранный для этой ОС)
  const bin = process.env.WT_TEST_CIADPI;
  if (bin && fs.existsSync(bin)) {
    const resDir = path.join(tmp, 'res');
    fs.mkdirSync(path.join(resDir, 'bin', process.platform), { recursive: true });
    fs.copyFileSync(bin, path.join(resDir, 'bin', process.platform, process.platform === 'win32' ? 'ciadpi.exe' : 'ciadpi'));
    fs.chmodSync(path.join(resDir, 'bin', process.platform, 'ciadpi'), 0o755);
    const store = new Store(path.join(tmp, 'eng'));
    store.set('enabled', ['youtube']);
    store.set('strategy_youtube', 'split+disorder');
    store.set('socksPort', await freePort());
    const engine = new Engine({ resourcesDir: resDir, store, config });
    const logs = [];
    engine.on('log', (l) => logs.push(l));
    await test('движок: запуск, PAC обновляется, остановка', async () => {
      assert.strictEqual(await engine.start(), true, logs.join('\n'));
      assert(engine.running);
      const pac = engine.pac.content;
      assert(pac.includes('youtube.com') && !pac.includes('instagram.com'));
      await engine.stop();
      assert(!engine.running);
      assert(!engine.pac.content.includes('youtube.com'), 'после остановки все адреса идут напрямую');
    });
    await test('движок: повторный запуск на том же порту после остановки', async () => {
      assert.strictEqual(await engine.start(), true, logs.join('\n'));
      await engine.stop();
      assert.strictEqual(await engine.start(), true, logs.join('\n'));
      await engine.stop();
    });
    await test('движок: занятый порт не ломает запуск (берётся свободный)', async () => {
      const busy = net.createServer();
      const p = await new Promise((r) => busy.listen(0, '127.0.0.1', () => r(busy.address().port)));
      store.set('socksPort', p);
      assert.strictEqual(await engine.start(), true, logs.join('\n'));
      assert.notStrictEqual(engine.socksPort, p);
      await engine.stop();
      busy.close();
    });
    await engine.shutdown();
  } else {
    results.push('SKIP движок с реальным ByeDPI (задайте WT_TEST_CIADPI=путь к ciadpi)');
  }

  console.log(results.join('\n'));
  console.log(`\nПройдено проверок: ${passed}${process.exitCode ? ', ЕСТЬ ОШИБКИ' : ''}`);
  fs.rmSync(tmp, { recursive: true, force: true });
  process.exit(process.exitCode || 0);
})();
