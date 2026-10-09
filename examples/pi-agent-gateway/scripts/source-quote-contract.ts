import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import { validateToolArguments } from "@earendil-works/pi-ai";
import { BudgetController, liveBudgetConfig } from "../src/budget.ts";
import { createRuntime } from "../src/runtime.ts";
import { modelStoryboardContext, resolveModelStoryboardResult, saveStoryboardResultParameters, sourceQuoteChoices,
  StoryboardSourceQuoteError, validateStoryboardContext, validateStoryboardResult, type StoryboardContext } from "../src/storyboard-schema.ts";

const fixture = async (name: string) => JSON.parse(await readFile(new URL(`../../../docs/storyboard-mvp/fixtures/${name}`, import.meta.url), "utf8"));
const generate = validateStoryboardContext(await fixture("internal-context-generate.json"));
const rewrite = validateStoryboardContext(await fixture("internal-context-rewrite.json"));
const generation = await fixture("model-result-6-shots.json");
const replacement = await fixture("model-result-rewrite.json");
assert.equal(generate.mode, "generate");
assert.equal(rewrite.mode, "rewrite");

function withIds(result: any, context: StoryboardContext): any {
  const choices = sourceQuoteChoices(context.sourceSnapshot);
  const convert = (shot: Record<string, unknown>, index: number) => {
    const { sourceQuote: _quote, ...fields } = shot;
    return { ...fields, sourceQuoteId: choices[index % choices.length].id };
  };
  return result.mode === "generate" ? { ...result, shots: result.shots.map(convert) }
    : { ...result, proposalShot: convert(result.proposalShot, 0) };
}

function verifySource(context: StoryboardContext): void {
  const choices = sourceQuoteChoices(context.sourceSnapshot);
  assert.ok(choices.length > 0 && choices.length <= 101);
  for (const [index, choice] of choices.entries()) {
    assert.equal(choice.text, context.sourceSnapshot.sceneText.slice(choice.start, choice.end));
    assert.ok(choice.text.length > 0 && choice.text.length <= 240);
    assert.ok(!/[\uD800-\uDBFF]$/.test(choice.text) && !/^[\uDC00-\uDFFF]/.test(choice.text));
    if (index > 0) assert.ok(choices[index - 1].end <= choice.start);
  }
  assert.equal(choices.map(choice => choice.text).join("").replace(/\s/g, ""), context.sourceSnapshot.sceneText.replace(/\s/g, ""), "every non-whitespace source character is represented once");
  const modelContext = modelStoryboardContext(context);
  assert.ok(!("sceneText" in modelContext.sourceSnapshot), "send the source text only once");
  assert.equal(modelContext.sourceSnapshot.sceneHash, context.sourceSnapshot.sceneHash);
  const result = context.mode === "generate" ? { ...generation, shots: Array.from({ length: context.targetShotCount }, (_, index) => generation.shots[index % generation.shots.length]) } : replacement;
  const byId = withIds(result, context);
  validateToolArguments({ name: "save_storyboard_result", description: "offline", parameters: saveStoryboardResultParameters }, { type: "toolCall", id: "offline", name: "save_storyboard_result", arguments: { result: byId } });
  const resolved = resolveModelStoryboardResult(byId, context);
  assert.doesNotThrow(() => validateStoryboardResult(resolved, context));
  assert.ok(!JSON.stringify(resolved).includes("sourceQuoteId"), "Java must not receive model-only IDs");
  const shots = resolved.mode === "generate" ? resolved.shots : [resolved.proposalShot];
  for (const [index, shot] of shots.entries()) assert.equal(shot.sourceQuote, choices[index % choices.length].text);
}

