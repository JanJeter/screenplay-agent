import type { Usage } from "@earendil-works/pi-ai";

export type PricingRates = {
  inputPerMillionUsd: number;
  outputPerMillionUsd: number;
  cacheReadPerMillionUsd: number;
  cacheWritePerMillionUsd: number;
  cacheWrite1hPerMillionUsd: number;
};

export type LiveBudgetConfig = {
  capUsd: number;
  initialBusinessRuns: number;
  initialChargedUsd: number;
  inputTokenReserve: number;
  providerInputOverheadTokens: number;
  rates: PricingRates;
  pricingSource: string;
};

export type ProviderRequestLedgerEntry = {
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

export class BudgetExceededError extends Error {
  constructor(message: string) { super(message); this.name = "BudgetExceededError"; }
}

export class BudgetController {
  private chargedUsd: number;
  private reservedUsd = 0;
  private readonly businessRunIds = new Set<string>();

  constructor(private readonly config: LiveBudgetConfig) {
    this.chargedUsd = config.initialChargedUsd;
  }

  beginBusinessRun(runId: string): void {
    if (this.businessRunIds.has(runId)) return;
    if (this.config.initialBusinessRuns + this.businessRunIds.size >= 8) {
      throw new BudgetExceededError("SB-12 business-run cap of 8 has been reached");
    }
    this.businessRunIds.add(runId);
  }

  reserve(input: {
    sequence: number; provider: string; model: string; contextBytes: number; outputTokenLimit: number;
  }): ProviderRequestLedgerEntry {
    const contextUpperBound = input.contextBytes + this.config.providerInputOverheadTokens;
    if (contextUpperBound > this.config.inputTokenReserve) {
      throw new BudgetExceededError(
        `Provider context exceeds SB12_INPUT_TOKEN_RESERVE (${contextUpperBound} > ${this.config.inputTokenReserve})`,
      );
    }
    const reservedCostUsd = this.estimateCost(this.config.inputTokenReserve, input.outputTokenLimit);
    if (this.chargedUsd + this.reservedUsd + reservedCostUsd > this.config.capUsd + Number.EPSILON) {
      throw new BudgetExceededError("SB-12 USD budget reservation would exceed the configured cap");
    }
    this.reservedUsd += reservedCostUsd;
    return {
      sequence: input.sequence, provider: input.provider, model: input.model, startedAt: new Date().toISOString(),
      status: "reserved", retryPolicy: { maxRetries: 0, retries: 0 }, contextBytes: input.contextBytes,
      inputTokenReserve: this.config.inputTokenReserve, outputTokenLimit: input.outputTokenLimit,
      reservedCostUsd, chargedCostUsd: 0,
    };
  }

  recordResponse(entry: ProviderRequestLedgerEntry, headers: Record<string, string>): void {
    entry.requestId ??= headers["request-id"] ?? headers["x-request-id"] ?? headers["anthropic-request-id"];
  }

  complete(entry: ProviderRequestLedgerEntry, usage: Usage | undefined): void {
    if (entry.status !== "reserved") return;
    if (!hasValidUsage(usage)) {
      this.fail(entry, "Provider usage is missing or invalid; charged the full request reservation");
      return;
    }
    const actualCostUsd = this.actualCost(usage);
    this.settle(entry, actualCostUsd, "completed");
    entry.actualUsage = {
      input: usage.input, output: usage.output, cacheRead: usage.cacheRead, cacheWrite: usage.cacheWrite,
      ...(usage.cacheWrite1h === undefined ? {} : { cacheWrite1h: usage.cacheWrite1h }), totalTokens: usage.totalTokens,
    };
    entry.actualCostUsd = actualCostUsd;
  }

  fail(entry: ProviderRequestLedgerEntry, reason: string): void {
    // A failed or interrupted request can still be billable. Conservatively consume
    // the full reservation until a provider usage export proves a lower amount.
    this.settle(entry, entry.reservedCostUsd, "failed");
    entry.failureReason = reason.slice(0, 240);
  }

  totals(): { businessRuns: number; businessRunCap: number; chargedUsd: number; reservedUsd: number; remainingUsd: number; capUsd: number } {
    return {
      businessRuns: this.config.initialBusinessRuns + this.businessRunIds.size, businessRunCap: 8,
      chargedUsd: this.chargedUsd, reservedUsd: this.reservedUsd,
      remainingUsd: Math.max(0, this.config.capUsd - this.chargedUsd - this.reservedUsd), capUsd: this.config.capUsd,
    };
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
  if (mode === "invalid") issues.push({ variable: "AGENT_MODE", reason: "must be mock or live" });
  if (mode === "mock") issues.push({ variable: "AGENT_MODE", reason: "must equal live to start an SB-12 live run" });
  if (provider === "invalid") issues.push({ variable: "PI_PROVIDER", reason: "must be anthropic or deepseek" });
  for (const variable of ["JAVA_MODE", "PI_MODEL", "AGENT_GATEWAY_TOKEN", "JAVA_BASE_URL"]) {
    if (!env[variable]?.trim()) issues.push({ variable, reason: "must be provided to start an SB-12 live run" });
  }
  if (provider === "anthropic" && !env.ANTHROPIC_API_KEY?.trim() && !env.ANTHROPIC_OAUTH_TOKEN?.trim()) {
    issues.push({ variable: "ANTHROPIC_API_KEY or ANTHROPIC_OAUTH_TOKEN", reason: "one Anthropic credential must be provided" });
  }
  if (provider === "deepseek" && !env.DEEPSEEK_API_KEY?.trim()) {
    issues.push({ variable: "DEEPSEEK_API_KEY", reason: "a DeepSeek credential must be provided" });
  }
  if (env.JAVA_MODE !== "http") issues.push({ variable: "JAVA_MODE", reason: "must equal http for the Java persistence chain" });
  for (const variable of [
    "SB12_INPUT_TOKEN_RESERVE", "SB12_PROVIDER_INPUT_OVERHEAD_TOKENS", "PI_INPUT_USD_PER_MTOK", "PI_OUTPUT_USD_PER_MTOK",
  ]) {
    if (!isPositiveNumber(env[variable])) issues.push({ variable, reason: "must be a positive numeric SB-12 budget input" });
  }
  for (const variable of ["PI_CACHE_READ_USD_PER_MTOK", "PI_CACHE_WRITE_USD_PER_MTOK", "PI_CACHE_WRITE_1H_USD_PER_MTOK"]) {
    if (!isNonNegativeNumber(env[variable])) issues.push({ variable, reason: "must be an explicitly provided finite non-negative cache rate" });
  }
  if (env.SB12_BUDGET_USD !== undefined && (!isPositiveNumber(env.SB12_BUDGET_USD) || Number(env.SB12_BUDGET_USD) > 1)) {
    issues.push({ variable: "SB12_BUDGET_USD", reason: "must be positive and no greater than 1.00" });
  }
  if (!isNonNegativeInteger(env.SB12_INITIAL_BUSINESS_RUNS ?? "0") || Number(env.SB12_INITIAL_BUSINESS_RUNS ?? "0") > 8) {
    issues.push({ variable: "SB12_INITIAL_BUSINESS_RUNS", reason: "must be a whole number from 0 through 8" });
  }
  if (!isNonNegativeNumber(env.SB12_INITIAL_CHARGED_USD ?? "0") || Number(env.SB12_INITIAL_CHARGED_USD ?? "0") > 1) {
    issues.push({ variable: "SB12_INITIAL_CHARGED_USD", reason: "must be a finite amount from 0 through 1.00" });
  }
  return { mode, provider, ready: mode === "live" && issues.length === 0, issues };
}

export function liveBudgetConfig(env: Environment): LiveBudgetConfig {
  const inspected = inspectLiveConfiguration(env);
  if (!inspected.ready || inspected.mode !== "live") {
    throw new Error(`Invalid SB-12 live configuration: ${inspected.issues.map(issue => issue.variable).join(", ") || "AGENT_MODE=live is required"}`);
  }
  return {
    capUsd: Number(env.SB12_BUDGET_USD ?? "1"), initialBusinessRuns: Number(env.SB12_INITIAL_BUSINESS_RUNS ?? "0"),
    initialChargedUsd: Number(env.SB12_INITIAL_CHARGED_USD ?? "0"), inputTokenReserve: Number(env.SB12_INPUT_TOKEN_RESERVE),
    providerInputOverheadTokens: Number(env.SB12_PROVIDER_INPUT_OVERHEAD_TOKENS),
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
