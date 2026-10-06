import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { createServer, type IncomingMessage, type ServerResponse } from "node:http";
import { readFile } from "node:fs/promises";
import { createHash, randomUUID } from "node:crypto";
import type { AddressInfo } from "node:net";
import { setTimeout as delay } from "node:timers/promises";
import { fileURLToPath } from "node:url";
import { stream as streamAnthropic } from "@earendil-works/pi-ai/api/anthropic-messages";
import type { Context, Model } from "@earendil-works/pi-ai";
import { createStoryboardTools } from "../src/storyboard-tools.ts";
import { requestJavaAdapter } from "../src/tools.ts";
import {
  hasEquivalentSavedStoryboardAcknowledgement,
  validateSavedStoryboardResult,
  validateStoryboardContext,
  validateStoryboardResult,
  type StoryboardContext,
} from "../src/storyboard-schema.ts";

const cwd = fileURLToPath(new URL("../", import.meta.url));
const fixtureRoot = fileURLToPath(new URL("../../../docs/storyboard-mvp/fixtures/", import.meta.url));
const gatewayPort = 31000 + Math.floor(Math.random() * 10000);
const adapterPort = gatewayPort + 10000;
const gatewayToken = randomUUID();
const executionToken = randomUUID();
const gatewayBase = `http://127.0.0.1:${gatewayPort}`;
const adapterBase = `http://127.0.0.1:${adapterPort}`;

async function fixture(name: string): Promise<unknown> {
  return JSON.parse(await readFile(new URL(name, `file:///${fixtureRoot.replace(/\\/g, "/")}/`), "utf8")) as unknown;
}

function json(res: ServerResponse, status: number, value: unknown): void {
  res.writeHead(status, { "Content-Type": "application/json; charset=utf-8" });
  res.end(JSON.stringify(value));
}

async function readJson(req: IncomingMessage): Promise<unknown> {
  const chunks: Buffer[] = [];
  for await (const chunk of req) chunks.push(Buffer.from(chunk));
  return JSON.parse(Buffer.concat(chunks).toString("utf8")) as unknown;
}

function gatewayCall(path: string, data?: unknown): Promise<Response> {
  return fetch(`${gatewayBase}${path}`, {
    method: data === undefined ? "GET" : "POST",
    headers: { Authorization: `Bearer ${gatewayToken}`, "X-Agent-User": "contract-user", "Content-Type": "application/json" },
    body: data === undefined ? undefined : JSON.stringify(data), signal: AbortSignal.timeout(20_000),
  });
}

async function captureProviderToolSchema(): Promise<Record<string, unknown>> {
  let captured: Record<string, unknown> | undefined;
  const provider = createServer((req, res) => {
    void readJson(req).then(value => {
      captured = value as Record<string, unknown>;
      res.writeHead(200, { "Content-Type": "text/event-stream" });
      res.end();
    }).catch(() => {
      res.writeHead(400);
      res.end();
    });
  });
  await new Promise<void>(resolve => provider.listen(0, "127.0.0.1", resolve));
  const { port } = provider.address() as AddressInfo;
  const model: Model<"anthropic-messages"> = {
    id: "schema-capture", name: "Schema capture", api: "anthropic-messages", provider: "schema-capture",
    baseUrl: `http://127.0.0.1:${port}`, reasoning: false, input: ["text"],
    cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 }, contextWindow: 16_384, maxTokens: 1_024,
  };
  const context: Context = {
    messages: [{ role: "user", content: "capture the registered tool schema", timestamp: Date.now() }],
    tools: createStoryboardTools({
      userId: "schema-user", projectId: "schema-project", screenplayId: "schema-script", sessionId: "schema-session",
      applicationRunId: "schema-run", taskType: "generate_storyboard",
    }),
  };
  try {
    const stream = streamAnthropic(model, context, { apiKey: "schema-capture-key", cacheRetention: "none" });
    for await (const event of stream) {
      if (event.type === "done" || event.type === "error") break;
    }
  } finally {
    await new Promise<void>((resolve, reject) => provider.close(error => error ? reject(error) : resolve()));
  }
  assert.ok(captured, "Anthropic-compatible provider must receive a request");
  return captured;
}

