#!/usr/bin/env node
// Run through an SSH tunnel to the server's loopback-only Spring Boot port.
// Example: E2E_BASE_URL=http://127.0.0.1:18088 E2E_ORG_ID=1 node scripts/verify-deploy-mock-e2e.cjs
const { randomUUID, randomBytes } = require('node:crypto');

const base = process.env.E2E_BASE_URL || 'http://127.0.0.1:18088';
const orgId = Number(process.env.E2E_ORG_ID);
if (!Number.isSafeInteger(orgId) || orgId < 1) {
  console.error('E2E_ORG_ID must be a verified organization ID');
  process.exit(2);
}
const stamp = new Date().toISOString().replace(/[-:.TZ]/g, '').slice(0, 14);
const suffix = randomUUID().slice(0, 8);
const email = `agent-e2e-${stamp}-${suffix}@example.invalid`;
const password = randomBytes(24).toString('base64url');
const name = `Agent Mock E2E ${stamp} ${suffix}`;
let token;

async function request(method, path, body, expected, timeout = 12000) {
  const response = await fetch(new URL(path, base), {
    method,
    headers: { ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
      ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(timeout),
  });
  if (response.status !== expected) throw new Error(`${method} ${path} returned HTTP ${response.status}; expected ${expected}`);
  const value = await response.json();
  if (!value || typeof value !== 'object') throw new Error(`${method} ${path} returned invalid JSON`);
  return value;
}

function requiredId(value, label) {
  if (typeof value !== 'string' && typeof value !== 'number') throw new Error(`${label} missing`);
  const id = String(value);
  if (!id || id === 'undefined' || id === 'null') throw new Error(`${label} missing`);
  return id;
}

async function readSse(runId) {
  const response = await fetch(new URL(`/api/v1/screenplay/agent/runs/${runId}/events`, base), {
    headers: { Authorization: `Bearer ${token}`, Accept: 'text/event-stream' },
    signal: AbortSignal.timeout(30000),
  });
  if (response.status !== 200 || !response.headers.get('content-type')?.includes('text/event-stream')) {
    throw new Error(`Java SSE returned HTTP ${response.status} or wrong content type`);
  }
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  const events = [];
  let buffer = '';
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true }).replace(/\r/g, '');
      let boundary;
      while ((boundary = buffer.indexOf('\n\n')) !== -1) {
        const frame = buffer.slice(0, boundary);
        buffer = buffer.slice(boundary + 2);
        const type = frame.split('\n').find(line => line.startsWith('event:'))?.slice(6).trim();
        const id = frame.split('\n').find(line => line.startsWith('id:'))?.slice(3).trim();
        if (type && id) events.push({ id, type });
        if (type === 'run.completed' || type === 'run.failed' || type === 'run.cancelled') return events;
      }
    }
  } finally { await reader.cancel().catch(() => {}); }
  throw new Error('Java SSE closed before a terminal event');
}

async function main() {
  await request('POST', '/api/v1/users/register', { email, password, fullName: name, organizationId: orgId }, 200);
  const login = await request('POST', '/api/v1/auth/login', { email, password }, 200);
  if (typeof login.accessToken !== 'string' || !login.accessToken) throw new Error('Login accessToken missing');
  token = login.accessToken;
  const listed = await request('GET', '/api/v1/screenplay/projects', undefined, 200);
  if (!Array.isArray(listed)) throw new Error('Project list was not an array');
  const project = await request('POST', '/api/v1/screenplay/projects',
    { name, description: 'Isolated Java to Gateway mock acceptance run', genre: 'Drama' }, 201);
  const projectId = requiredId(project.id, 'projectId');
  const script = await request('POST', `/api/v1/screenplay/projects/${projectId}/scripts`,
    { versionName: `Mock E2E ${stamp}`, originalFilename: 'mock-e2e.txt',
      rawText: '内景，旧车站，夜。林舟等候许晴。许晴进门，把一封信放在长椅上。' }, 201);
  const screenplayId = requiredId(script.id, 'screenplayId');
  const session = await request('POST', `/api/v1/screenplay/projects/${projectId}/agent/sessions`,
    { screenplayId: Number(screenplayId), taskType: 'CHECK_PLOT_LOGIC' }, 201);
  const sessionId = requiredId(session.id, 'sessionId');
  const run = await request('POST', `/api/v1/screenplay/agent/sessions/${sessionId}/messages`,
    { message: '请检查这段剧情的逻辑，并说明证据。', clientRequestId: randomUUID() }, 202);
  const runId = requiredId(run.id, 'runId');
  console.log(JSON.stringify({ phase: 'submitted', projectId, screenplayId, sessionId, runId }));
  const ssePromise = readSse(runId);
  let current;
  const deadline = Date.now() + 75000;
  do {
    current = await request('GET', `/api/v1/screenplay/agent/runs/${runId}`, undefined, 200);
    if (['completed', 'failed', 'cancelled', 'interrupted'].includes(current.status)) break;
    await new Promise(resolve => setTimeout(resolve, 1000));
  } while (Date.now() < deadline);
  if (!['completed', 'failed', 'cancelled', 'interrupted'].includes(current.status)) throw new Error('Java run did not reach a terminal state within 75 seconds');
  const events = await ssePromise;
  const eventTypes = events.map(event => event.type);
  console.log(JSON.stringify({ phase: 'terminal', projectId, screenplayId, sessionId, runId,
    status: current.status, errorCode: current.errorCode || null, sseEvents: eventTypes,
    lastEventId: events.at(-1)?.id }));
  if (current.status !== 'completed') throw new Error(`Java run ended ${current.status} (${current.errorCode || 'no public code'})`);
  if (!eventTypes.includes('run.completed') || !eventTypes.includes('run.started')) throw new Error('Java SSE lacks required run events');
}

main().catch(error => { console.error(`E2E failed: ${error.message}`); process.exitCode = 1; });
