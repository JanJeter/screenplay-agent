import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { BudgetController, liveBudgetConfig } from "../src/budget.ts";
import { selectLiveModel } from "../src/live-provider.ts";
import { createRuntime, hasVerifiedSavedStoryboardResult } from "../src/runtime.ts";
import { parseSavedStoryboardAcknowledgement } from "../src/storyboard-schema.ts";

// Exercise the real DeepSeek/OpenAI SDK adapter, but replace all HTTP transport
// in this process. No request (including Java calls) leaves this process.
const env = {
  AGENT_MODE: "live", PI_PROVIDER: "deepseek", PI_MODEL: "deepseek-flash",
  DEEPSEEK_API_KEY: "offline-contract-key", JAVA_MODE: "http",
  JAVA_BASE_URL: "http://java-offline.invalid", AGENT_GATEWAY_TOKEN: "offline-contract-gateway-token",
  SB12_INPUT_TOKEN_RESERVE: "100000", SB12_PROVIDER_INPUT_OVERHEAD_TOKENS: "1000",
  PI_INPUT_USD_PER_MTOK: "0.3", PI_OUTPUT_USD_PER_MTOK: "1.2",
  PI_CACHE_READ_USD_PER_MTOK: "0.006", PI_CACHE_WRITE_USD_PER_MTOK: "0", PI_CACHE_WRITE_1H_USD_PER_MTOK: "0",
};
const fixture = async (name: string): Promise<unknown> => JSON.parse(await readFile(
  new URL(`../../../docs/storyboard-mvp/fixtures/${name}`, import.meta.url), "utf8",
));
const [context, result, saved] = await Promise.all([
  fixture("internal-context-generate.json"), fixture("model-result-6-shots.json"),
  fixture("internal-result-response-storyboard.json"),
]);
const originalFetch = globalThis.fetch;
const originalEnv = Object.fromEntries(Object.keys(env).map(name => [name, process.env[name]]));
Object.assign(process.env, env);

let phase: "success" | "http-error" | "missing-usage" = "success";
let providerRequests = 0;
let saves = 0;
const captured: Record<string, unknown>[] = [];
const events: { type: string; data: Record<string, unknown> }[] = [];

function streamResponse(sequence: number, tool?: { name: string; arguments: string }): Response {
  const frame = (delta: unknown, finishReason: string | null, includeUsage = false) => ({
    id: `offline-completion-${sequence}`, object: "chat.completion.chunk", created: 1,
    model: "deepseek-flash", choices: [{ index: 0, delta, finish_reason: finishReason }],
    ...(includeUsage ? { usage: { prompt_tokens: 100, completion_tokens: 20, prompt_cache_hit_tokens: 40, total_tokens: 120 } } : {}),
  });
  const split = tool ? Math.floor(tool.arguments.length / 2) : 0;
  const frames = tool ? [
    frame({ role: "assistant", tool_calls: [{ index: 0, id: `call_${sequence}`, type: "function",
      function: { name: tool.name, arguments: tool.arguments.slice(0, split) } }] }, null),
    frame({ tool_calls: [{ index: 0, function: { arguments: tool.arguments.slice(split) } }] }, null),
  ] : [frame({ role: "assistant", content: JSON.stringify(saved) }, null)];
  frames.push(frame({}, tool ? "tool_calls" : "stop", phase !== "missing-usage"));
  return new Response(`${frames.map(chunk => `data: ${JSON.stringify(chunk)}\n\n`).join("")}data: [DONE]\n\n`, {
    headers: { "Content-Type": "text/event-stream", "x-request-id": `offline-request-${sequence}` },
  });
}

