import type { Usage } from "@earendil-works/pi-ai";
import { randomUUID } from "node:crypto";
import { isAbsolute, resolve } from "node:path";
import { PersistentBudgetLedger } from "./budget-ledger.ts";

export type PricingRates = {
  inputPerMillionUsd: number;
  outputPerMillionUsd: number;
  cacheReadPerMillionUsd: number;
  cacheWritePerMillionUsd: number;
  cacheWrite1hPerMillionUsd: number;
};

export type LiveBudgetConfig = {
  profile: "sb12" | "workbench";
  activityId: string;
  businessRunCap: number;
  ledgerPath?: string;
  provider: string;
  model: string;
  capUsd: number;
  initialBusinessRuns: number;
  initialChargedUsd: number;
  inputTokenReserve: number;
  providerInputOverheadTokens: number;
  rates: PricingRates;
  pricingSource: string;
};

export type ProviderRequestLedgerEntry = {
  ledgerEntryId?: string;
  runId?: string;
  sequence: number;
  provider: string;
  model: string;
  startedAt: string;
  endedAt?: string;
  durationMs?: number;
  status: "reserved" | "completed" | "failed" | "rejected";
  retryPolicy: { maxRetries: 0; retries: 0 };
  requestId?: string;
  contextBytes: number;
  inputTokenReserve: number;
  outputTokenLimit: number;
  reservedCostUsd: number;
  actualUsage?: Pick<Usage, "input" | "output" | "cacheRead" | "cacheWrite" | "cacheWrite1h" | "totalTokens">;
  actualCostUsd?: number;
  chargedCostUsd: number;
  failureReason?: string;
};

export type BudgetErrorCode = "budget_exhausted" | "business_run_limit" | "business_run_duplicate" | "budget_context_limit" | "budget_uncertain";

export class BudgetExceededError extends Error {
  constructor(message: string, readonly code: BudgetErrorCode = "budget_exhausted") { super(message); this.name = "BudgetExceededError"; }
}

type LedgerSnapshot = {
  version: 1;
  settings: Omit<LiveBudgetConfig, "ledgerPath">;
  businessRunIds: string[];
  providerRequests: ProviderRequestLedgerEntry[];
};

export class BudgetController {
  private chargedUsd: number;
  private reservedUsd = 0;
  private readonly businessRunIds = new Set<string>();
  private readonly requests: ProviderRequestLedgerEntry[] = [];
  private readonly restoredRunIds = new Set<string>();
  private ledger?: PersistentBudgetLedger;
  private uncertain = false;

  constructor(private readonly config: LiveBudgetConfig) {
    this.chargedUsd = config.initialChargedUsd;
    if (config.ledgerPath) {
      try {
        this.ledger = new PersistentBudgetLedger(config.ledgerPath);
        const snapshot = this.ledger.read();
        if (snapshot !== undefined) this.restore(snapshot);
        this.persist();
      } catch (error) {
        this.ledger?.close();
        throw new BudgetExceededError(`Budget ledger cannot be opened safely: ${error instanceof Error ? error.message : "invalid ledger"}`, "budget_uncertain");
      }
    }
  }

  beginBusinessRun(runId: string): void {
    this.assertCertain();
    if (this.restoredRunIds.has(runId) || (this.config.profile === "workbench" && this.businessRunIds.has(runId))) {
      throw new BudgetExceededError("本次任务已记录在活动账本中，不能重复执行。", "business_run_duplicate");
    }
    if (this.businessRunIds.has(runId)) return;
    if (this.config.initialBusinessRuns + this.businessRunIds.size >= this.config.businessRunCap) {
      throw new BudgetExceededError("本活动的生成次数已用完；整场生成和单镜重做共用次数额度。", "business_run_limit");
    }
    this.businessRunIds.add(runId);
    this.persist();
  }

