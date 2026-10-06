import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFile, readdir } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const fixtureDir = dirname(fileURLToPath(import.meta.url));
const schemaDir = join(fixtureDir, "..", "schemas");
const utf8Bytes = (value) => Buffer.byteLength(JSON.stringify(value), "utf8");

async function json(name) {
  return JSON.parse(await readFile(join(fixtureDir, name), "utf8"));
}

function exactKeys(value, keys) {
  assert.deepEqual(Object.keys(value).sort(), [...keys].sort());
}

function requiredString(value, key, maximum) {
  assert.equal(typeof value[key], "string", `${key} must be a string`);
  assert.ok(value[key].trim().length > 0, `${key} must be non-blank`);
  assert.ok(value[key].length <= maximum, `${key} exceeds its maximum`);
}

const shotSizes = new Set(["ESTABLISHING", "WIDE", "MEDIUM", "CLOSE_UP", "EXTREME_CLOSE_UP"]);
const movements = new Set(["STATIC", "PAN", "TILT", "DOLLY_IN", "DOLLY_OUT", "TRACK", "HANDHELD"]);

function editableShot(shot) {
  exactKeys(shot, ["shotSize", "cameraMovement", "visualDescription", "dialogue", "sound", "durationSeconds", "imagePrompt", "videoPrompt", "sourceQuote"]);
  assert.ok(shotSizes.has(shot.shotSize), "invalid shotSize");
  assert.ok(movements.has(shot.cameraMovement), "invalid cameraMovement");
  requiredString(shot, "visualDescription", 2000);
  assert.equal(typeof shot.dialogue, "string");
  assert.ok(shot.dialogue.length <= 1200);
  assert.equal(typeof shot.sound, "string");
  assert.ok(shot.sound.length <= 1200);
  assert.ok(Number.isInteger(shot.durationSeconds) && shot.durationSeconds >= 1 && shot.durationSeconds <= 30);
  requiredString(shot, "imagePrompt", 2000);
  requiredString(shot, "videoPrompt", 2500);
  requiredString(shot, "sourceQuote", 500);
}

function modelResult(result, expectedMode, sourceText) {
  assert.equal(result.mode, expectedMode);
  if (expectedMode === "generate") {
    exactKeys(result, ["mode", "shots"]);
    assert.ok(result.shots.length >= 4 && result.shots.length <= 8, "generated count must be 4..8");
    for (const shot of result.shots) {
      editableShot(shot);
      assert.ok(sourceText.includes(shot.sourceQuote), "sourceQuote must exist in source snapshot");
    }
    return;
  }
  exactKeys(result, ["mode", "proposalShot"]);
  editableShot(result.proposalShot);
  assert.ok(sourceText.includes(result.proposalShot.sourceQuote), "proposal quote must exist in source snapshot");
}

function mustReject(work, label) {
  assert.throws(work, undefined, `${label} must be rejected`);
}

const context = await json("internal-context-generate.json");
assert.equal(context.sourceSnapshot.sceneHash, createHash("sha256").update(context.sourceSnapshot.sceneText, "utf8").digest("hex"));
const sample = await readFile(join(fixtureDir, "..", "samples", "01-unopened-letter-dialogue.md"), "utf8");
const sampleScene = sample.match(/```text\n([\s\S]*?)\n```/)?.[1];
assert.equal(sampleScene, context.sourceSnapshot.sceneText, "fixture snapshot must match the frozen reference sample");
const four = await json("model-result-4-shots.json");
const six = await json("model-result-6-shots.json");
const eight = await json("model-result-8-shots.json");
const rewrite = await json("model-result-rewrite.json");
modelResult(four, "generate", context.sourceSnapshot.sceneText);
modelResult(six, "generate", context.sourceSnapshot.sceneText);
modelResult(eight, "generate", context.sourceSnapshot.sceneText);
modelResult(rewrite, "rewrite", context.sourceSnapshot.sceneText);
assert.equal(four.shots.length, 4);
assert.equal(six.shots.length, 6);
assert.equal(eight.shots.length, 8);

