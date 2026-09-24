import http from 'node:http';
import assert from 'node:assert/strict';

// Loopback-only protocol fixture, not a model-quality evaluation.
const backend = 'http://127.0.0.1:8091';
const fixture = 'http://127.0.0.1:8092';
const prior = 'Alice missed the call.';
const next = 'Alice remembers answering that call.';
const corrected = 'Alice still had a missed call.';

async function json(url, method = 'GET', body) {
  const response = await fetch(url, {
    method, headers: { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(180000),
  });
  const data = await response.json();
  if (method === 'PUT' && !response.ok)
    throw new Error(`Fixture setup write failed: ${response.status} ${JSON.stringify(data)}`);
  return { status: response.status, data };
}

if (process.argv[2] === 'serve') {
  let calls = 0;
  let fail = false;
  const kinds = {};
  const server = http.createServer(async (request, response) => {
    const chunks = [];
    for await (const chunk of request) chunks.push(chunk);
    const body = chunks.length ? JSON.parse(Buffer.concat(chunks).toString()) : {};
    const send = (value, status = 200) => {
      response.writeHead(status, { 'Content-Type': 'application/json' });
      response.end(JSON.stringify(value));
    };
    if (request.url === '/stats') return send({ calls, kinds, fail });
    if (request.url === '/control' && request.method === 'POST') {
      fail = Boolean(body.fail);
      return send({ fail });
    }
    if (request.url !== '/v1/chat/completions') return send({ error: 'fixture route not found' }, 404);
    calls++;
    if (fail) return send({ error: { message: 'Intentional local fixture failure' } }, 500);
    const system = body.messages?.filter(m => m.role === 'system').map(m => m.content).join('\n') ?? '';
    const user = body.messages?.filter(m => m.role === 'user').map(m => m.content).join('\n') ?? '';
    let kind = 'other';
    let result = { queries: [], note: 'Local protocol fixture' };
    if (system.includes('You audit causal continuity of fiction')) {
      kind = 'extract';
      const source = user.split('Extract only this segment:\n')[1] ?? '';
      const quote = source.includes(next) ? next : source.includes(corrected) ? corrected
        : source.includes(prior) ? prior : '';
      const answered = quote === next;
      const claim = { entity: 'phone', property: 'call:first:status',
        value: answered ? 'answered' : 'missed', quote, confidence: 0.95, inferred: false };
      result = { coverage: 'complete', aliases: [], uncertainties: [],
        events: quote ? [{ label: 'call state', frame: 'main', quote,
          requires: answered ? [claim] : [], effects: answered ? [] : [claim],
          narrative: { time: '', timeQuote: '', perspective: '', scope: '' } }] : [] };
    } else if (system.includes('Audit a suspected fiction causal contradiction')) {
      kind = 'verify';
      const candidate = JSON.parse(user.split('PROPOSED (may be wrong):\n')[1].split('\nPRIOR excerpt:')[0]);
      result = { verdict: 'conflict', explanation: 'A missed call is retold as an answered call.',
        priorQuote: candidate.prior.quote, currentQuote: candidate.current.quote, confidence: 0.95 };
    } else if (body.stream) {
      kind = 'stream';
    }
    kinds[kind] = (kinds[kind] ?? 0) + 1;
    if (body.stream) {
      response.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' });
      const content = kind === 'stream' ? `<draft>${prior}</draft>` : JSON.stringify(result);
      for (const part of [content.slice(0, 15), content.slice(15)]) {
        response.write(`data: ${JSON.stringify({ id: 'local-fixture', model: 'local-fixture',
          choices: [{ index: 0, delta: { content: part }, finish_reason: null }] })}\n\n`);
      }
      response.end(`data: ${JSON.stringify({ choices: [{ index: 0, delta: {}, finish_reason: 'stop' }],
        usage: { prompt_tokens: 100, completion_tokens: 50, total_tokens: 150 } })}\n\ndata: [DONE]\n\n`);
    } else {
      send({ id: 'local-fixture', model: 'local-fixture', choices: [{ index: 0,
        message: { role: 'assistant', content: JSON.stringify(result) }, finish_reason: 'stop' }],
      usage: { prompt_tokens: 100, completion_tokens: 50, total_tokens: 150 } });
    }
  });
  server.listen(8092, '127.0.0.1', () => console.log('Local deterministic provider on 127.0.0.1:8092'));
} else {
  const projects = await json(`${backend}/projects`);
  assert.equal(projects.status, 200);
  assert.ok(projects.data.every(p => p.id.startsWith('runtime-')), 'Refusing to configure a non-isolated server');
  const profile = { id: 'local-runtime-fixture', name: 'LOCAL TEST FIXTURE', provider: 'custom',
    api_style: 'chat_completions', base_url: `${fixture}/v1`, api_key: 'local-fixture-not-a-secret',
    model: 'local-fixture', max_tokens: 8000, temperature: 0 };
  assert.equal((await json(`${backend}/config/llm/profiles`, 'POST', profile)).status, 200);
  await json(`${backend}/config/llm/assignments`, 'POST', { writer: profile.id, archivist: profile.id,
    editor: profile.id, extractor: profile.id });
  const project = `runtime-api-${Date.now()}`;
  assert.equal((await json(`${backend}/projects`, 'POST', { name: project, language: 'en' })).status, 200);
  const base = `${backend}/projects/${project}`;
  const draft = `${base}/drafts`;
  const check = text => json(`${draft}/V1C2/continuity-check`, 'POST', { content: text });
  const review = async (text, revision, budget = 60000) =>
    json(`${draft}/V1C2/semantic-check`, 'POST', { content: text, revision, max_tokens: budget });
  const start = (await json(`${fixture}/stats`)).data.calls;
  await json(`${draft}/V1C1`, 'PUT', { content: prior });
  const unchecked = await check(next);
  assert.equal(unchecked.data.semantic_status, 'unchecked');
  assert.equal((await json(`${fixture}/stats`)).data.calls, start, 'Read-only checks must not call a model');
  assert.equal((await review(next, unchecked.data.revision)).status, 409, 'Missing prefix extraction must block');
  const status = await json(`${base}/session/continuity/V1C2/causal`);
  assert.equal(status.status, 200);
  const audit = await json(`${base}/session/continuity/V1C2/causal/run`, 'POST',
    { revision: status.data.revision, max_tokens: 60000, max_minutes: 3 });
  assert.equal(audit.data.status, 'complete', JSON.stringify(audit.data));
  const fresh = await check(next);
  const conflict = await review(next, fresh.data.revision);
  assert.equal(conflict.status, 200, JSON.stringify(conflict.data));
  assert.equal(conflict.data.semantic_status, 'complete');
  assert.ok(conflict.data.issues.some(i => i.type === 'semantic_state_mismatch'));
  assert.ok(conflict.data.reference_excerpts.includes(prior));
  const afterConflict = (await json(`${fixture}/stats`)).data.calls;
  const cached = await review(next, conflict.data.revision);
  assert.equal(cached.data.revision, conflict.data.revision);
  assert.equal((await json(`${fixture}/stats`)).data.calls, afterConflict, 'Cached review must not call again');
  await json(`${draft}/V1C2`, 'PUT', { content: next });
  await json(`${draft}/V1C1`, 'PUT', { content: `${prior} The phone was put away.` });
  const stale = await json(`${base}/session/feedback`, 'POST', {
    chapter: 'V1C2', action: 'confirm', continuity_revision: conflict.data.revision,
  });
  assert.equal(stale.status, 409, 'Stale author acknowledgement must not archive');
  assert.equal((await json(`${fixture}/stats`)).data.calls, afterConflict);
  await json(`${draft}/V1C1`, 'PUT', { content: prior });
  const low = await check(corrected);
  const partial = await review(corrected, low.data.revision, 1000);
  assert.equal(partial.data.semantic_status, 'partial', JSON.stringify(partial));
  assert.equal((await json(`${fixture}/stats`)).data.calls, afterConflict, 'Budget rejection must precede transport');
  const resumed = await review(corrected, partial.data.revision);
  assert.equal(resumed.data.semantic_status, 'complete');
  assert.equal(resumed.data.issues.length, 0);
  const failureText = `${corrected} Provider failure probe.`;
  const beforeFailure = await check(failureText);
  await json(`${fixture}/control`, 'POST', { fail: true });
  try {
    const failed = await review(failureText, beforeFailure.data.revision);
    assert.equal(failed.data.semantic_status, 'partial');
    assert.ok(failed.data.issues.some(i => i.type === 'semantic_incomplete'));
  } finally {
    await json(`${fixture}/control`, 'POST', { fail: false });
  }
  const browserProject = 'runtime-browser';
  if (!(await json(`${backend}/projects`)).data.some(p => p.id === browserProject)) {
    assert.equal((await json(`${backend}/projects`, 'POST', { name: browserProject, language: 'en' })).status, 200);
    await json(`${backend}/projects/${browserProject}/drafts/V1C1`, 'PUT', { content: '' });
  }
  console.log(JSON.stringify({ passed: true, project, browserProject,
    assertions: ['isolation', 'no-cost-read', 'missing-prefix', 'causal-extraction', 'semantic-conflict',
      'cache-reuse', 'stale-archive-409', 'token-budget', 'resume', 'provider-failure'],
    provider: (await json(`${fixture}/stats`)).data }, null, 2));
}
