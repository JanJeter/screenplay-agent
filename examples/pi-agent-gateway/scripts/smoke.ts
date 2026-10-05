import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { randomUUID } from "node:crypto";
import { setTimeout as delay } from "node:timers/promises";
import { fileURLToPath } from "node:url";

const port = 31000 + Math.floor(Math.random() * 20000);
const token = randomUUID();
const base = `http://127.0.0.1:${port}`;
const cwd = fileURLToPath(new URL("../", import.meta.url));
const child = spawn(process.execPath, ["../../pi-agent/node_modules/tsx/dist/cli.mjs", "--tsconfig", "tsconfig.json", "src/server.ts"], {
  cwd, env: { ...process.env, PORT: String(port), AGENT_GATEWAY_TOKEN: token, AGENT_MODE: "mock", JAVA_MODE: "mock" },
  stdio: ["ignore", "pipe", "pipe"],
});
let log = "";
child.stdout.on("data", b => { log += String(b); });
child.stderr.on("data", b => { log += String(b); });
function call(path: string, data?: unknown, user = "demo-user", headers: Record<string, string> = {}) {
  return fetch(`${base}${path}`, {
    method: data === undefined ? "GET" : "POST",
    headers: { Authorization: `Bearer ${token}`, "X-Agent-User": user, "Content-Type": "application/json", ...headers },
    body: data === undefined ? undefined : JSON.stringify(data), signal: AbortSignal.timeout(20_000),
  });
}
async function object(response: Response): Promise<Record<string, unknown>> {
  assert.ok(response.ok, `HTTP ${response.status}: ${await response.clone().text()}`);
  return await response.json() as Record<string, unknown>;
}
try {
  let ready = false;
  for (let i = 0; i < 100; i++) {
    try { ready = (await fetch(`${base}/health`, { signal: AbortSignal.timeout(200) })).ok; } catch { /* starting */ }
    if (ready) break;
    if (child.exitCode !== null) throw new Error(log);
    await delay(100);
  }
  assert.ok(ready, log);
  assert.equal((await fetch(`${base}/agent/runs/invalid`)).status, 401);
  const session = await object(await call("/agent/sessions", { projectId: "demo-project", screenplayId: "demo-screenplay" }));
  const request = { sessionId: session.sessionId, message: "分析场景，并保存一份建议草稿。", clientRequestId: randomUUID() };
  const run = await object(await call("/agent/chat", request));
  assert.deepEqual(await object(await call("/agent/chat", request)), run);
  assert.equal((await call("/agent/chat", { ...request, message: "changed" })).status, 409);
  assert.equal((await call("/agent/chat", { ...request, clientRequestId: randomUUID() })).status, 409);
  assert.equal((await call(`/agent/runs/${run.runId}/events`, undefined, "different-user")).status, 404);
  const response = await call(`/agent/runs/${run.runId}/events`);
  assert.equal(response.status, 200);
  assert.match(response.headers.get("content-type") ?? "", /text\/event-stream/);
  assert.ok(response.body);
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let wire = "";
  let chunks = 0;
  for (;;) {
    const next = await reader.read();
    if (next.done) break;
    chunks++;
    wire += decoder.decode(next.value, { stream: true });
  }
  wire += decoder.decode();
  assert.ok(chunks > 1, "Expected incremental SSE delivery");
  assert.match(wire, /event: tool.started/);
  assert.match(wire, /event: tool.completed/);
  assert.match(wire, /event: text.delta/);
  assert.match(wire, /event: run.completed/);
  assert.doesNotMatch(wire, /thinking_delta|"authorization"|Bearer /);
  const ids = [...wire.matchAll(/^id: (\d+)$/gm)].map(m => Number(m[1]));
  assert.deepEqual(ids, Array.from({ length: ids.length }, (_, i) => i + 1));
  const replay = await (await call(`/agent/runs/${run.runId}/events`, undefined, "demo-user", { "Last-Event-ID": "2" })).text();
  assert.match(replay, /^id: 3$/m);
  assert.doesNotMatch(replay, /^id: [12]$/m);
  assert.equal((await object(await call(`/agent/runs/${run.runId}`))).status, "completed");
  const second = await object(await call("/agent/chat", { ...request, clientRequestId: randomUUID() }));
  await object(await call(`/agent/runs/${second.runId}/cancel`, {}));
  const cancelled = await (await call(`/agent/runs/${second.runId}/events`)).text();
  assert.match(cancelled, /event: run.cancelled/);
  assert.doesNotMatch(cancelled, /event: run.completed/);
  console.log("PASS: real Pi mock tool loop, incremental SSE, replay, auth/ownership, idempotency, concurrency, cancellation");
} finally {
  child.kill();
}