  reserve(input: {
    sequence: number; provider: string; model: string; contextBytes: number; outputTokenLimit: number; runId?: string;
  }): ProviderRequestLedgerEntry {
    this.assertCertain();
    if (this.ledger && (!input.runId || !this.businessRunIds.has(input.runId))) {
      throw new BudgetExceededError("模型请求缺少已记录的活动任务。", "budget_uncertain");
    }
    if (this.ledger && (input.provider !== this.config.provider || input.model !== this.config.model)) {
      throw new BudgetExceededError("模型请求与活动账本配置不一致。", "budget_uncertain");
    }
    const contextUpperBound = input.contextBytes + this.config.providerInputOverheadTokens;
    if (contextUpperBound > this.config.inputTokenReserve) {
      throw new BudgetExceededError(
        "本次上下文超过已配置的请求预留，请缩短输入或调整活动配置。", "budget_context_limit",
      );
    }
    const reservedCostUsd = this.estimateCost(this.config.inputTokenReserve, input.outputTokenLimit);
    if (this.chargedUsd + this.reservedUsd + reservedCostUsd > this.config.capUsd + Number.EPSILON) {
      throw new BudgetExceededError("本活动的费用额度不足以预留下一次模型请求，请确认新的活动预算。", "budget_exhausted");
    }
    this.reservedUsd += reservedCostUsd;
    const entry: ProviderRequestLedgerEntry = {
      ledgerEntryId: randomUUID(), runId: input.runId,
      sequence: input.sequence, provider: input.provider, model: input.model, startedAt: new Date().toISOString(),
      status: "reserved", retryPolicy: { maxRetries: 0, retries: 0 }, contextBytes: input.contextBytes,
      inputTokenReserve: this.config.inputTokenReserve, outputTokenLimit: input.outputTokenLimit,
      reservedCostUsd, chargedCostUsd: 0,
    };
    this.requests.push(entry);
    this.persist();
    return entry;
  }

  recordResponse(entry: ProviderRequestLedgerEntry, headers: Record<string, string>): void {
    entry.requestId ??= headers["request-id"] ?? headers["x-request-id"] ?? headers["anthropic-request-id"];
    this.persist();
  }

  complete(entry: ProviderRequestLedgerEntry, usage: Usage | undefined): void {
    if (entry.status !== "reserved") return;
    if (!hasValidUsage(usage)) {
      this.fail(entry, "Provider usage is missing or invalid; charged the full request reservation");
      return;
    }
    const actualCostUsd = this.actualCost(usage);
    entry.actualUsage = {
      input: usage.input, output: usage.output, cacheRead: usage.cacheRead, cacheWrite: usage.cacheWrite,
      ...(usage.cacheWrite1h === undefined ? {} : { cacheWrite1h: usage.cacheWrite1h }), totalTokens: usage.totalTokens,
    };
    entry.actualCostUsd = actualCostUsd;
    this.settle(entry, actualCostUsd, "completed");
    this.persist();
  }

  fail(entry: ProviderRequestLedgerEntry, reason: string): void {
    // A failed or interrupted request can still be billable. Conservatively consume
    // the full reservation until a provider usage export proves a lower amount.
    if (entry.status !== "reserved") return;
    entry.failureReason = reason.slice(0, 240);
    this.settle(entry, entry.reservedCostUsd, "failed");
    this.persist();
  }

  totals() {
    return {
      profile: this.config.profile, activityId: this.config.activityId,
      businessRuns: this.config.initialBusinessRuns + this.businessRunIds.size, businessRunCap: this.config.businessRunCap,
      chargedUsd: this.chargedUsd, reservedUsd: this.reservedUsd,
      remainingUsd: Math.max(0, this.config.capUsd - this.chargedUsd - this.reservedUsd), capUsd: this.config.capUsd,
    };
  }

  close(): void { this.ledger?.close(); }

  private assertCertain(): void {
    if (this.uncertain) throw new BudgetExceededError("活动账本无法可靠保存，请检查账本后再继续。", "budget_uncertain");
  }

