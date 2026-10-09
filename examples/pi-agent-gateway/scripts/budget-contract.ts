import assert from "node:assert/strict";
import { BudgetController, BudgetExceededError, inspectLiveConfiguration, liveBudgetConfig } from "../src/budget.ts";

const env = {
  AGENT_MODE: "live", JAVA_MODE: "http", PI_MODEL: "test-model", ANTHROPIC_API_KEY: "not-a-real-key",
  AGENT_GATEWAY_TOKEN: "not-a-real-gateway-token", JAVA_BASE_URL: "http://127.0.0.1:18080",
  SB12_INPUT_TOKEN_RESERVE: "1000", SB12_PROVIDER_INPUT_OVERHEAD_TOKENS: "100",
  PI_INPUT_USD_PER_MTOK: "1", PI_OUTPUT_USD_PER_MTOK: "5", PI_CACHE_READ_USD_PER_MTOK: "0.1",
  PI_CACHE_WRITE_USD_PER_MTOK: "1.25", PI_CACHE_WRITE_1H_USD_PER_MTOK: "2", SB12_BUDGET_USD: "0.05",
};

assert.equal(inspectLiveConfiguration(env).ready, true);
assert.equal(inspectLiveConfiguration(env).provider, "anthropic", "an omitted provider must preserve Anthropic compatibility");
assert.equal(inspectLiveConfiguration({ ...env, PI_PROVIDER: "anthropic" }).ready, true);
assert.equal(inspectLiveConfiguration({ ...env, ANTHROPIC_API_KEY: undefined, ANTHROPIC_OAUTH_TOKEN: "local-oauth-stub" }).ready, true);
assert.deepEqual(inspectLiveConfiguration({ AGENT_MODE: "live" }).issues.map(issue => issue.variable), [
  "JAVA_MODE", "PI_MODEL", "AGENT_GATEWAY_TOKEN", "JAVA_BASE_URL", "ANTHROPIC_API_KEY or ANTHROPIC_OAUTH_TOKEN",
  "JAVA_MODE", "SB12_INPUT_TOKEN_RESERVE", "SB12_PROVIDER_INPUT_OVERHEAD_TOKENS", "PI_INPUT_USD_PER_MTOK",
  "PI_OUTPUT_USD_PER_MTOK", "PI_CACHE_READ_USD_PER_MTOK", "PI_CACHE_WRITE_USD_PER_MTOK", "PI_CACHE_WRITE_1H_USD_PER_MTOK",
]);

const deepseekEnv = {
  ...env, PI_PROVIDER: "deepseek", PI_MODEL: "deepseek-flash", DEEPSEEK_API_KEY: "local-deepseek-stub",
  ANTHROPIC_API_KEY: undefined,
  PI_CACHE_READ_USD_PER_MTOK: "0.1", PI_CACHE_WRITE_USD_PER_MTOK: "0", PI_CACHE_WRITE_1H_USD_PER_MTOK: "0",
};
assert.equal(inspectLiveConfiguration(deepseekEnv).ready, true);
assert.equal(inspectLiveConfiguration(deepseekEnv).provider, "deepseek");
assert.equal(inspectLiveConfiguration({ ...deepseekEnv, PI_CACHE_READ_USD_PER_MTOK: "0" }).ready, true);
assert.deepEqual(inspectLiveConfiguration({ ...deepseekEnv, DEEPSEEK_API_KEY: " ", ANTHROPIC_API_KEY: "local-anthropic-stub" }).issues.map(issue => issue.variable), ["DEEPSEEK_API_KEY"], "Anthropic credentials cannot authenticate DeepSeek");
assert.deepEqual(inspectLiveConfiguration({ ...env, ANTHROPIC_API_KEY: undefined, DEEPSEEK_API_KEY: "local-deepseek-stub" }).issues.map(issue => issue.variable), ["ANTHROPIC_API_KEY or ANTHROPIC_OAUTH_TOKEN"], "DeepSeek credentials cannot authenticate Anthropic");
for (const provider of ["", " ", "unknown", "openai"]) {
  const inspected = inspectLiveConfiguration({ ...env, PI_PROVIDER: provider });
  assert.equal(inspected.ready, false);
  assert.equal(inspected.provider, "invalid");
  assert.ok(inspected.issues.some(issue => issue.variable === "PI_PROVIDER"));
}
for (const variable of ["PI_CACHE_READ_USD_PER_MTOK", "PI_CACHE_WRITE_USD_PER_MTOK", "PI_CACHE_WRITE_1H_USD_PER_MTOK"]) {
  for (const value of [undefined, "", " ", "-0.01", "NaN", "Infinity", "-Infinity"]) {
    const inspected = inspectLiveConfiguration({ ...deepseekEnv, [variable]: value });
    assert.equal(inspected.ready, false, `${variable} must reject ${String(value)}`);
    assert.ok(inspected.issues.some(issue => issue.variable === variable));
  }
}
for (const variable of ["SB12_BUDGET_USD", "SB12_INPUT_TOKEN_RESERVE", "SB12_PROVIDER_INPUT_OVERHEAD_TOKENS", "PI_INPUT_USD_PER_MTOK", "PI_OUTPUT_USD_PER_MTOK"]) {
  for (const value of ["0", "-1", "NaN", "Infinity", "", " "]) {
    const inspected = inspectLiveConfiguration({ ...deepseekEnv, [variable]: value });
    assert.equal(inspected.ready, false, `${variable} must remain strictly positive`);
    assert.ok(inspected.issues.some(issue => issue.variable === variable));
  }
}
assert.equal(liveBudgetConfig(env).pricingSource, "https://platform.claude.com/docs/en/about-claude/pricing");
assert.equal(liveBudgetConfig(deepseekEnv).pricingSource, "https://api-docs.deepseek.com/quick_start/pricing");
assert.equal(liveBudgetConfig({ ...deepseekEnv, PI_PRICING_SOURCE: "https://example.test/approved-pricing" }).pricingSource, "https://example.test/approved-pricing");