globalThis.fetch = async (input, init) => {
  const request = new Request(input, init);
  const url = new URL(request.url);
  if (url.origin === "https://api.deepseek.com" && url.pathname === "/chat/completions") {
    assert.equal(request.headers.get("authorization"), "Bearer offline-contract-key");
    captured.push(JSON.parse(await request.text()) as Record<string, unknown>);
    const sequence = ++providerRequests;
    if (phase === "http-error") return new Response(JSON.stringify({ error: { message: "offline failure" } }), {
      status: 500, headers: { "Content-Type": "application/json" },
    });
    if (phase === "missing-usage") return streamResponse(sequence);
    if (sequence === 1) return streamResponse(sequence, { name: "get_storyboard_context", arguments: "{}" });
    if (sequence === 2) return streamResponse(sequence, { name: "save_storyboard_result", arguments: JSON.stringify({ result }) });
    assert.equal(sequence, 3, "the successful run must complete after the save receipt");
    return streamResponse(sequence);
  }
  assert.equal(url.origin, "http://java-offline.invalid", "unexpected network target is blocked");
  assert.equal(request.headers.get("authorization"), "Bearer offline-execution-token");
  if (url.pathname.endsWith("/storyboard-context")) return Response.json(context);
  assert.ok(url.pathname.endsWith("/storyboard-result"));
  assert.deepEqual(JSON.parse(await request.text()), { result });
  saves++;
  return Response.json(saved);
};

function setup(runId: string) {
  const budget = new BudgetController(liveBudgetConfig(env));
  budget.beginBusinessRun(runId);
  const runtime = createRuntime({
    userId: "offline-user", projectId: "offline-project", screenplayId: "offline-script", sessionId: runId,
    applicationRunId: runId, executionToken: "offline-execution-token", taskType: "generate_storyboard",
  }, (type, data) => events.push({ type, data }), budget);
  runtime.prepareRun();
  return { runtime, budget };
}