  private settings(): LedgerSnapshot["settings"] {
    const { ledgerPath: _path, ...settings } = this.config;
    return settings;
  }

  private persist(): void {
    this.assertCertain();
    if (!this.ledger) return;
    try {
      this.ledger.save({ version: 1, settings: this.settings(), businessRunIds: [...this.businessRunIds], providerRequests: this.requests } satisfies LedgerSnapshot);
    } catch {
      this.uncertain = true;
      throw new BudgetExceededError("活动账本无法可靠保存，请检查账本后再继续。", "budget_uncertain");
    }
  }

  private restore(value: unknown): void {
    const snapshot = value as LedgerSnapshot | null;
    if (!snapshot || snapshot.version !== 1 || JSON.stringify(snapshot.settings) !== JSON.stringify(this.settings())
        || !Array.isArray(snapshot.businessRunIds) || !Array.isArray(snapshot.providerRequests)
        || snapshot.businessRunIds.some(id => typeof id !== "string" || !id)
        || new Set(snapshot.businessRunIds).size !== snapshot.businessRunIds.length
        || snapshot.businessRunIds.length > this.config.businessRunCap) {
      throw new Error("Ledger configuration or structure does not match this activity; existing budgets must not be reset or replaced");
    }
    for (const id of snapshot.businessRunIds) { this.businessRunIds.add(id); this.restoredRunIds.add(id); }
    const entryIds = new Set<string>();
    for (const entry of snapshot.providerRequests) {
      if (!entry || !entry.ledgerEntryId || entryIds.has(entry.ledgerEntryId) || !entry.runId || !this.businessRunIds.has(entry.runId)
          || !["reserved", "completed", "failed"].includes(entry.status)
          || !Number.isFinite(entry.reservedCostUsd) || entry.reservedCostUsd < 0
          || !Number.isFinite(entry.chargedCostUsd) || entry.chargedCostUsd < 0
          || entry.retryPolicy?.maxRetries !== 0 || entry.retryPolicy.retries !== 0
          || entry.provider !== this.config.provider || entry.model !== this.config.model
          || entry.inputTokenReserve !== this.config.inputTokenReserve
          || !Number.isSafeInteger(entry.outputTokenLimit) || entry.outputTokenLimit <= 0
          || entry.reservedCostUsd !== this.estimateCost(entry.inputTokenReserve, entry.outputTokenLimit)
          || !Number.isFinite(Date.parse(entry.startedAt))
          || (entry.status === "completed" && (!hasValidUsage(entry.actualUsage as Usage) || entry.actualCostUsd !== this.actualCost(entry.actualUsage as Usage)
            || entry.chargedCostUsd !== entry.actualCostUsd))
          || (entry.status === "failed" && entry.chargedCostUsd !== entry.reservedCostUsd)
          || (entry.status === "reserved" && entry.chargedCostUsd !== 0)) {
        throw new Error("Ledger contains an invalid provider request; manual inspection is required");
      }
      entryIds.add(entry.ledgerEntryId);
      this.requests.push(entry);
      if (entry.status === "reserved") {
        // The old process may have sent the request before it stopped. Consume
        // its full reservation; never treat missing usage as a free request.
        entry.status = "failed";
        entry.chargedCostUsd = entry.reservedCostUsd;
        entry.endedAt = new Date().toISOString();
        entry.durationMs = Math.max(0, Date.parse(entry.endedAt) - Date.parse(entry.startedAt));
        entry.failureReason = "Gateway restarted before usage was recorded; charged full reservation";
      }
      this.chargedUsd += entry.chargedCostUsd;
    }
  }