const deepseekBudget = new BudgetController(liveBudgetConfig(deepseekEnv));
const deepseekEntry = deepseekBudget.reserve({ sequence: 1, provider: "deepseek", model: "deepseek-flash", contextBytes: 850, outputTokenLimit: 2_000 });
assert.equal(deepseekEntry.reservedCostUsd, 0.011, "zero cache-write rates must not remove the positive input reservation");
deepseekBudget.complete(deepseekEntry, {
  input: 400, output: 500, cacheRead: 100, cacheWrite: 0, totalTokens: 1_000,
  cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
});
assert.equal(deepseekEntry.status, "completed");
assert.equal(deepseekEntry.actualCostUsd, 0.00291);
assert.equal(deepseekBudget.totals().reservedUsd, 0);

const usageTemplate = {
  input: 400, output: 500, cacheRead: 100, cacheWrite: 0, totalTokens: 1_000,
  cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
};
for (const usage of [
  undefined,
  { ...usageTemplate, input: 0, output: 0, cacheRead: 0, totalTokens: 0 },
  { ...usageTemplate, input: NaN },
  { ...usageTemplate, output: Infinity },
  { ...usageTemplate, cacheRead: -1 },
  { ...usageTemplate, totalTokens: 0 },
  { ...usageTemplate, totalTokens: 999 },
  { ...usageTemplate, cacheWrite1h: 1 },
]) {
  const unknownUsageBudget = new BudgetController(liveBudgetConfig(deepseekEnv));
  const unknownUsageEntry = unknownUsageBudget.reserve({ sequence: 1, provider: "deepseek", model: "deepseek-flash", contextBytes: 850, outputTokenLimit: 2_000 });
  unknownUsageBudget.complete(unknownUsageEntry, usage);
  assert.equal(unknownUsageEntry.status, "failed", "missing or invalid usage must not be recorded as completed");
  assert.equal(unknownUsageEntry.actualUsage, undefined);
  assert.equal(unknownUsageEntry.actualCostUsd, undefined, "a conservative reservation is not an observed provider charge");
  assert.equal(unknownUsageEntry.chargedCostUsd, unknownUsageEntry.reservedCostUsd);
  assert.equal(unknownUsageBudget.totals().reservedUsd, 0);
  assert.equal(unknownUsageBudget.totals().chargedUsd, unknownUsageEntry.reservedCostUsd);
}

const budget = new BudgetController(liveBudgetConfig(env));
for (let index = 1; index <= 8; index++) budget.beginBusinessRun(`run-${index}`);
assert.throws(() => budget.beginBusinessRun("run-9"), BudgetExceededError);
const entry = budget.reserve({ sequence: 1, provider: "anthropic", model: "test-model", contextBytes: 850, outputTokenLimit: 2_000 });
assert.equal(entry.reservedCostUsd, 0.012);
budget.recordResponse(entry, { "request-id": "local-stub-request" });
budget.complete(entry, {
  input: 400, output: 500, cacheRead: 100, cacheWrite: 200, cacheWrite1h: 50, totalTokens: 1_200,
  cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
});
assert.equal(entry.status, "completed");
assert.equal(entry.requestId, "local-stub-request");
assert.equal(entry.actualCostUsd, 0.0031975);
assert.equal(budget.totals().chargedUsd, 0.0031975);

const failed = budget.reserve({ sequence: 2, provider: "anthropic", model: "test-model", contextBytes: 850, outputTokenLimit: 2_000 });
budget.fail(failed, "local stub disconnected");
assert.equal(failed.status, "failed");
assert.equal(budget.totals().chargedUsd, 0.0151975, "unknown failed-request cost must consume the full reservation");
assert.throws(
  () => budget.reserve({ sequence: 3, provider: "anthropic", model: "test-model", contextBytes: 850, outputTokenLimit: 8_000 }),
  BudgetExceededError,
);

const contextBudget = new BudgetController(liveBudgetConfig(env));
assert.throws(
  () => contextBudget.reserve({ sequence: 1, provider: "anthropic", model: "test-model", contextBytes: 901, outputTokenLimit: 1 }),
  BudgetExceededError,
);
const carried = new BudgetController(liveBudgetConfig({
  ...deepseekEnv, SB12_BUDGET_USD: "1", SB12_INITIAL_BUSINESS_RUNS: "1", SB12_INITIAL_CHARGED_USD: "0.2",
}));
assert.equal(carried.totals().businessRuns, 1);
assert.equal(carried.totals().chargedUsd, 0.2);
for (let index = 1; index <= 7; index++) carried.beginBusinessRun(`carried-${index}`);
assert.throws(() => carried.beginBusinessRun("carried-over-cap"), BudgetExceededError);
assert.equal(inspectLiveConfiguration({ ...deepseekEnv, SB12_INITIAL_BUSINESS_RUNS: "1.5" }).ready, false);
assert.equal(inspectLiveConfiguration({ ...deepseekEnv, SB12_INITIAL_CHARGED_USD: "1.01" }).ready, false);
console.log("PASS: provider-specific credentials, explicit zero cache rates, invalid numeric rejection, eight-run cap, conservative reservation, usage accounting, invalid-usage/failed-request charging, and budget/context rejection");
await import("./workbench-budget-contract.ts");
