const $ = (id) => document.getElementById(id);
let S = null;
let logOpen = false;
let advOpen = false;
let keyOpen = null; // null: решает состояние лицензии
let justActivated = false;
let logs = [];
let rawTouched = false;

let LANG = 'ru';
let DICT = {};
const tr = (ru) => (LANG === 'ru' ? ru : DICT[ru] || ru);
const trf = (ru, ...a) => tr(ru).replace(/\{(\d+)\}/g, (m, i) => (a[i] !== undefined ? String(a[i]) : m));

/** Форма слова при числе: русский (3 формы), английский (1 / много), чеченский (после числа не меняется). */
function plural(n, one, few, many) {
  if (LANG === 'en') return tr(n === 1 ? one : few);
  if (LANG === 'ce') return tr(one);
  const m100 = n % 100;
  const m10 = n % 10;
  if (m100 >= 11 && m100 <= 14) return many;
  if (m10 === 1) return one;
  if (m10 >= 2 && m10 <= 4) return few;
  return many;
}

/** Статичные тексты страницы: ключ в атрибуте data-i18n (или в тексте элемента). */
function applyI18n() {
  document.documentElement.lang = LANG;
  for (const el of document.querySelectorAll('[data-i18n]')) {
    if (!el.dataset.ru) el.dataset.ru = el.textContent.trim().replace(/\s+/g, ' ');
    el.textContent = tr(el.dataset.ru);
  }
  for (const el of document.querySelectorAll('[data-i18n-ph]')) el.placeholder = tr(el.dataset.i18nPh);
  for (const el of document.querySelectorAll('[data-i18n-aria]')) el.setAttribute('aria-label', tr(el.dataset.i18nAria));
}

function renderLang() {
  const box = $('langRow');
  box.textContent = '';
  for (const [code, name] of S.langs) {
    const b = document.createElement('button');
    b.className = 'chip' + (code === LANG ? ' on' : '');
    b.textContent = name;
    b.addEventListener('click', () => window.wt.setLang(code));
    box.append(b);
  }
}

/** "142 дня" / "5 часов" до конца срока; null, если лицензия бессрочная или срок вышел. */
function leftText(untilMs) {
  if (typeof untilMs !== 'number') return null;
  const ms = untilMs - Date.now();
  if (ms <= 0) return null;
  const d = Math.floor(ms / 86400000);
  if (d >= 1) return `${d} ${plural(d, 'день', 'дня', 'дней')}`;
  const h = Math.max(1, Math.floor(ms / 3600000));
  return `${h} ${plural(h, 'час', 'часа', 'часов')}`;
}

function strategyLabel(s) {
  if (s === 'none') return tr('без обхода');
  if (s.startsWith('raw:')) return tr('своя строка');
  return s;
}

/** Кнопка «копировать»: значок меняется на галочку, подпись на «Скопировано», через 2 с всё возвращается. */
function setupCopyButton(btn, getText) {
  const lbl = btn.querySelector('.lbl');
  const normal = lbl.textContent;
  let timer = null;
  btn.addEventListener('click', async () => {
    await window.wt.copyText(getText());
    btn.classList.add('copied');
    lbl.textContent = tr('Скопировано');
    clearTimeout(timer);
    timer = setTimeout(() => {
      btn.classList.remove('copied');
      lbl.textContent = tr(normal);
    }, 2000);
  });
}

const GLYPH = {
  youtube: '<svg viewBox="0 0 34 34"><rect x="0" y="5" width="34" height="24" rx="7" fill="#ff0000"/><path d="M13.6 12.2v9.6l8.4-4.8z" fill="#fff"/></svg>',
  instagram: '<svg viewBox="0 0 34 34"><defs><linearGradient id="ig" x1="0" y1="1" x2="1" y2="0"><stop offset="0" stop-color="#feda75"/><stop offset=".28" stop-color="#fa7e1e"/><stop offset=".55" stop-color="#d62976"/><stop offset=".78" stop-color="#962fbf"/><stop offset="1" stop-color="#4f5bd5"/></linearGradient></defs><rect width="34" height="34" rx="9.5" fill="url(#ig)"/><rect x="6.8" y="6.8" width="20.4" height="20.4" rx="6" fill="none" stroke="#fff" stroke-width="2.5"/><circle cx="17" cy="17" r="4.8" fill="none" stroke="#fff" stroke-width="2.5"/><circle cx="23.4" cy="10.6" r="1.4" fill="#fff"/></svg>',
};

let advId = null;

