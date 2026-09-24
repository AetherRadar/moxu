import { readFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';

const sum = (rows, key) => rows.reduce((total, row) => total + (Number(row[key]) || 0), 0);
const p95 = values => {
  const sorted = [...values].sort((a, b) => a - b);
  return sorted.length ? sorted[Math.ceil(sorted.length * 0.95) - 1] : null;
};
function metrics(job) {
  const stages = Object.values(job.usage?.stages ?? {});
  const results = job.results ?? [];
  return {
    measuredTokens: sum(stages, 'reported_tokens'),
    accountedTokens: sum(stages, 'budget_tokens'),
    unknownCalls: sum(stages, 'unknown_usage_calls'),
    calls: sum(stages, 'calls'),
    p95Ms: p95(results.map(row => row.latency_ms).filter(Number.isFinite)),
    successRate: results.length ? results.filter(row => row.success).length / results.length : 0,
  };
}
function accuracy(review) {
  if (!review || !Number.isInteger(review.reviewed_facts) || review.reviewed_facts <= 0
      || !Number.isInteger(review.correct_facts) || review.correct_facts < 0
      || review.correct_facts > review.reviewed_facts) return null;
  return review.correct_facts / review.reviewed_facts;
}
export function compareJobs(baseline, candidate, quality = {}) {
  const snapshot = job => JSON.stringify((job.results ?? []).map(row => [
    row.chapter, row.source_hash, row.title_hash,
  ]).sort((a, b) => a[0].localeCompare(b[0])));
  if (!baseline.project_id || !baseline.model_profile_hash
      || baseline.project_id !== candidate.project_id || baseline.model_profile_hash !== candidate.model_profile_hash
      || snapshot(baseline) !== snapshot(candidate) || !baseline.results?.length
      || baseline.results.some(row => !row.source_hash || !row.title_hash)) {
    throw new Error('Comparison requires identical chapters, source/title hashes, project and model configuration.');
  }
  const old = metrics(baseline), next = metrics(candidate);
  const baselineAccuracy = accuracy(quality.baseline), candidateAccuracy = accuracy(quality.candidate);
  const measured = old.unknownCalls === 0 && next.unknownCalls === 0
    && old.measuredTokens > 0 && next.measuredTokens > 0;
  const tokenRatio = measured ? next.measuredTokens / old.measuredTokens : null;
  const latencyRatio = old.p95Ms > 0 && next.p95Ms !== null ? next.p95Ms / old.p95Ms : null;
  const gates = {
    completeJobs: baseline.status === 'complete' && candidate.status === 'complete',
    hundredChapterSample: baseline.results.length >= 100,
    uncachedSamples: [baseline, candidate].every(job => job.results.every(row => row.skipped === false)),
    completeLatencies: [baseline, candidate].every(job => job.results.every(row =>
      Number.isFinite(row.latency_ms) && row.latency_ms >= 0)),
    measuredUsage: measured,
    tokenReduction50Percent: tokenRatio !== null && tokenRatio <= 0.5,
    p95Reduction40Percent: latencyRatio !== null && latencyRatio <= 0.6,
    successRateNotReduced: next.successRate >= old.successRate,
    qualityReviewed: baselineAccuracy !== null && candidateAccuracy !== null,
    accuracyDropAtMost5Points: baselineAccuracy !== null && candidateAccuracy !== null
      && candidateAccuracy >= baselineAccuracy - 0.05,
  };
  return { baseline: old, candidate: next, tokenRatio, latencyRatio, baselineAccuracy, candidateAccuracy,
    gates, accepted: Object.values(gates).every(Boolean) };
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const [baselinePath, candidatePath, qualityPath] = process.argv.slice(2);
  if (!baselinePath || !candidatePath) throw new Error('Usage: node scripts/compare-analysis-jobs.mjs baseline.json candidate.json [quality.json]');
  const baseline = JSON.parse(await readFile(baselinePath, 'utf8'));
  const candidate = JSON.parse(await readFile(candidatePath, 'utf8'));
  const quality = qualityPath ? JSON.parse(await readFile(qualityPath, 'utf8')) : {};
  console.log(JSON.stringify(compareJobs(baseline, candidate, quality), null, 2));
}
