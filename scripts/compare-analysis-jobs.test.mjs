import { test } from 'node:test';
import assert from 'node:assert/strict';
import { compareJobs } from './compare-analysis-jobs.mjs';
function job(tokens = 100, latency = 100, unknown = 0) {
  return { status: 'complete', project_id: 'fixture', model_profile_hash: 'model',
    usage: { stages: { summary: { reported_tokens: tokens, budget_tokens: tokens, calls: 1, unknown_usage_calls: unknown } } },
    results: Array.from({ length: 100 }, (_, index) => ({
      chapter: `V1C${index + 1}`, source_hash: 'source', title_hash: 'title',
      success: true, skipped: false, latency_ms: latency,
    })) };
}
test('missing human quality review cannot pass acceptance', () => {
  assert.equal(compareJobs(job(), job(40, 50)).accepted, false);
});
test('unknown usage cannot be advertised as measured savings', () => {
  assert.equal(compareJobs(job(), job(0, 50, 1)).tokenRatio, null);
});
test('rejects different input or model', () => {
  assert.throws(() => compareJobs(job(), { ...job(), model_profile_hash: 'other' }));
});
test('requires all declared performance and quality gates', () => {
  const quality = { baseline: { reviewed_facts: 100, correct_facts: 95 }, candidate: { reviewed_facts: 100, correct_facts: 94 } };
  assert.equal(compareJobs(job(), job(40, 50), quality).accepted, true);
  assert.equal(compareJobs(job(), job(90, 50), quality).accepted, false);
});
test('cached or undersized samples cannot pass the full benchmark', () => {
  const quality = { baseline: { reviewed_facts: 100, correct_facts: 95 }, candidate: { reviewed_facts: 100, correct_facts: 94 } };
  const cached = job(40, 50);
  cached.results[0].skipped = true;
  assert.equal(compareJobs(job(), cached, quality).accepted, false);
  const baseline = job(), candidate = job(40, 50);
  baseline.results = baseline.results.slice(0, 10);
  candidate.results = candidate.results.slice(0, 10);
  assert.equal(compareJobs(baseline, candidate, quality).accepted, false);
});
