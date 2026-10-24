const os = require('os');

/** Текст диагностики для поддержки. Без IP-адресов, трафика и самого ключа. */
function buildReport({ version, license, deviceId, config, enabled, strategyFor, probeReport, port, running, logs }) {
  const lines = [];
  lines.push(`Wolf Tower ${version} (компьютер)`);
  lines.push(`ОС: ${process.platform} ${process.arch}, ${os.release()}`);
  lines.push(`Лицензия: ${license}`);
  lines.push(`ID устройства: ${deviceId}`);
  lines.push(`Защита запущена: ${running}, порт SOCKS: ${port || '-'}`);
  lines.push('', 'Сервисы:');
  for (const p of config.profiles) {
    lines.push(`- ${p.name}: ${enabled.includes(p.id) ? 'вкл' : 'выкл'}, стратегия ${strategyFor(p.id)}`);
  }
  if (probeReport) lines.push('', 'Последний подбор:', probeReport);
  lines.push('', 'Лог (последние строки):', ...logs.slice(-40));
  return lines.join('\n');
}

module.exports = { buildReport };
