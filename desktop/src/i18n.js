// Три языка: русский (текст в коде служит ключом), английский и чеченский.
// Переводы: i18n/translations.json (общий файл для Android и компьютера). Подстановки: {0}, {1}...
// Нет перевода: показывается русский текст. Технические строки журнала не переводятся.
const fs = require('fs');

const LANGS = [
  ['en', 'English'],
  ['ce', 'Нохчийн'],
  ['ru', 'Русский'],
];

let dict = {};
let lang = 'ru';

function load(paths) {
  for (const p of paths) {
    try {
      dict = JSON.parse(fs.readFileSync(p, 'utf8'));
      return true;
    } catch (_) {
      /* пробуем следующий путь */
    }
  }
  return false;
}

/** Язык по умолчанию из языка системы: ru, ce, иначе английский. */
function fromLocale(locale) {
  const l = String(locale || '').slice(0, 2).toLowerCase();
  return l === 'ru' ? 'ru' : l === 'ce' ? 'ce' : 'en';
}

function setLang(l) {
  if (LANGS.some(([c]) => c === l)) lang = l;
}

const getLang = () => lang;

function tr(ru) {
  if (lang === 'ru') return ru;
  const e = dict[ru];
  return (e && e[lang]) || ru;
}

function trf(ru, ...args) {
  return tr(ru).replace(/\{(\d+)\}/g, (m, i) => (args[i] !== undefined ? String(args[i]) : m));
}

/** Форма слова при числе: русский (3 формы), английский (1 / много), чеченский (после числа не меняется). */
function plural(n, one, few, many) {
  if (lang === 'en') return tr(n === 1 ? one : few);
  if (lang === 'ce') return tr(one);
  const m100 = n % 100;
  const m10 = n % 10;
  if (m100 >= 11 && m100 <= 14) return many;
  if (m10 === 1) return one;
  if (m10 >= 2 && m10 <= 4) return few;
  return many;
}

/** Словарь текущего языка для окна (ключ: русский текст, значение: перевод). Для русского пустой. */
function table() {
  if (lang === 'ru') return {};
  const out = {};
  for (const [k, v] of Object.entries(dict)) if (v[lang]) out[k] = v[lang];
  return out;
}

module.exports = { LANGS, load, fromLocale, setLang, getLang, tr, trf, plural, table };