function assertModelVisibleStoryboardSchema(payload: Record<string, unknown>): void {
  assert.ok(Array.isArray(payload.tools), "provider request must include tools");
  const saveTool = payload.tools.find(tool => tool && typeof tool === "object"
    && (tool as Record<string, unknown>).name === "save_storyboard_result") as Record<string, unknown> | undefined;
  assert.ok(saveTool, "provider request must include save_storyboard_result");
  const inputSchema = saveTool.input_schema as Record<string, unknown>;
  assert.deepEqual(inputSchema.required, ["result"]);
  const result = (inputSchema.properties as Record<string, unknown>).result as Record<string, unknown>;
  assert.ok(Array.isArray(result.anyOf), "result must expose generate and rewrite alternatives");
  const variants = result.anyOf as Record<string, unknown>[];
  const generate = variants.find(variant => (variant.properties as Record<string, Record<string, unknown>>).mode?.const === "generate");
  const rewrite = variants.find(variant => (variant.properties as Record<string, Record<string, unknown>>).mode?.const === "rewrite");
  assert.ok(generate && rewrite, "both result modes must be model-visible");
  assert.equal(generate.additionalProperties, false);
  assert.equal(rewrite.additionalProperties, false);
  assert.deepEqual(generate.required, ["mode", "shots"]);
  assert.deepEqual(rewrite.required, ["mode", "proposalShot"]);
  const shots = (generate.properties as Record<string, Record<string, unknown>>).shots;
  assert.equal(shots.minItems, 4);
  assert.equal(shots.maxItems, 8);
  const editableShot = shots.items as Record<string, unknown>;
  assert.equal(editableShot.additionalProperties, false);
  assert.deepEqual(editableShot.required, ["shotSize", "cameraMovement", "visualDescription", "dialogue", "sound", "durationSeconds", "imagePrompt", "videoPrompt", "sourceQuote"]);
  const fields = editableShot.properties as Record<string, Record<string, unknown>>;
  assert.equal(fields.durationSeconds.minimum, 1);
  assert.equal(fields.durationSeconds.maximum, 30);
  assert.equal(fields.visualDescription.maxLength, 2_000);
  assert.equal(fields.dialogue.maxLength, 1_200);
  assert.equal(fields.sound.maxLength, 1_200);
  assert.equal(fields.imagePrompt.maxLength, 2_000);
  assert.equal(fields.videoPrompt.maxLength, 2_500);
  assert.equal(fields.sourceQuote.maxLength, 500);
  assert.deepEqual(fields.shotSize.enum, ["ESTABLISHING", "WIDE", "MEDIUM", "CLOSE_UP", "EXTREME_CLOSE_UP"]);
  assert.deepEqual(fields.cameraMovement.enum, ["STATIC", "PAN", "TILT", "DOLLY_IN", "DOLLY_OUT", "TRACK", "HANDHELD"]);
}

async function object(response: Response): Promise<Record<string, unknown>> {
  assert.ok(response.ok, `HTTP ${response.status}: ${await response.clone().text()}`);
  return await response.json() as Record<string, unknown>;
}

async function waitForGateway(): Promise<void> {
  for (let attempt = 0; attempt < 100; attempt++) {
    try { if ((await fetch(`${gatewayBase}/health`, { signal: AbortSignal.timeout(200) })).ok) return; } catch { /* starting */ }
    await delay(100);
  }
  throw new Error("Gateway did not start");
}