  private settle(entry: ProviderRequestLedgerEntry, chargedCostUsd: number, status: "completed" | "failed"): void {
    if (entry.status !== "reserved") return;
    this.reservedUsd -= entry.reservedCostUsd;
    this.chargedUsd += chargedCostUsd;
    entry.chargedCostUsd = chargedCostUsd;
    entry.status = status;
    entry.endedAt = new Date().toISOString();
    entry.durationMs = Math.max(0, Date.parse(entry.endedAt) - Date.parse(entry.startedAt));
  }

  private estimateCost(inputTokens: number, outputTokens: number): number {
    const inputRate = Math.max(
      this.config.rates.inputPerMillionUsd,
      this.config.rates.cacheReadPerMillionUsd,
      this.config.rates.cacheWritePerMillionUsd,
      this.config.rates.cacheWrite1hPerMillionUsd,
    );
    return (inputTokens * inputRate + outputTokens * this.config.rates.outputPerMillionUsd) / 1_000_000;
  }

  private actualCost(usage: Usage): number {
    const shortWrite = usage.cacheWrite - (usage.cacheWrite1h ?? 0);
    return (
      usage.input * this.config.rates.inputPerMillionUsd
      + usage.output * this.config.rates.outputPerMillionUsd
      + usage.cacheRead * this.config.rates.cacheReadPerMillionUsd
      + shortWrite * this.config.rates.cacheWritePerMillionUsd
      + (usage.cacheWrite1h ?? 0) * this.config.rates.cacheWrite1hPerMillionUsd
    ) / 1_000_000;
  }
}

type Environment = Record<string, string | undefined>;
type ConfigurationIssue = { variable: string; reason: string };

function hasValidUsage(usage: Usage | undefined): usage is Usage {
  if (!usage || typeof usage !== "object") return false;
  const counts = [usage.input, usage.output, usage.cacheRead, usage.cacheWrite, usage.totalTokens];
  if (counts.some(count => !Number.isSafeInteger(count) || count < 0) || usage.totalTokens === 0) return false;
  const longWrite = usage.cacheWrite1h ?? 0;
  return Number.isSafeInteger(longWrite) && longWrite >= 0 && longWrite <= usage.cacheWrite
    && usage.totalTokens === usage.input + usage.output + usage.cacheRead + usage.cacheWrite;
}

