import { inspectLiveConfiguration } from "../src/budget.ts";
import { selectLiveModel } from "../src/live-provider.ts";

const inspected = inspectLiveConfiguration(process.env);
if (inspected.provider !== "invalid" && process.env.PI_MODEL?.trim()) {
  try { selectLiveModel(process.env); }
  catch {
    inspected.ready = false;
    inspected.issues.push({ variable: "PI_MODEL", reason: "must name a model registered for the selected PI_PROVIDER" });
  }
}
const presence = (name: string) => process.env[name]?.trim() ? "present" : "absent";

process.stdout.write(`${JSON.stringify({
  mode: inspected.mode,
  provider: inspected.provider,
  budgetProfile: process.env.AGENT_BUDGET_PROFILE ?? "sb12",
  readyForLiveRun: inspected.ready && inspected.mode === "live",
  readyForSb12LiveRun: inspected.ready && inspected.mode === "live" && process.env.AGENT_BUDGET_PROFILE !== "workbench",
  credentialPresence: {
    ANTHROPIC_API_KEY: presence("ANTHROPIC_API_KEY"),
    ANTHROPIC_OAUTH_TOKEN: presence("ANTHROPIC_OAUTH_TOKEN"),
    DEEPSEEK_API_KEY: presence("DEEPSEEK_API_KEY"),
  },
  requiredConfiguration: inspected.issues,
  note: "Only presence and validation status are emitted; secret values are never read into output.",
}, null, 2)}\n`);
