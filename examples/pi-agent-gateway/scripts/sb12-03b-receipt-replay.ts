import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { hasVerifiedSavedStoryboardResult } from "../src/runtime.ts";
import { parseSavedStoryboardAcknowledgement, validateSavedStoryboardAcknowledgement } from "../src/storyboard-schema.ts";

// This is an offline replay of the retained 03-B Java event evidence. It does
// not create a runtime, service, provider request, or database mutation.
const root = resolve(import.meta.dirname, "../../..");
const evidence = resolve(root, "docs/storyboard-mvp/quality-runs/20261008/03-B");
const readJson = async (name: string) => JSON.parse(await readFile(resolve(evidence, name), "utf8")) as unknown;
const events = await readJson("03-B.agent-events.readonly-recovered.json") as Array<{ sequence: number; type: string; payload: { data?: Record<string, unknown> } }>;
const storyboard = await readJson("03-B.java-storyboard.readonly-recovered.json") as { id: string; sourceRunId: string; shots: unknown[] };
const run = await readJson("03-B.java-run.json") as { id: string; status: string; errorCode: string };
const receipt = await readFile(resolve(evidence, "03-B.final-model-receipt.readonly-recovered.txt"), "utf8");

assert.equal(run.status, "failed", "replay must preserve the historical Java failure");
assert.equal(run.errorCode, "gateway_failed");
assert.equal(storyboard.sourceRunId, run.id, "saved artifact must belong to the failed run");
assert.equal(storyboard.shots.length, 4);
assert.ok(events.some(event => event.sequence === 70 && event.type === "tool.completed" && event.payload.data?.isError === false),
  "real event chain must contain the successful final save");
assert.ok(receipt.includes("```"), "retained final model receipt must contain its JSON code block");
assert.throws(() => parseSavedStoryboardAcknowledgement(receipt),
  "the legacy strict diagnostic parser must reject prose before the code block");

const savedReceipt = { artifactId: storyboard.id, resultRef: { type: "storyboard", id: storyboard.id, storyboardId: storyboard.id } };
assert.doesNotThrow(() => validateSavedStoryboardAcknowledgement(savedReceipt));
const replay = [
  { role: "toolResult", toolName: "save_storyboard_result", isError: false, content: [{ type: "text", text: JSON.stringify(savedReceipt) }] },
  { role: "assistant", content: [{ type: "text", text: receipt }] },
];
assert.equal(hasVerifiedSavedStoryboardResult(replay), true,
  "a validated same-run saved artifact must complete despite final explanatory prose");
assert.equal(hasVerifiedSavedStoryboardResult([{ ...replay[0], isError: true }, replay[1]]), false,
  "a failed save event must still block completion");
assert.equal(hasVerifiedSavedStoryboardResult([replay[1]]), false,
  "missing saved artifact evidence must still block completion");
console.log("PASS: 03-B real-event offline receipt replay; providerRequestsSent=0; historical Java status remains failed");