export function inspectLiveConfiguration(env: Environment): {
  mode: "mock" | "live" | "invalid";
  provider: "anthropic" | "deepseek" | "invalid";
  ready: boolean;
  issues: ConfigurationIssue[];
} {
  const mode = env.AGENT_MODE === "live" ? "live" : env.AGENT_MODE === undefined || env.AGENT_MODE === "mock" ? "mock" : "invalid";
  const configuredProvider = env.PI_PROVIDER ?? "anthropic";
  const provider = configuredProvider === "anthropic" || configuredProvider === "deepseek" ? configuredProvider : "invalid";
  const issues: ConfigurationIssue[] = [];
  const workbench = env.AGENT_BUDGET_PROFILE === "workbench";
  if (env.AGENT_BUDGET_PROFILE !== undefined && !["sb12", "workbench"].includes(env.AGENT_BUDGET_PROFILE)) {
    issues.push({ variable: "AGENT_BUDGET_PROFILE", reason: "must be sb12 or workbench" });
  }
  if (mode === "invalid") issues.push({ variable: "AGENT_MODE", reason: "must be mock or live" });
  if (mode === "mock") issues.push({ variable: "AGENT_MODE", reason: "must equal live to start a paid run" });
  if (provider === "invalid") issues.push({ variable: "PI_PROVIDER", reason: "must be anthropic or deepseek" });
  for (const variable of ["JAVA_MODE", "PI_MODEL", "AGENT_GATEWAY_TOKEN", "JAVA_BASE_URL"]) {
    if (!env[variable]?.trim()) issues.push({ variable, reason: "must be provided to start a paid run" });
  }
  if (provider === "anthropic" && !env.ANTHROPIC_API_KEY?.trim() && !env.ANTHROPIC_OAUTH_TOKEN?.trim()) {
    issues.push({ variable: "ANTHROPIC_API_KEY or ANTHROPIC_OAUTH_TOKEN", reason: "one Anthropic credential must be provided" });
  }
  if (provider === "deepseek" && !env.DEEPSEEK_API_KEY?.trim()) {
    issues.push({ variable: "DEEPSEEK_API_KEY", reason: "a DeepSeek credential must be provided" });
  }
  if (env.JAVA_MODE !== "http") issues.push({ variable: "JAVA_MODE", reason: "must equal http for the Java persistence chain" });
  for (const variable of [
    workbench ? "AGENT_INPUT_TOKEN_RESERVE" : "SB12_INPUT_TOKEN_RESERVE",
    workbench ? "AGENT_PROVIDER_INPUT_OVERHEAD_TOKENS" : "SB12_PROVIDER_INPUT_OVERHEAD_TOKENS",
    "PI_INPUT_USD_PER_MTOK", "PI_OUTPUT_USD_PER_MTOK",
  ]) {
    if (!isPositiveNumber(env[variable])) issues.push({ variable, reason: "must be a positive numeric budget input" });
  }
  for (const variable of ["PI_CACHE_READ_USD_PER_MTOK", "PI_CACHE_WRITE_USD_PER_MTOK", "PI_CACHE_WRITE_1H_USD_PER_MTOK"]) {
    if (!isNonNegativeNumber(env[variable])) issues.push({ variable, reason: "must be an explicitly provided finite non-negative cache rate" });
  }
  if (workbench) {
    if (!/^[A-Za-z0-9][A-Za-z0-9._-]{2,79}$/.test(env.AGENT_ACTIVITY_ID ?? "") || /^sb[-_]?12/i.test(env.AGENT_ACTIVITY_ID ?? "")) {
      issues.push({ variable: "AGENT_ACTIVITY_ID", reason: "must be an independent 3-80 character activity ID, outside the sealed SB-12 namespace" });
    }
    const ledgerPath = env.AGENT_BUDGET_LEDGER_PATH?.trim();
    if (!ledgerPath || !isAbsolute(ledgerPath) || /(?:^|[/\\])quality-runs(?:[/\\]|$)/i.test(ledgerPath)) {
      issues.push({ variable: "AGENT_BUDGET_LEDGER_PATH", reason: "must be an absolute durable ledger path outside historical quality-runs" });
    }
    if (!isPositiveNumber(env.AGENT_BUDGET_USD)) issues.push({ variable: "AGENT_BUDGET_USD", reason: "must explicitly specify the authorized activity USD cap" });
    if (!isNonNegativeInteger(env.AGENT_BUSINESS_RUN_LIMIT) || Number(env.AGENT_BUSINESS_RUN_LIMIT) < 1) {
      issues.push({ variable: "AGENT_BUSINESS_RUN_LIMIT", reason: "must explicitly specify a positive integer run cap shared by generation and rewrite" });
    }
    if (provider !== "deepseek") issues.push({ variable: "PI_PROVIDER", reason: "workbench live runs retain the official DeepSeek provider" });
    if (env.PI_MODEL !== "deepseek-flash") issues.push({ variable: "PI_MODEL", reason: "workbench live runs retain the deepseek-flash model" });
    if (!env.PI_PRICING_SOURCE?.trim()) issues.push({ variable: "PI_PRICING_SOURCE", reason: "record the source of the explicitly verified rates" });
    for (const variable of Object.keys(env).filter(key => key.startsWith("SB12_") && env[key] !== undefined)) {
      issues.push({ variable, reason: "historical SB-12 settings must not be mixed into a new workbench activity" });
    }
  } else {
  if (env.SB12_BUDGET_USD !== undefined && (!isPositiveNumber(env.SB12_BUDGET_USD) || Number(env.SB12_BUDGET_USD) > 1)) {
    issues.push({ variable: "SB12_BUDGET_USD", reason: "must be positive and no greater than 1.00" });
  }
  if (!isNonNegativeInteger(env.SB12_INITIAL_BUSINESS_RUNS ?? "0") || Number(env.SB12_INITIAL_BUSINESS_RUNS ?? "0") > 8) {
    issues.push({ variable: "SB12_INITIAL_BUSINESS_RUNS", reason: "must be a whole number from 0 through 8" });
  }
  if (!isNonNegativeNumber(env.SB12_INITIAL_CHARGED_USD ?? "0") || Number(env.SB12_INITIAL_CHARGED_USD ?? "0") > 1) {
    issues.push({ variable: "SB12_INITIAL_CHARGED_USD", reason: "must be a finite amount from 0 through 1.00" });
  }
  }
  return { mode, provider, ready: mode === "live" && issues.length === 0, issues };
}

