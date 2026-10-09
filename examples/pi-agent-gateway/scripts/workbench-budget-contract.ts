import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve, sep } from "node:path";
import { setTimeout as delay } from "node:timers/promises";
import { fileURLToPath } from "node:url";
import { BudgetController, BudgetExceededError, inspectLiveConfiguration, liveBudgetConfig, type BudgetErrorCode } from "../src/budget.ts";
import { createRuntime } from "../src/runtime.ts";

const directory = mkdtempSync(join(tmpdir(), "workbench-budget-contract-"));
const env = {
  AGENT_MODE: "live", AGENT_BUDGET_PROFILE: "workbench", AGENT_ACTIVITY_ID: "offline-contract",
  AGENT_BUDGET_LEDGER_PATH: join(directory, "ledger.json"), AGENT_BUSINESS_RUN_LIMIT: "3", AGENT_BUDGET_USD: "0.05",
  AGENT_INPUT_TOKEN_RESERVE: "100000", AGENT_PROVIDER_INPUT_OVERHEAD_TOKENS: "1000",
  PI_PROVIDER: "deepseek", PI_MODEL: "deepseek-flash", DEEPSEEK_API_KEY: "offline-contract-key",
  JAVA_MODE: "http", JAVA_BASE_URL: "http://java-offline.invalid", AGENT_GATEWAY_TOKEN: "offline-contract-gateway-token",
  PI_INPUT_USD_PER_MTOK: "0.3", PI_OUTPUT_USD_PER_MTOK: "1.2", PI_CACHE_READ_USD_PER_MTOK: "0.006",
  PI_CACHE_WRITE_USD_PER_MTOK: "0", PI_CACHE_WRITE_1H_USD_PER_MTOK: "0",
  PI_PRICING_SOURCE: "https://api-docs.deepseek.com/quick_start/pricing",
};
const request = { sequence: 1, provider: "deepseek", model: "deepseek-flash", contextBytes: 850, outputTokenLimit: 2000 };
const usage = { input: 60, output: 20, cacheRead: 40, cacheWrite: 0, totalTokens: 120,
  cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 } };
const code = (expected: BudgetErrorCode) => (error: unknown): boolean => error instanceof BudgetExceededError && error.code === expected;
const controllers: BudgetController[] = [];
function controller(configuration = env) {
  const budget = new BudgetController(liveBudgetConfig(configuration));
  controllers.push(budget);
  return budget;
}