function renderServices() {
  const box = $('svcList');
  box.textContent = '';
  for (const p of S.profiles) {
    const row = document.createElement('label');
    row.className = 'svc';
    const ico = document.createElement('span');
    ico.className = 'svc-ico';
    ico.innerHTML = GLYPH[p.id] || ''; // значки задаются в коде (константы выше), пользовательских данных тут нет
    const txt = document.createElement('span');
    txt.className = 'svc-txt';
    const score = S.probe.scores && S.probe.scores[p.id] ? trf(' (проверка: {0})', S.probe.scores[p.id]) : '';
    const name = document.createElement('b');
    name.textContent = p.name;
    const sub = document.createElement('span');
    sub.className = 'muted small';
    sub.textContent = `${tr('Стратегия: ')}${strategyLabel(S.chosen[p.id])}${score}`;
    txt.append(name, sub);
    const sw = document.createElement('input');
    sw.type = 'checkbox';
    sw.className = 'switch ' + p.id;
    sw.checked = S.enabled.includes(p.id);
    sw.disabled = S.running;
    sw.addEventListener('change', () => window.wt.setEnabled(p.id, sw.checked));
    row.append(ico, txt, sw);
    box.append(row);
  }
  $('svcNote').hidden = !S.running;
}

function renderAdvSvc() {
  const box = $('advSvc');
  box.textContent = '';
  for (const p of S.profiles) {
    const b = document.createElement('button');
    b.className = 'chip' + (p.id === advId ? ' on' : '');
    b.textContent = p.name;
    b.addEventListener('click', () => {
      advId = p.id;
      rawTouched = false;
      render();
    });
    box.append(b);
  }
}

function renderChips(profile) {
  const box = $('chips');
  box.textContent = '';
  const cur = S.chosen[profile.id];
  for (const s of ['none', ...S.strategies]) {
    const b = document.createElement('button');
    b.className = 'chip' + (s === cur ? ' on' : '');
    b.disabled = S.running;
    b.textContent = strategyLabel(s);
    b.addEventListener('click', () => window.wt.setStrategy(profile.id, s));
    box.append(b);
  }
}

function renderLog() {
  $('logTitle').textContent = trf('Лог ({0})', logs.length);
  $('logToggle').textContent = logOpen ? tr('Свернуть') : tr('Развернуть');
  $('logLast').hidden = logOpen;
  $('logBox').hidden = !logOpen;
  $('logLast').textContent = logs.length ? logs[logs.length - 1] : tr('Пока пусто');
  if (logOpen) {
    const pre = $('logText');
    pre.textContent = logs.slice(-100).join('\n');
    pre.scrollTop = pre.scrollHeight;
  }
}

function render() {
  if (!S) return;
  LANG = S.lang || 'ru';
  DICT = S.dict || {};
  applyI18n();
  renderLang();
  if (!advId || !S.profiles.some((p) => p.id === advId)) advId = S.profiles[0].id;
  const profile = S.profiles.find((p) => p.id === advId);
  const chosen = S.profiles.filter((p) => S.enabled.includes(p.id));
  const names = chosen.map((p) => p.name).join(tr(' и '));
  const canRun = S.license.licensed;
  const busy = S.busy || S.probe.running;

  // ----- кнопка и статус
  const t = $('toggle');
  t.classList.toggle('on', S.running);
  t.classList.toggle('busy', busy);
  t.disabled = busy || (!S.running && (!canRun || !chosen.length));
  const head = $('status');
  head.textContent = S.running
    ? tr('Защита включена')
    : S.probe.running
      ? tr('Подбор стратегии…')
      : S.busy
        ? tr('Проверка лицензии…')
        : tr('Защита выключена');
  head.classList.toggle('on-text', S.running);
  $('hint').textContent = S.running
    ? trf(chosen.length === 1 ? '{0} идёт через обход' : '{0} идут через обход', names)
    : !canRun
      ? tr('Для запуска введите лицензионный ключ')
      : !chosen.length
        ? tr('Включите хотя бы один сервис ниже')
        : trf('Нажмите кнопку, чтобы разблокировать {0}', names);
  $('hint').classList.toggle('amber', !S.running && (!canRun || !chosen.length));
  renderServices();

  // ----- лицензия: после активации поля ключа убираются, остаётся только срок
  const showKey = keyOpen === null ? !canRun : keyOpen;
  $('licenseChip').hidden = !(canRun && !showKey);
  $('keyCard').hidden = !showKey;
  if (canRun) {
    const left = leftText(S.license.untilMs);
    const soon = typeof S.license.untilMs === 'number' && S.license.untilMs - Date.now() < 7 * 86400000;
    $('licenseChip').classList.toggle('soon', soon);
    $('licTitle').textContent = justActivated ? tr('Ключ принят') : S.license.plan === 'trial' ? tr('Пробная лицензия') : tr('Лицензия активна');
    $('licSub').textContent = left ? trf('Осталось {0}', left) : tr('Бессрочная');
  }
  $('licenseText').textContent = canRun ? '' : S.license.text;
  $('licenseText').hidden = canRun;
  $('deviceId').textContent = S.license.deviceId;
  $('keyCancel').hidden = !(canRun && showKey);
  $('newKeyBtn').hidden = !(canRun && !showKey);

  // ----- стратегия
  const cur = S.chosen[profile.id];
  $('probeBtn').disabled = S.running || S.probe.running || !canRun || !chosen.length;
  $('probeProgress').textContent = S.probe.running ? S.probe.progress : '';
  $('probeCancel').hidden = !S.probe.running;
  $('probeReport').textContent = S.probe.report || '';
  $('probeReport').hidden = !S.probe.report;

  // ----- дополнительно
  $('adv').hidden = !advOpen;
  $('advToggle').classList.toggle('open', advOpen);
  if (advOpen) {
    renderAdvSvc();
    renderChips(profile);
    $('rawApply').disabled = S.running;
    if (!rawTouched && cur.startsWith('raw:')) $('rawInput').value = cur.slice(4);
  }
  $('platformNote').textContent =
    S.platform === 'darwin'
      ? tr('При первом включении защиты macOS попросит пароль администратора для настройки прокси.')
      : S.platform === 'win32'
        ? tr('Приложения из Microsoft Store системный прокси могут не учитывать: используйте YouTube и Instagram в браузере.')
        : '';
  renderLog();
}

