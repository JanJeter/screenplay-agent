import type { Api, Model, Provider } from "@earendil-works/pi-ai";
import { anthropicProvider } from "@earendil-works/pi-ai/providers/anthropic";
import { deepseekProvider } from "@earendil-works/pi-ai/providers/deepseek";

type Environment = Record<string, string | undefined>;

export function selectLiveModel(env: Environment): { provider: Provider; model: Model<Api> } {
  const providerId = env.PI_PROVIDER ?? "anthropic";
  let provider: Provider;
  if (providerId === "anthropic") {
    provider = anthropicProvider();
  } else if (providerId === "deepseek") {
    const builtIn = deepseekProvider();
    const catalog = builtIn.getModels();
    // Official canonical ID as of 2026-10-07. Keep this application-level
    // addition separate from Pi's generated catalog. Reuse its protocol flags;
    // accounting uses the explicitly configured rates, not catalog prices.
    const flash = catalog.find(model => model.id === "deepseek-flash")
      ?? catalog.find(model => model.id === "deepseek-v4-flash");
    if (!flash) throw new Error("DeepSeek Flash compatibility template is unavailable");
    provider = {
      ...builtIn,
      getModels: () => catalog.some(model => model.id === "deepseek-flash")
        ? catalog
        : [...catalog, { ...flash, id: "deepseek-flash", name: "DeepSeek Flash" }],
    };
  } else {
    throw new Error("PI_PROVIDER must be anthropic or deepseek");
  }
  const selected = provider.getModels().find(model => model.id === env.PI_MODEL);
  if (!selected) throw new Error("PI_MODEL must name a model registered for PI_PROVIDER");
  const rate = (name: string, fallback: number): number => env[name] === undefined ? fallback : Number(env[name]);
  return {
    provider,
    model: {
      ...selected,
      cost: {
        input: rate("PI_INPUT_USD_PER_MTOK", selected.cost.input),
        output: rate("PI_OUTPUT_USD_PER_MTOK", selected.cost.output),
        cacheRead: rate("PI_CACHE_READ_USD_PER_MTOK", selected.cost.cacheRead),
        cacheWrite: rate("PI_CACHE_WRITE_USD_PER_MTOK", selected.cost.cacheWrite),
      },
    },
  };
}