for (const count of [4, 6, 8]) verifySource({ ...generate, targetShotCount: count });
verifySource(rewrite);
assert.doesNotThrow(() => resolveModelStoryboardResult(generation, generate), "legacy exact text remains supported");
const formatted = "## 第一幕\n**人物：** 她拿起信，停顿。\n\n> 原文保留 Markdown 与标点。😀\n".repeat(150).slice(0, 7900);
verifySource({ ...generate, sourceSnapshot: { ...generate.sourceSnapshot, sceneText: formatted, sceneHash: createHash("sha256").update(formatted).digest("hex") } });
const chinese = "场".repeat(8000);
const longContext = { ...generate, sourceSnapshot: { ...generate.sourceSnapshot, sceneText: chinese, sceneHash: createHash("sha256").update(chinese).digest("hex") } };
verifySource(longContext);
assert.ok(Buffer.byteLength(JSON.stringify(modelStoryboardContext(longContext)), "utf8") < 40000, "8k Chinese context must not duplicate the raw source");
const byId = withIds(generation, generate);
for (const change of [{ sourceQuoteId: "q_missing" }, { sourceQuoteId: byId.shots[0].sourceQuoteId, sourceQuote: "重写或拼接" }, { sourceQuoteId: 123 }]) {
  assert.throws(() => resolveModelStoryboardResult({ ...byId, shots: [{ ...byId.shots[0], ...change }, ...byId.shots.slice(1)] }, generate), StoryboardSourceQuoteError);
}
assert.throws(() => resolveModelStoryboardResult({ ...generation, shots: [{ ...generation.shots[0], sourceQuote: "模型漏掉Markdown并拼接重写" }, ...generation.shots.slice(1)] }, generate), StoryboardSourceQuoteError);

// Private replay is opt-in: never print or persist source text or make HTTP calls.
if (process.env.SOURCE_QUOTE_CONTEXT_PATH) {
  const context = validateStoryboardContext(JSON.parse(await readFile(process.env.SOURCE_QUOTE_CONTEXT_PATH, "utf8")));
  verifySource(context);
  console.log(`PASS: private frozen source replay (${context.sourceSnapshot.sceneText.length} characters, ${sourceQuoteChoices(context.sourceSnapshot).length} exact choices)`);
}