function oversizedResult(context: StoryboardContext): unknown {
  assert.equal(context.mode, "generate");
  const sourceQuote = context.sourceSnapshot.sceneText.slice(0, 500);
  const long = "画".repeat(2_000);
  return {
    mode: "generate",
    shots: Array.from({ length: 8 }, () => ({
      shotSize: "MEDIUM", cameraMovement: "STATIC", visualDescription: long,
      dialogue: "声".repeat(1_200), sound: "音".repeat(1_200), durationSeconds: 30,
      imagePrompt: long, videoPrompt: "动".repeat(2_500), sourceQuote,
    })),
  };
}

function snapshotWithSceneText(sceneText: string) {
  return {
    scriptId: "script_101", scriptRevision: 1, sourceSceneId: "scene_12", sceneNo: 1,
    heading: "INT. 容量验证 - 夜", sceneText,
    sceneHash: createHash("sha256").update(sceneText, "utf8").digest("hex"),
  };
}

function capacityContexts(): { chineseGenerate: StoryboardContext; escapedGenerate: StoryboardContext; escapedRewrite: StoryboardContext } {
  const chineseScene = `中文锚点。${"字".repeat(7_995)}`;
  const chineseGenerate = validateStoryboardContext({
    mode: "generate", targetShotCount: 4, instructions: "保留中文容量边界。",
    sourceSnapshot: snapshotWithSceneText(chineseScene),
  });
  const escapedScene = `转义锚点${"\u0000".repeat(7_996)}`;
  const escapedGenerate = validateStoryboardContext({
    mode: "generate", targetShotCount: 4, instructions: "保留 JSON 转义容量边界。",
    sourceSnapshot: snapshotWithSceneText(escapedScene),
  });
  const sourceQuote = escapedScene.slice(0, 500);
  const maximallyEscapedShot = {
    shotSize: "MEDIUM", cameraMovement: "STATIC",
    visualDescription: "\u0000".repeat(2_000), dialogue: "\u0000".repeat(1_200), sound: "\u0000".repeat(1_200),
    durationSeconds: 12, imagePrompt: "\u0000".repeat(2_000), videoPrompt: "\u0000".repeat(2_500), sourceQuote,
  };
  const escapedRewrite = validateStoryboardContext({
    mode: "rewrite", instruction: "验证目标镜与相邻镜的完整上下文容量。", storyboardId: "sb_capacity_001", targetShotId: "shot_capacity_001",
    baseStoryboardRevision: 1, sourceSnapshot: snapshotWithSceneText(escapedScene), targetShot: maximallyEscapedShot,
    previousShot: maximallyEscapedShot, nextShot: maximallyEscapedShot,
  });
  return { chineseGenerate, escapedGenerate, escapedRewrite };
}

const postedResults = new Map<string, unknown>();
const contexts = new Map<string, StoryboardContext>();
const adapter = createServer((req, res) => {
  void (async () => {
    if (req.headers.authorization !== `Bearer ${executionToken}`) { json(res, 401, { error: "bad token" }); return; }
    const match = /^\/internal\/agent\/runs\/([A-Za-z0-9._:-]+)\/(storyboard-context|storyboard-result)$/.exec(req.url ?? "");
    if (!match) { json(res, 404, { error: "not found" }); return; }
    const [, runId, operation] = match;
    if (operation === "storyboard-context" && req.method === "GET") {
      const context = contexts.get(runId);
      if (!context) { json(res, 404, { error: "unknown run" }); return; }
      json(res, 200, context); return;
    }
    if (operation === "storyboard-result" && req.method === "POST") {
      const payload = await readJson(req);
      postedResults.set(runId, payload);
      if (runId === "run-save-failure") { json(res, 500, { error: "storage failed" }); return; }
      const context = contexts.get(runId);
      if (!context) { json(res, 404, { error: "unknown run" }); return; }
      json(res, 200, context.mode === "generate"
        ? { artifactId: "900001", resultRef: { type: "storyboard", id: "900001", storyboardId: "900001" } }
        : { artifactId: "900002", resultRef: { type: "shot_proposal", id: "900002", storyboardId: context.storyboardId } });
      return;
    }
    json(res, 405, { error: "method not allowed" });
  })().catch(() => json(res, 500, { error: "adapter error" }));
});

