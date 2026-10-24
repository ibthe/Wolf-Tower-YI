const { probeHost } = require('./probe');

async function runWithLimit(tasks, limit) {
  let i = 0;
  const worker = async () => {
    while (i < tasks.length) {
      const t = tasks[i++];
      await t();
    }
  };
  await Promise.all(Array.from({ length: Math.min(limit, tasks.length) }, worker));
}

/**
 * Для каждого варианта стратегии поднимается отдельный ByeDPI (по 4 одновременно) и через него
 * проверяются тестовые адреса выбранных сервисов. Возвращает { idСервиса: { стратегия: числоУспехов } }.
 */
async function score({ makeProcess, freePort, forName, profiles, candidates, isCancelled, onProgress, batch = 4 }) {
  const counts = {};
  for (const p of profiles) counts[p.id] = {};

  for (let i = 0; i < candidates.length; i += batch) {
    if (isCancelled()) break;
    const group = candidates.slice(i, i + batch);
    const procs = [];
    try {
      let reason = '';
      for (const strategy of group) {
        const port = await freePort();
        const proc = makeProcess();
        if (await proc.start(port, forName(strategy))) procs.push({ strategy, port, proc });
        else reason = proc.lastError || reason;
      }
      if (!procs.length) throw new Error('не удалось запустить ByeDPI' + (reason ? ': ' + reason : ''));
      const tasks = [];
      for (const { strategy, port } of procs) {
        for (const profile of profiles) {
          for (const host of profile.probeHosts) {
            tasks.push(async () => {
              if (await probeHost(host, port, 5000, { minBytes: profile.probeMinBytes, budgetMs: profile.probeBudgetMs })) {
                counts[profile.id][strategy] = (counts[profile.id][strategy] || 0) + 1;
              }
            });
          }
        }
      }
      await runWithLimit(tasks, 12);
    } finally {
      await Promise.all(procs.map((p) => p.proc.stop()));
    }
    onProgress(Math.min(i + batch, candidates.length));
    // Ранее найденный вариант с полным баллом не проиграет более позднему (при равенстве выигрывает более
    // ранний), поэтому остальные пачки можно не проверять.
    if (profiles.every((p) => !p.probeHosts.length || Object.values(counts[p.id]).some((c) => c >= p.probeHosts.length))) break;
  }
  return counts;
}

/** Максимум успехов; при равенстве выигрывает более ранний вариант ("none" проверяется первым). */
function chooseBest(counts, candidates) {
  let best = null;
  let bestOk = 0;
  for (const s of candidates) {
    const c = counts[s] || 0;
    if (c > bestOk) {
      best = s;
      bestOk = c;
    }
  }
  return { best, ok: bestOk };
}

module.exports = { score, chooseBest };