// Real Pi/DeepSeek SDK code with all HTTP intercepted within this process.
const env = {
  AGENT_MODE: "live", PI_PROVIDER: "deepseek", PI_MODEL: "deepseek-flash", DEEPSEEK_API_KEY: "offline-contract-key",
  JAVA_MODE: "http", JAVA_BASE_URL: "http://java-offline.invalid", AGENT_GATEWAY_TOKEN: "offline-contract-gateway-token",
  SB12_INPUT_TOKEN_RESERVE: "100000", SB12_PROVIDER_INPUT_OVERHEAD_TOKENS: "1000",
  PI_INPUT_USD_PER_MTOK: "0.3", PI_OUTPUT_USD_PER_MTOK: "1.2", PI_CACHE_READ_USD_PER_MTOK: "0.006",
  PI_CACHE_WRITE_USD_PER_MTOK: "0", PI_CACHE_WRITE_1H_USD_PER_MTOK: "0",
};
const originalFetch = globalThis.fetch;
const previous = Object.fromEntries(Object.keys(env).map(name => [name, process.env[name]]));
Object.assign(process.env, env);
try {
  for (const scenario of ["generate-id", "rewrite-id", "invalid-id", "invalid-text", "conflicting-quote", "invalid-shape", "save-failed"] as const) {
    const context = scenario === "rewrite-id" ? rewrite : generate;
    const result = withIds(scenario === "rewrite-id" ? replacement : generation, context);
    if (scenario === "invalid-id") result.shots[0].sourceQuoteId = "q_missing";
    if (scenario === "invalid-text") { delete result.shots[0].sourceQuoteId; result.shots[0].sourceQuote = "改写拼接不属于原文"; }
    if (scenario === "conflicting-quote") result.shots[0].sourceQuote = "与所选片段不同";
    if (scenario === "invalid-shape") result.shots[0].cameraMovement = "INVALID";
    const saved = await fixture(context.mode === "generate" ? "internal-result-response-storyboard.json" : "internal-result-response-proposal.json");
    let requests = 0;
    let saves = 0;
    globalThis.fetch = async (input, init) => {
      const request = new Request(input, init);
      const url = new URL(request.url);
      if (url.origin === "https://api.deepseek.com" && url.pathname === "/chat/completions") {
        requests++;
        assert.ok(requests <= 3, "no unbounded self-repair");
        const tool = requests === 1 ? { name: "get_storyboard_context", arguments: "{}" }
          : requests === 2 ? { name: "save_storyboard_result", arguments: JSON.stringify({ result }) } : undefined;
        const delta = tool ? { role: "assistant", tool_calls: [{ index: 0, id: `call-${requests}`, type: "function", function: tool }] }
          : { role: "assistant", content: JSON.stringify(saved) };
        const frame = (message: unknown, finish: string | null, usage = false) => ({ id: `offline-${requests}`, object: "chat.completion.chunk", created: 1,
          model: "deepseek-flash", choices: [{ index: 0, delta: message, finish_reason: finish }],
          ...(usage ? { usage: { prompt_tokens: 100, completion_tokens: 20, prompt_cache_hit_tokens: 40, total_tokens: 120 } } : {}) });
        return new Response([frame(delta, null), frame({}, tool ? "tool_calls" : "stop", true)].map(value => `data: ${JSON.stringify(value)}\n\n`).join("") + "data: [DONE]\n\n", {
          headers: { "Content-Type": "text/event-stream", "x-request-id": `offline-${scenario}-${requests}` },
        });
      }
      assert.equal(url.origin, "http://java-offline.invalid", "no external network is permitted");
      if (url.pathname.endsWith("/storyboard-context")) return Response.json(context);
      assert.ok(url.pathname.endsWith("/storyboard-result"));
      saves++;
      const posted = await request.json() as { result: unknown };
      assert.ok(!JSON.stringify(posted).includes("sourceQuoteId"));
      assert.doesNotThrow(() => validateStoryboardResult(posted.result, context));
      return scenario === "save-failed" ? new Response("offline failure", { status: 500 }) : Response.json(saved);
    };
    const budget = new BudgetController(liveBudgetConfig(env));
    budget.beginBusinessRun(scenario);
    const events: { type: string; data: Record<string, unknown> }[] = [];
    const runtime = createRuntime({ userId: "offline", projectId: "offline", screenplayId: "offline", sessionId: scenario,
      applicationRunId: scenario, executionToken: "offline", taskType: context.mode === "generate" ? "generate_storyboard" : "rewrite_storyboard_shot" },
    (type, data) => events.push({ type, data }), budget);
    runtime.prepareRun();
    await runtime.agent.prompt("离线原文引用测试");
    runtime.finishRun();
    const success = scenario === "generate-id" || scenario === "rewrite-id";
    assert.equal(runtime.hasValidStructuredResult(), success);
    assert.equal(requests, success ? 3 : 2, "first failed save must stop before a third provider request");
    assert.equal(saves, success || scenario === "save-failed" ? 1 : 0, "invalid payload must never reach Java");
    const expectedCode = success ? undefined : scenario === "invalid-shape" ? "storyboard_result_invalid"
      : scenario === "save-failed" ? "storyboard_save_failed" : "storyboard_source_quote_invalid";
    assert.equal(runtime.getStoryboardFailure(), expectedCode);
    if (expectedCode) assert.ok(events.some(event => event.type === "tool.completed" && event.data.code === expectedCode));
    assert.equal(budget.totals().businessRuns, 1);
    assert.equal(budget.totals().reservedUsd, 0);
    for (const entry of runtime.getProviderLedger()) assert.deepEqual(entry.retryPolicy, { maxRetries: 0, retries: 0 });
  }
} finally {
  globalThis.fetch = originalFetch;
  for (const [name, value] of Object.entries(previous)) { if (value === undefined) delete process.env[name]; else process.env[name] = value; }
}
console.log("PASS: exact source IDs, Markdown/Unicode, bounded context, 4/6/8 and rewrite mapping, strict legacy quotes, invalid ID/text/schema refusal, save failure codes, first-failure abort, zero external HTTP");