await new Promise<void>((resolve) => adapter.listen(adapterPort, "127.0.0.1", resolve));
const child = spawn(process.execPath, ["../../pi-agent/node_modules/tsx/dist/cli.mjs", "--tsconfig", "tsconfig.json", "src/server.ts"], {
  cwd,
  env: { ...process.env, PORT: String(gatewayPort), AGENT_GATEWAY_TOKEN: gatewayToken, AGENT_MODE: "mock", JAVA_MODE: "http", JAVA_BASE_URL: adapterBase },
  stdio: ["ignore", "pipe", "pipe"],
});
let gatewayLog = "";
child.stdout.on("data", data => { gatewayLog += String(data); });
child.stderr.on("data", data => { gatewayLog += String(data); });

try {
  const generateContext = validateStoryboardContext(await fixture("internal-context-generate.json"));
  assert.equal(generateContext.mode, "generate");
  assertModelVisibleStoryboardSchema(await captureProviderToolSchema());
  for (const count of [4, 6, 8]) {
    const result = await fixture(`model-result-${count}-shots.json`);
    assert.doesNotThrow(() => validateStoryboardResult(result, { ...generateContext, targetShotCount: count }));
  }
  const truncatedOutput = await fixture("model-result-6-shots.json") as { mode: string; shots: unknown[] };
  assert.throws(() => validateStoryboardResult({ ...truncatedOutput, shots: truncatedOutput.shots.slice(0, 3) }, generateContext), "truncated output must not be saved");
  const rewriteContext = validateStoryboardContext(await fixture("internal-context-rewrite.json"));
  assert.equal(rewriteContext.mode, "rewrite");
  const rewriteResult = await fixture("model-result-rewrite.json");
  assert.doesNotThrow(() => validateStoryboardResult(rewriteResult, rewriteContext));
  for (const name of ["invalid-enum.json", "invalid-wrong-shot-count.json", "invalid-missing-prompt.json", "invalid-source-quote-outside-snapshot.json"]) {
    const invalid = await fixture(name);
    assert.throws(() => validateStoryboardResult(invalid, generateContext), `${name} must be rejected`);
  }
  const { chineseGenerate, escapedGenerate, escapedRewrite } = capacityContexts();
  assert.ok(Buffer.byteLength(JSON.stringify(chineseGenerate), "utf8") > 16 * 1024, "long Chinese generation must exceed the former 16 KiB cap");
  assert.ok(Buffer.byteLength(JSON.stringify(escapedRewrite), "utf8") > 64 * 1024, "rewrite neighbours and JSON escaping must exercise the former intermediate cap");
  assert.ok(Buffer.byteLength(JSON.stringify(escapedRewrite), "utf8") <= 256 * 1024, "frozen R6 capacity must accept the worst-case rewrite context");
  assert.throws(() => validateStoryboardResult(oversizedResult(generateContext), { ...generateContext, targetShotCount: 8 }), "truncated/oversized output must be rejected");
  assert.throws(() => validateStoryboardResult({ mode: "rewrite", proposalShot: rewriteContext.targetShot, targetShotId: "other-shot" }, rewriteContext), "single-shot output must not carry a target ID");
  assert.doesNotThrow(() => validateSavedStoryboardResult({ artifactId: "900001", resultRef: { type: "storyboard", id: "900001", storyboardId: "900001" } }, generateContext));
  const savedReceipt = { artifactId: "900001", resultRef: { type: "storyboard", id: "900001", storyboardId: "900001" } };
  assert.equal(hasEquivalentSavedStoryboardAcknowledgement(
    { resultRef: { storyboardId: "900001", id: "900001", type: "storyboard" }, artifactId: "900001" }, savedReceipt,
  ), true, "outer and nested JSON key order must not affect a valid receipt");
  for (const invalidReceipt of [
    { artifactId: "other", resultRef: { type: "storyboard", id: "other", storyboardId: "other" } },
    { artifactId: "900001", resultRef: { type: "shot_proposal", id: "900001", storyboardId: "900001" } },
    { artifactId: "900001", resultRef: { type: "storyboard", id: "900001", storyboardId: "other" } },
    { artifactId: "900001", resultRef: { type: "storyboard", id: "900001" } },
    { artifactId: "900001", resultRef: { type: "storyboard", id: "900001", storyboardId: "900001" }, extra: true },
  ]) {
    assert.equal(hasEquivalentSavedStoryboardAcknowledgement(invalidReceipt, savedReceipt), false,
      "wrong or malformed save receipts must not be accepted");
  }

  contexts.set("run-generate-6", generateContext);
  contexts.set("run-rewrite", rewriteContext);
  contexts.set("run-capacity-chinese", chineseGenerate);
  contexts.set("run-capacity-escaped", escapedGenerate);
  contexts.set("run-capacity-rewrite", escapedRewrite);
  contexts.set("run-save-failure", generateContext);
  await waitForGateway();

  // Exercise the production HTTP body reader directly. The rewrite payload is
  // intentionally above its old 64 KiB intermediate limit, while remaining
  // under the frozen 256 KiB Java-to-Gateway context budget.
  const previousJavaBaseUrl = process.env.JAVA_BASE_URL;
  process.env.JAVA_BASE_URL = adapterBase;
  try {
    for (const runId of ["run-capacity-chinese", "run-capacity-escaped", "run-capacity-rewrite"]) {
      const received = await requestJavaAdapter({
        userId: "contract-user", projectId: "project_77", screenplayId: "script_101", sessionId: `capacity-${runId}`,
        executionToken,
      }, `/internal/agent/runs/${runId}/storyboard-context`, undefined, undefined, undefined, 256 * 1024);
      assert.doesNotThrow(() => validateStoryboardContext(received), `${runId} must pass the production HTTP reader and context validator`);
    }
  } finally {
    if (previousJavaBaseUrl === undefined) delete process.env.JAVA_BASE_URL;
    else process.env.JAVA_BASE_URL = previousJavaBaseUrl;
  }

  for (const runId of ["run-generate-6", "run-rewrite", "run-save-failure"]) {
    const taskType = runId === "run-rewrite" ? "rewrite_storyboard_shot" : "generate_storyboard";
    const session = await object(await gatewayCall("/agent/sessions", {
      projectId: "project_77", screenplayId: "script_101", taskType, executionToken,
      applicationRunId: runId, applicationSessionId: `session_${runId}`,
    }));
    const run = await object(await gatewayCall("/agent/chat", {
      sessionId: session.sessionId, message: "执行冻结的分镜任务", clientRequestId: randomUUID(),
    }));
    const events = await (await gatewayCall(`/agent/runs/${run.runId}/events`)).text();
    if (runId === "run-save-failure") {
      assert.match(events, /event: run.failed/);
      assert.doesNotMatch(events, /event: run.completed/);
    } else {
      assert.match(events, /event: run.completed/);
      const payload = postedResults.get(runId) as { result?: { shots?: unknown[]; mode?: string } } | undefined;
      assert.ok(payload?.result, `${runId} must submit a result`);
      if (runId === "run-generate-6") assert.equal(payload.result.shots?.length, 6);
      if (taskType === "rewrite_storyboard_shot") assert.equal(payload.result.mode, "rewrite");
    }
  }
  console.log("PASS: provider-visible result schema, semantic save receipts, R6 Chinese/escaped/rewrite capacity, storyboard 4/6/8, invalid/truncated output, Java numeric IDs, save failure, and rewrite target isolation");
} finally {
  child.kill();
  adapter.closeAllConnections();
  await new Promise<void>((resolve) => adapter.close(() => resolve()));
  if (child.exitCode !== null && child.exitCode !== 0) process.stderr.write(gatewayLog);
}