$('toggle').addEventListener('click', () => window.wt.toggleProtection());
$('probeBtn').addEventListener('click', () => window.wt.startProbe());
$('probeCancel').addEventListener('click', () => window.wt.cancelProbe());
$('advToggle').addEventListener('click', () => {
  advOpen = !advOpen;
  render();
});
$('newKeyBtn').addEventListener('click', () => {
  keyOpen = true;
  render();
});
$('keyCancel').addEventListener('click', () => {
  keyOpen = null;
  $('licenseMsg').textContent = '';
  render();
});
setupCopyButton($('copyId'), () => S.license.deviceId);
setupCopyButton($('logCopy'), () => logs.join('\n'));
$('paste').addEventListener('click', async () => {
  $('keyInput').value = (await window.wt.paste()).trim();
});
$('activate').addEventListener('click', async () => {
  const btn = $('activate');
  btn.disabled = true;
  btn.textContent = tr('Проверка…');
  const err = await window.wt.activate($('keyInput').value);
  btn.disabled = false;
  btn.textContent = tr('Активировать');
  $('licenseMsg').textContent = err || '';
  if (!err) {
    $('keyInput').value = '';
    keyOpen = null;
    justActivated = true;
    setTimeout(() => {
      justActivated = false;
      render();
    }, 3000);
    render();
  }
});
$('rawInput').addEventListener('input', () => {
  rawTouched = true;
  $('rawErr').textContent = '';
});
$('rawApply').addEventListener('click', async () => {
  const text = $('rawInput').value.trim();
  if (!text) return;
  const ok = await window.wt.setStrategy(advId, 'raw:' + text);
  $('rawErr').textContent = ok ? '' : tr('Допустимы только флаги обхода: -s -d -o -q -f -t -r -a и т.п.');
  if (ok) rawTouched = false;
});
$('logToggle').addEventListener('click', () => {
  logOpen = !logOpen;
  renderLog();
});
$('logClear').addEventListener('click', async () => {
  await window.wt.clearLog();
  logs = [];
  renderLog();
});
// диагностика копируется в основном процессе; значок на кнопке меняется так же, как у остальных
(() => {
  const btn = $('diag');
  const lbl = btn.querySelector('.lbl');
  const normal = lbl.textContent;
  let timer = null;
  btn.addEventListener('click', async () => {
    await window.wt.copyDiag();
    btn.classList.add('copied');
    lbl.textContent = tr('Скопировано');
    clearTimeout(timer);
    timer = setTimeout(() => {
      btn.classList.remove('copied');
      lbl.textContent = tr(normal);
    }, 2000);
  });
})();
$('licensesToggle').addEventListener('click', async () => {
  const pre = $('licensesText');
  pre.hidden = !pre.hidden;
  if (!pre.hidden && !pre.textContent) pre.textContent = await window.wt.componentLicenses();
});

window.wt.onState((s) => {
  S = s;
  render();
});
window.wt.onLog((line) => {
  logs.push(line);
  if (logs.length > 300) logs.shift();
  renderLog();
});
(async () => {
  logs = await window.wt.getLogs();
  S = await window.wt.getState();
  render();
})();