const detail = await json("storyboard-detail-6-shots.json");
assert.equal(detail.sourceSnapshot.sceneHash, createHash("sha256").update(detail.sourceSnapshot.sceneText, "utf8").digest("hex"));
assert.equal(detail.revision, 1);
assert.equal(detail.shots.length, 6);
assert.deepEqual(detail.shots.map((shot) => shot.orderIndex), [0, 1, 2, 3, 4, 5]);
assert.ok(detail.shots.reduce((total, shot) => total + shot.durationSeconds, 0) <= 240);
for (const shot of detail.shots) {
  const { id, orderIndex, ...content } = shot;
  assert.match(id, /^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/);
  assert.ok(Number.isInteger(orderIndex));
  editableShot(content);
  assert.ok(detail.sourceSnapshot.sceneText.includes(shot.sourceQuote));
}

const save = await json("storyboard-save-request-6-shots.json");
assert.equal(save.expectedRevision, detail.revision);
assert.deepEqual(new Set(save.shots.map((shot) => shot.id)), new Set(detail.shots.map((shot) => shot.id)));
assert.equal(save.shots.length, detail.shots.length);

const proposal = await json("shot-proposal-pending.json");
assert.equal(proposal.status, "PENDING");
assert.equal(proposal.baseStoryboardRevision, detail.revision);
assert.ok(detail.shots.some((shot) => shot.id === proposal.targetShotId));
editableShot(proposal.candidateShot);
assert.ok(detail.sourceSnapshot.sceneText.includes(proposal.candidateShot.sourceQuote));
for (const [name, expectedType, expectedId] of [["internal-result-response-storyboard.json", "storyboard", "sb_001"], ["internal-result-response-proposal.json", "shot_proposal", "proposal_001"]]) {
  const response = await json(name);
  assert.equal(response.artifactId, expectedId);
  assert.equal(response.resultRef.type, expectedType);
  assert.equal(response.resultRef.id, expectedId);
}

for (const name of ["run-queued.json", "run-completed-storyboard.json", "run-completed-proposal.json", "run-failed.json", "run-cancelled.json"]) {
  const run = await json(name);
  if (run.status === "COMPLETED") assert.notEqual(run.resultRef, null, `${name} needs resultRef`);
  else assert.equal(run.resultRef, null, `${name} must not have resultRef`);
}
for (const name of ["run-accepted-generation.json", "run-accepted-rewrite.json"]) {
  const accepted = await json(name);
  exactKeys(accepted, ["runId", "status"]);
  assert.match(accepted.runId, /^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/);
  assert.equal(accepted.status, "QUEUED");
}
const oversizedContext = await json("error-context-too-large.json");
exactKeys(oversizedContext, ["code", "message", "traceId"]);
assert.equal(oversizedContext.code, "STORYBOARD_CONTEXT_TOO_LARGE");

for (const name of ["invalid-enum.json", "invalid-wrong-shot-count.json", "invalid-missing-prompt.json", "invalid-source-quote-outside-snapshot.json"]) {
  const invalid = await json(name);
  mustReject(() => modelResult(invalid, invalid.mode, context.sourceSnapshot.sceneText), name);
}

const maxBudgets = new Map([
  ["generation-request-6-shots.json", 16 * 1024],
  ["internal-context-generate.json", 256 * 1024],
  ["internal-context-rewrite.json", 256 * 1024],
  ["model-result-8-shots.json", 64 * 1024],
  ["storyboard-save-request-6-shots.json", 128 * 1024],
  ["storyboard-detail-6-shots.json", 128 * 1024]
]);
for (const [name, budget] of maxBudgets) {
  const bytes = utf8Bytes(await json(name));
  assert.ok(bytes <= budget, `${name}: ${bytes} exceeds ${budget}`);
  process.stdout.write(`${name}: ${bytes} bytes / ${budget} bytes\n`);
}

const outputBytes = utf8Bytes(eight);
assert.ok(outputBytes <= 8192, `8-shot fixture ${outputBytes} bytes exceeds the conservative byte-per-token 8,192-token ceiling`);
for (const name of await readdir(schemaDir)) {
  if (name.endsWith(".json")) JSON.parse(await readFile(join(schemaDir, name), "utf8"));
}
for (const name of await readdir(fixtureDir)) {
  if (name.endsWith(".json")) JSON.parse(await readFile(join(fixtureDir, name), "utf8"));
}
process.stdout.write("Storyboard fixtures: valid (4/6/8 contract, negative cases, revision/resultRef, and budgets).\n");