try {
  const selected = selectLiveModel(env);
  assert.equal(selected.model.id, "deepseek-flash");
  assert.equal(selected.model.provider, "deepseek");
  assert.equal(selected.model.baseUrl, "https://api.deepseek.com");
  assert.equal(selectLiveModel({ PI_MODEL: "claude-haiku-4-5-20251001" }).model.provider, "anthropic");
  assert.throws(() => selectLiveModel({ ...env, PI_MODEL: "unknown-model" }));
  assert.throws(() => selectLiveModel({ ...env, PI_PROVIDER: "unknown-provider" }));

  const success = setup("offline-success");
  await success.runtime.agent.prompt("生成冻结场景分镜并保存。");
  assert.equal(success.runtime.hasValidStructuredResult(), true);
  assert.equal(saves, 1);
  assert.equal(providerRequests, 3);
  // A completed storyboard is the Java-validated successful save, not a
  // second model-authored copy of the receipt. The final text can be prose or
  // a malformed acknowledgement without invalidating the persisted artifact.
  const completedMessages = success.runtime.agent.state.messages.slice();
  const finalMessage = completedMessages.at(-1);
  assert.ok(finalMessage?.role === "assistant");
  const beforeAcknowledgement = completedMessages.slice(0, -1);
  const receipt = JSON.stringify(saved);
  const fence = (body: string) => `\`\`\`json\n${body}\n\`\`\``;
  const completeWithFinalText = (text: string) => {
    success.runtime.agent.state.messages = [
      ...beforeAcknowledgement, { ...finalMessage, content: [{ type: "text", text }] },
    ];
    return success.runtime.hasValidStructuredResult();
  };
  for (const text of [receipt, fence(receipt), `Saved successfully.\n${fence(receipt)}`,
    `${fence(receipt)}\nSaved successfully.`, "已保存联板（4 个镜头）。"]) {
    assert.equal(completeWithFinalText(text), true, "final prose must not invalidate a Java-validated save");
  }
  assert.throws(() => parseSavedStoryboardAcknowledgement(`Saved successfully.\n${fence(receipt)}`),
    "receipt parsing remains strict for diagnostics and does not extract JSON from prose");
  assert.doesNotThrow(() => parseSavedStoryboardAcknowledgement(fence(receipt)));
  assert.equal(completeWithFinalText(fence(receipt)), true);
  const acknowledgedMessages = success.runtime.agent.state.messages.slice();
  success.runtime.agent.state.messages = acknowledgedMessages.filter(message =>
    message.role !== "toolResult" || message.toolName !== "save_storyboard_result");
  assert.equal(success.runtime.hasValidStructuredResult(), false, "a receipt without an actual save tool result must fail");
  success.runtime.agent.state.messages = acknowledgedMessages.map(message =>
    message.role === "toolResult" && message.toolName === "save_storyboard_result"
      ? { ...message, isError: true } : message);
  assert.equal(success.runtime.hasValidStructuredResult(), false, "a failed save must not be acknowledged");
  assert.equal(hasVerifiedSavedStoryboardResult(success.runtime.agent.state.messages), false,
    "the exported completion predicate must reject an errored save tool result");
  success.runtime.agent.state.messages = completedMessages;
  for (const body of captured) {
    assert.equal(body.model, "deepseek-flash");
    assert.equal(body.stream, true);
    assert.deepEqual(body.thinking, { type: "disabled" });
    assert.deepEqual(body.stream_options, { include_usage: true });
    const tools = body.tools as { function: { name: string; parameters: { properties: Record<string, unknown> } } }[];
    assert.ok(tools.find(tool => tool.function.name === "save_storyboard_result")?.function.parameters.properties.result);
  }
  assert.ok((captured[1].messages as { role: string }[]).some(message => message.role === "tool"));
  const ledger = success.runtime.getProviderLedger();
  assert.equal(ledger.length, 3);
  for (const [index, entry] of ledger.entries()) {
    assert.equal(entry.status, "completed");
    assert.equal(entry.requestId, `offline-request-${index + 1}`);
    assert.deepEqual(entry.retryPolicy, { maxRetries: 0, retries: 0 });
    assert.deepEqual(entry.actualUsage, { input: 60, output: 20, cacheRead: 40, cacheWrite: 0, totalTokens: 120 });
    assert.ok(Math.abs(entry.actualCostUsd! - 0.00004224) < 1e-12);
  }
  assert.equal(success.budget.totals().reservedUsd, 0);

  phase = "http-error";
  const failure = setup("offline-failure");
  const beforeFailure = providerRequests;
  await failure.runtime.agent.prompt("触发本地模拟错误。");
  assert.equal(providerRequests - beforeFailure, 1, "the SDK must not retry a 500 response");
  const failedEntry = failure.runtime.getProviderLedger()[0];
  assert.equal(failedEntry.status, "failed");
  assert.equal(failedEntry.chargedCostUsd, failedEntry.reservedCostUsd);

  phase = "missing-usage";
  const unknownUsage = setup("offline-missing-usage");
  const eventStart = events.length;
  await unknownUsage.runtime.agent.prompt("触发本地模拟用量缺失。");
  const unknownEntry = unknownUsage.runtime.getProviderLedger()[0];
  assert.equal(unknownEntry.status, "failed");
  assert.equal(unknownEntry.actualCostUsd, undefined);
  assert.equal(unknownEntry.chargedCostUsd, unknownEntry.reservedCostUsd);
  assert.ok(events.slice(eventStart).some(event => event.type === "provider.request_failed"));
  assert.ok(!events.slice(eventStart).some(event => event.type === "provider.request_completed"));
  console.log("PASS: offline DeepSeek SDK tool loop, Java-validated save completion gate, strict diagnostic receipt parsing, non-thinking payload, cache usage, request ledger, zero retries, missing-usage reservation; no external HTTP requests");
} finally {
  globalThis.fetch = originalFetch;
  for (const [name, value] of Object.entries(originalEnv)) {
    if (value === undefined) delete process.env[name]; else process.env[name] = value;
  }
}