try {
  assert.equal(inspectLiveConfiguration(env).ready, true);
  for (const variable of ["AGENT_ACTIVITY_ID", "AGENT_BUDGET_LEDGER_PATH", "AGENT_BUSINESS_RUN_LIMIT", "AGENT_BUDGET_USD", "PI_PRICING_SOURCE"]) {
    assert.equal(inspectLiveConfiguration({ ...env, [variable]: undefined }).ready, false, `${variable} must have no paid default`);
  }
  assert.equal(inspectLiveConfiguration({ ...env, AGENT_ACTIVITY_ID: "sb12-final-five" }).ready, false);
  assert.equal(inspectLiveConfiguration({ ...env, SB12_INITIAL_BUSINESS_RUNS: "0" }).ready, false);
  assert.equal(inspectLiveConfiguration({ ...env, AGENT_BUSINESS_RUN_LIMIT: "1.5" }).ready, false);
  assert.equal(inspectLiveConfiguration({ ...env, AGENT_BUDGET_LEDGER_PATH: "relative.json" }).ready, false);

  const initial = controller();
  initial.beginBusinessRun("generate-1");
  assert.throws(() => initial.beginBusinessRun("generate-1"), code("business_run_duplicate"));
  const completed = initial.reserve({ ...request, runId: "generate-1" });
  initial.recordResponse(completed, { "x-request-id": "offline-request-id" });
  initial.complete(completed, usage);
  assert.throws(() => controller(), code("budget_uncertain"), "two live processes must not write the same ledger");
  const beforeRestart = initial.totals();
  initial.close();
  const restarted = controller();
  assert.deepEqual(restarted.totals(), beforeRestart, "a restart must not reset spend or runs");
  assert.throws(() => restarted.beginBusinessRun("generate-1"), code("business_run_duplicate"));
  restarted.beginBusinessRun("rewrite-1");
  const interrupted = restarted.reserve({ ...request, runId: "rewrite-1" });
  assert.throws(() => restarted.reserve({ ...request, sequence: 2, runId: "rewrite-1" }), code("budget_exhausted"), "outstanding reservations must count against the cap");
  restarted.close();
  const recovered = controller();
  assert.equal(recovered.totals().reservedUsd, 0);
  assert.equal(recovered.totals().chargedUsd, beforeRestart.chargedUsd + interrupted.reservedCostUsd);
  assert.throws(() => recovered.reserve({ ...request, runId: "rewrite-1" }), code("budget_exhausted"));
  recovered.beginBusinessRun("generate-2");
  assert.throws(() => recovered.beginBusinessRun("rewrite-2"), code("business_run_limit"));
  const snapshot = JSON.parse(readFileSync(env.AGENT_BUDGET_LEDGER_PATH, "utf8"));
  assert.equal(snapshot.providerRequests[0].requestId, "offline-request-id");
  assert.deepEqual(snapshot.providerRequests[0].actualUsage, { input: 60, output: 20, cacheRead: 40, cacheWrite: 0, totalTokens: 120 });
  assert.equal(snapshot.providerRequests[1].status, "failed");
  assert.equal(snapshot.providerRequests[1].actualCostUsd, undefined, "an interruption is not confirmed provider usage");
  recovered.close();
  assert.throws(() => controller({ ...env, AGENT_ACTIVITY_ID: "another-activity" }), code("budget_uncertain"));
  assert.throws(() => controller({ ...env, AGENT_BUDGET_USD: "1" }), code("budget_uncertain"), "editing configuration cannot silently raise an existing authorization");
  const corruptPath = join(directory, "corrupt.json");
  writeFileSync(corruptPath, "{broken");
  assert.throws(() => controller({ ...env, AGENT_BUDGET_LEDGER_PATH: corruptPath }), code("budget_uncertain"));
  const blockedPath = join(directory, "write-blocked.json");
  const blocked = controller({ ...env, AGENT_BUDGET_LEDGER_PATH: blockedPath });
  rmSync(blockedPath);
  mkdirSync(blockedPath);
  assert.throws(() => blocked.beginBusinessRun("cannot-save"), code("budget_uncertain"));
  assert.throws(() => blocked.reserve({ ...request, runId: "cannot-save" }), code("budget_uncertain"), "a failed write must block every subsequent request");
  blocked.close();

  // Real Pi runtime, but every fetch is intercepted. Both task profiles must
  // reject before HTTP when their next request cannot fit the USD reservation.
  const originalFetch = globalThis.fetch;
  const originalEnv = Object.fromEntries(Object.keys(env).map(name => [name, process.env[name]]));
  let httpCalls = 0;
  Object.assign(process.env, env);
  globalThis.fetch = async () => { httpCalls++; throw new Error("Network is disabled in this contract"); };
  try {
    for (const taskType of ["generate_storyboard", "rewrite_storyboard_shot"] as const) {
      const budget = controller({ ...env, AGENT_BUDGET_USD: "0.000001", AGENT_BUDGET_LEDGER_PATH: join(directory, `${taskType}.json`) });
      budget.beginBusinessRun(taskType);
      const runtime = createRuntime({ userId: "offline-user", projectId: "offline-project", screenplayId: "offline-script",
        sessionId: taskType, applicationRunId: taskType, taskType, executionToken: "offline-execution-token" }, () => {}, budget);
      runtime.prepareRun();
      await runtime.agent.prompt("离线额度拒绝测试");
      runtime.finishRun();
      assert.equal(runtime.getBudgetFailure(), "budget_exhausted");
      assert.deepEqual(runtime.getProviderLedger(), []);
      assert.equal(budget.totals().businessRuns, 1);
      budget.close();
    }
    assert.equal(httpCalls, 0, "both generate and rewrite must be stopped before any paid request");
  } finally {
    globalThis.fetch = originalFetch;
    for (const [name, value] of Object.entries(originalEnv)) {
      if (value === undefined) delete process.env[name]; else process.env[name] = value;
    }
  }

  // Exercise the real Gateway routing and terminal SSE path. The persisted
  // activity above is already at its run cap, so no model or Java call occurs.
  const port = 41000 + Math.floor(Math.random() * 10000);
  const childEnv: NodeJS.ProcessEnv = { ...process.env, ...env, PORT: String(port) };
  for (const name of Object.keys(childEnv)) if (name.startsWith("SB12_")) delete childEnv[name];
  const child = spawn(process.execPath, ["../../pi-agent/node_modules/tsx/dist/cli.mjs", "--tsconfig", "tsconfig.json", "src/server.ts"], {
    cwd: fileURLToPath(new URL("../", import.meta.url)), env: childEnv, windowsHide: true, stdio: "pipe",
  });
  let logs = "";
  child.stderr.on("data", value => { logs += String(value); });
  const base = `http://127.0.0.1:${port}`;
  const call = (path: string, payload?: unknown) => fetch(`${base}${path}`, {
    method: payload === undefined ? "GET" : "POST",
    headers: { authorization: `Bearer ${env.AGENT_GATEWAY_TOKEN}`, "X-Agent-User": "offline-user", "Content-Type": "application/json" },
    body: payload === undefined ? undefined : JSON.stringify(payload), signal: AbortSignal.timeout(5000),
  });
  try {
    let ready = false;
    for (let attempt = 0; attempt < 100; attempt++) {
      if (child.exitCode !== null) throw new Error(`Offline Gateway stopped: ${logs}`);
      try { ready = (await call("/health")).ok; } catch { /* wait for our child */ }
      if (ready) break;
      await delay(100);
    }
    assert.ok(ready, "offline Gateway must start");
    for (const taskType of ["generate_storyboard", "rewrite_storyboard_shot"]) {
      const session = await call("/agent/sessions", { projectId: "offline-project", screenplayId: "offline-script", taskType,
        executionToken: "offline-execution-token", applicationRunId: `blocked-${taskType}`, applicationSessionId: `session-${taskType}` });
      assert.equal(session.status, 201, "quota rejection belongs to a terminal run, not session HTTP 500");
      const { sessionId } = await session.json() as { sessionId: string };
      const created = await call("/agent/chat", { sessionId, message: "离线次数上限测试", clientRequestId: taskType });
      assert.equal(created.status, 202);
      const { runId } = await created.json() as { runId: string };
      const events = await (await call(`/agent/runs/${runId}/events`)).text();
      assert.match(events, /event: run.failed/);
      assert.match(events, /"code":"business_run_limit"/);
      const ledger = await (await call(`/agent/runs/${runId}/ledger`)).json() as { providerRequests: unknown[] };
      assert.deepEqual(ledger.providerRequests, []);
    }
  } finally {
    const exited = new Promise<void>(done => child.once("exit", () => done()));
    if (child.exitCode === null) { child.kill(); await exited; }
  }
  // Killing the test Gateway leaves a dead PID lock; recovery must succeed
  // without clearing existing counts or reserved charges.
  const afterCrash = controller();
  assert.equal(afterCrash.totals().businessRuns, 3);
  assert.equal(afterCrash.totals().chargedUsd, beforeRestart.chargedUsd + interrupted.reservedCostUsd);
  afterCrash.close();
  console.log("PASS: independent workbench configuration, durable usage/runs, pending reservation recovery, writer lock, corrupt/mismatched ledger refusal, generate/rewrite offline USD rejection and terminal quota SSE; zero external requests");
} finally {
  for (const budget of controllers) budget.close();
  assert.ok(resolve(directory).startsWith(`${resolve(tmpdir())}${sep}`));
  rmSync(directory, { recursive: true, force: true });
}