export function liveBudgetConfig(env: Environment): LiveBudgetConfig {
  const inspected = inspectLiveConfiguration(env);
  if (!inspected.ready || inspected.mode !== "live") {
    throw new Error(`Invalid live configuration: ${inspected.issues.map(issue => issue.variable).join(", ") || "AGENT_MODE=live is required"}`);
  }
  const workbench = env.AGENT_BUDGET_PROFILE === "workbench";
  return {
    profile: workbench ? "workbench" : "sb12", activityId: workbench ? env.AGENT_ACTIVITY_ID! : "sb12",
    businessRunCap: workbench ? Number(env.AGENT_BUSINESS_RUN_LIMIT) : 8,
    ...(workbench ? { ledgerPath: resolve(env.AGENT_BUDGET_LEDGER_PATH!) } : {}),
    provider: inspected.provider, model: env.PI_MODEL!,
    capUsd: Number(workbench ? env.AGENT_BUDGET_USD : env.SB12_BUDGET_USD ?? "1"),
    initialBusinessRuns: workbench ? 0 : Number(env.SB12_INITIAL_BUSINESS_RUNS ?? "0"),
    initialChargedUsd: workbench ? 0 : Number(env.SB12_INITIAL_CHARGED_USD ?? "0"),
    inputTokenReserve: Number(workbench ? env.AGENT_INPUT_TOKEN_RESERVE : env.SB12_INPUT_TOKEN_RESERVE),
    providerInputOverheadTokens: Number(workbench ? env.AGENT_PROVIDER_INPUT_OVERHEAD_TOKENS : env.SB12_PROVIDER_INPUT_OVERHEAD_TOKENS),
    rates: {
      inputPerMillionUsd: Number(env.PI_INPUT_USD_PER_MTOK), outputPerMillionUsd: Number(env.PI_OUTPUT_USD_PER_MTOK),
      cacheReadPerMillionUsd: Number(env.PI_CACHE_READ_USD_PER_MTOK), cacheWritePerMillionUsd: Number(env.PI_CACHE_WRITE_USD_PER_MTOK),
      cacheWrite1hPerMillionUsd: Number(env.PI_CACHE_WRITE_1H_USD_PER_MTOK),
    },
    pricingSource: env.PI_PRICING_SOURCE?.trim() || (inspected.provider === "deepseek"
      ? "https://api-docs.deepseek.com/quick_start/pricing"
      : "https://platform.claude.com/docs/en/about-claude/pricing"),
  };
}

function isPositiveNumber(value: string | undefined): boolean {
  return value !== undefined && Number.isFinite(Number(value)) && Number(value) > 0;
}

function isNonNegativeNumber(value: string | undefined): boolean {
  return value !== undefined && value.trim().length > 0 && Number.isFinite(Number(value)) && Number(value) >= 0;
}

function isNonNegativeInteger(value: string | undefined): boolean {
  return value !== undefined && value.trim().length > 0 && Number.isSafeInteger(Number(value)) && Number(value) >= 0;
}
