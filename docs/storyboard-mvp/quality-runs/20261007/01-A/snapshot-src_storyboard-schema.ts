import { createHash } from "node:crypto";
import { Type } from "@earendil-works/pi-ai";

export const STORYBOARD_MAX_RESULT_BYTES = 64 * 1024;
// REPAIR_CONTRACT.md R6: this is the complete compact Java context body, not
// just sceneText. It deliberately retains the 8,000 UTF-16-character scene
// product limit while accommodating UTF-8, JSON escaping, and rewrite neighbours.
export const STORYBOARD_MAX_CONTEXT_BYTES = 256 * 1024;
export const STORYBOARD_MAX_OUTPUT_TOKENS = 8_192;

const idPattern = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/;
const sceneHashPattern = /^[a-f0-9]{64}$/;
const shotSizes = new Set(["ESTABLISHING", "WIDE", "MEDIUM", "CLOSE_UP", "EXTREME_CLOSE_UP"]);
const cameraMovements = new Set(["STATIC", "PAN", "TILT", "DOLLY_IN", "DOLLY_OUT", "TRACK", "HANDHELD"]);

type JsonRecord = Record<string, unknown>;

export type EditableShot = {
  shotSize: "ESTABLISHING" | "WIDE" | "MEDIUM" | "CLOSE_UP" | "EXTREME_CLOSE_UP";
  cameraMovement: "STATIC" | "PAN" | "TILT" | "DOLLY_IN" | "DOLLY_OUT" | "TRACK" | "HANDHELD";
  visualDescription: string;
  dialogue: string;
  sound: string;
  durationSeconds: number;
  imagePrompt: string;
  videoPrompt: string;
  sourceQuote: string;
};

export type SourceSnapshot = {
  scriptId: string;
  scriptRevision: number;
  sourceSceneId: string;
  sceneNo: number;
  heading: string;
  sceneText: string;
  sceneHash: string;
};

export type GenerateStoryboardContext = {
  mode: "generate";
  targetShotCount: number;
  instructions: string;
  sourceSnapshot: SourceSnapshot;
};

export type RewriteStoryboardContext = {
  mode: "rewrite";
  instruction: string;
  storyboardId: string;
  targetShotId: string;
  baseStoryboardRevision: number;
  sourceSnapshot: SourceSnapshot;
  targetShot: EditableShot;
  previousShot: EditableShot | null;
  nextShot: EditableShot | null;
};

export type StoryboardContext = GenerateStoryboardContext | RewriteStoryboardContext;
export type GenerateStoryboardResult = { mode: "generate"; shots: EditableShot[] };
export type RewriteStoryboardResult = { mode: "rewrite"; proposalShot: EditableShot };
export type StoryboardResult = GenerateStoryboardResult | RewriteStoryboardResult;
export type StoryboardResultRef = { type: "storyboard" | "shot_proposal"; id: string; storyboardId: string };
export type SavedStoryboardResult = { artifactId: string; resultRef: StoryboardResultRef };

// This is the model-facing representation of the frozen
// model-storyboard-result.schema.json contract. Keep the source-dependent
// checks below: JSON Schema cannot express quote containment or the requested
// generate count from the context.
const shotSizeSchema = Type.Enum([
  "ESTABLISHING", "WIDE", "MEDIUM", "CLOSE_UP", "EXTREME_CLOSE_UP",
]);
const cameraMovementSchema = Type.Enum([
  "STATIC", "PAN", "TILT", "DOLLY_IN", "DOLLY_OUT", "TRACK", "HANDHELD",
]);
const editableShotSchema = Type.Object({
  shotSize: shotSizeSchema,
  cameraMovement: cameraMovementSchema,
  visualDescription: Type.String({ minLength: 1, maxLength: 2_000, pattern: ".*\\S.*" }),
  dialogue: Type.String({ maxLength: 1_200 }),
  sound: Type.String({ maxLength: 1_200 }),
  durationSeconds: Type.Integer({ minimum: 1, maximum: 30 }),
  imagePrompt: Type.String({ minLength: 1, maxLength: 2_000, pattern: ".*\\S.*" }),
  videoPrompt: Type.String({ minLength: 1, maxLength: 2_500, pattern: ".*\\S.*" }),
  sourceQuote: Type.String({ minLength: 1, maxLength: 500, pattern: ".*\\S.*" }),
}, { additionalProperties: false });

export const modelStoryboardResultSchema = Type.Union([
  Type.Object({
    mode: Type.Literal("generate"),
    shots: Type.Array(editableShotSchema, { minItems: 4, maxItems: 8 }),
  }, { additionalProperties: false }),
  Type.Object({
    mode: Type.Literal("rewrite"),
    proposalShot: editableShotSchema,
  }, { additionalProperties: false }),
]);

/** The exact schema registered on save_storyboard_result for provider transport. */
export const saveStoryboardResultParameters = Type.Object({
  result: modelStoryboardResultSchema,
}, { additionalProperties: false });

export function normalizeStoryboardText(value: string): string {
  return value.replace(/\r\n?/g, "\n");
}

export function validateStoryboardContext(value: unknown): StoryboardContext {
  expectRecord(value, "Storyboard context");
  expectByteLimit(value, STORYBOARD_MAX_CONTEXT_BYTES, "Storyboard context");
  if (value.mode === "generate") {
    expectExactKeys(value, ["mode", "targetShotCount", "instructions", "sourceSnapshot"], "Generate context");
    expectInteger(value.targetShotCount, "Generate context targetShotCount");
    if (value.targetShotCount < 4 || value.targetShotCount > 8) {
      throw new Error("Generate context targetShotCount must be 4 through 8");
    }
    validateOptionalInstruction(value.instructions, "Generate context instructions");
    return { mode: "generate", targetShotCount: value.targetShotCount, instructions: value.instructions,
      sourceSnapshot: validateSourceSnapshot(value.sourceSnapshot) };
  }
  if (value.mode === "rewrite") {
    expectExactKeys(value, ["mode", "instruction", "storyboardId", "targetShotId", "baseStoryboardRevision", "sourceSnapshot", "targetShot", "previousShot", "nextShot"], "Rewrite context");
    expectText(value.instruction, 1_000, "Rewrite context instruction");
    expectId(value.storyboardId, "Rewrite context storyboardId");
    expectId(value.targetShotId, "Rewrite context targetShotId");
    expectInteger(value.baseStoryboardRevision, "Rewrite context baseStoryboardRevision");
    if (value.baseStoryboardRevision < 1) {
      throw new Error("Rewrite context baseStoryboardRevision must be a positive integer");
    }
    return {
      mode: "rewrite", instruction: value.instruction, storyboardId: value.storyboardId, targetShotId: value.targetShotId,
      baseStoryboardRevision: value.baseStoryboardRevision, sourceSnapshot: validateSourceSnapshot(value.sourceSnapshot),
      targetShot: validateEditableShot(value.targetShot, "Rewrite context targetShot"),
      previousShot: validateNullableShot(value.previousShot, "Rewrite context previousShot"),
      nextShot: validateNullableShot(value.nextShot, "Rewrite context nextShot"),
    };
  }
  throw new Error("Storyboard context mode must be generate or rewrite");
}

/** Validates the frozen model-output contract plus the source-dependent rules Java repeats before persistence. */
export function validateStoryboardResult(value: unknown, context: StoryboardContext): StoryboardResult {
  expectRecord(value, "Storyboard result");
  expectByteLimit({ result: value }, STORYBOARD_MAX_RESULT_BYTES, "Storyboard result");
  if (context.mode === "generate") {
    expectExactKeys(value, ["mode", "shots"], "Generate result");
    if (value.mode !== "generate" || !Array.isArray(value.shots) || value.shots.length !== context.targetShotCount) {
      throw new Error("Generate result must contain exactly the requested number of shots");
    }
    const shots = value.shots.map((shot, index) => validateEditableShot(shot, `Generate result shots[${index}]`));
    validateShotSemantics(shots, context.sourceSnapshot);
    return { mode: "generate", shots };
  }
  expectExactKeys(value, ["mode", "proposalShot"], "Rewrite result");
  if (value.mode !== "rewrite") throw new Error("Rewrite result mode must be rewrite");
  const proposalShot = validateEditableShot(value.proposalShot, "Rewrite result proposalShot");
  validateShotSemantics([proposalShot], context.sourceSnapshot);
  // The model schema intentionally has no ID, order, storyboard, or revision field.
  // Java applies this editable payload only to context.targetShotId.
  return { mode: "rewrite", proposalShot };
}

export function validateSavedStoryboardResult(value: unknown, context: StoryboardContext): SavedStoryboardResult {
  const saved = validateSavedStoryboardAcknowledgement(value);
  if (saved.resultRef.type !== (context.mode === "generate" ? "storyboard" : "shot_proposal")) {
    throw new Error("Storyboard save response has an unexpected artifact type");
  }
  if (context.mode === "generate" && saved.resultRef.storyboardId !== saved.artifactId) {
    throw new Error("Generated storyboard resultRef must point to its artifact");
  }
  if (context.mode === "rewrite" && saved.resultRef.storyboardId !== context.storyboardId) {
    throw new Error("Shot proposal was saved for a different storyboard");
  }
  return saved;
}

/** Validates a persisted-result receipt without relying on JSON key order. */
export function validateSavedStoryboardAcknowledgement(value: unknown): SavedStoryboardResult {
  expectRecord(value, "Storyboard save response");
  expectExactKeys(value, ["artifactId", "resultRef"], "Storyboard save response");
  expectId(value.artifactId, "Storyboard save response artifactId");
  expectRecord(value.resultRef, "Storyboard save response resultRef");
  expectExactKeys(value.resultRef, ["type", "id", "storyboardId"], "Storyboard save response resultRef");
  expectResultRefType(value.resultRef.type);
  expectId(value.resultRef.id, "Storyboard save response resultRef.id");
  expectId(value.resultRef.storyboardId, "Storyboard save response resultRef.storyboardId");
  if (value.artifactId !== value.resultRef.id) throw new Error("Storyboard save response artifactId must equal resultRef.id");
  return { artifactId: value.artifactId, resultRef: { type: value.resultRef.type, id: value.resultRef.id, storyboardId: value.resultRef.storyboardId } };
}

/** Both values must be valid receipts and refer to the exact persisted artifact. */
export function hasEquivalentSavedStoryboardAcknowledgement(candidate: unknown, saved: unknown): boolean {
  try {
    const actual = validateSavedStoryboardAcknowledgement(candidate);
    const expected = validateSavedStoryboardAcknowledgement(saved);
    return actual.artifactId === expected.artifactId
      && actual.resultRef.type === expected.resultRef.type
      && actual.resultRef.id === expected.resultRef.id
      && actual.resultRef.storyboardId === expected.resultRef.storyboardId;
  } catch {
    return false;
  }
}

function validateSourceSnapshot(value: unknown): SourceSnapshot {
  expectRecord(value, "Source snapshot");
  expectExactKeys(value, ["scriptId", "scriptRevision", "sourceSceneId", "sceneNo", "heading", "sceneText", "sceneHash"], "Source snapshot");
  expectId(value.scriptId, "Source snapshot scriptId");
  expectInteger(value.scriptRevision, "Source snapshot scriptRevision");
  if (value.scriptRevision < 1) throw new Error("Source snapshot scriptRevision must be positive");
  expectId(value.sourceSceneId, "Source snapshot sourceSceneId");
  expectInteger(value.sceneNo, "Source snapshot sceneNo");
  if (value.sceneNo < 1) throw new Error("Source snapshot sceneNo must be positive");
  expectText(value.heading, 255, "Source snapshot heading");
  expectText(value.sceneText, 8_000, "Source snapshot sceneText");
  if (value.sceneText !== normalizeStoryboardText(value.sceneText)) throw new Error("Source snapshot sceneText must use LF line endings");
  if (typeof value.sceneHash !== "string" || !sceneHashPattern.test(value.sceneHash)
      || createHash("sha256").update(value.sceneText, "utf8").digest("hex") !== value.sceneHash) {
    throw new Error("Source snapshot sceneHash does not match sceneText");
  }
  return { scriptId: value.scriptId, scriptRevision: value.scriptRevision, sourceSceneId: value.sourceSceneId,
    sceneNo: value.sceneNo, heading: value.heading, sceneText: value.sceneText, sceneHash: value.sceneHash };
}

function validateEditableShot(value: unknown, label: string): EditableShot {
  expectRecord(value, label);
  expectExactKeys(value, ["shotSize", "cameraMovement", "visualDescription", "dialogue", "sound", "durationSeconds", "imagePrompt", "videoPrompt", "sourceQuote"], label);
  if (typeof value.shotSize !== "string" || !shotSizes.has(value.shotSize)) throw new Error(`${label} has an invalid shotSize`);
  if (typeof value.cameraMovement !== "string" || !cameraMovements.has(value.cameraMovement)) throw new Error(`${label} has an invalid cameraMovement`);
  expectText(value.visualDescription, 2_000, `${label} visualDescription`);
  expectString(value.dialogue, 1_200, `${label} dialogue`);
  expectString(value.sound, 1_200, `${label} sound`);
  expectInteger(value.durationSeconds, `${label} durationSeconds`);
  if (value.durationSeconds < 1 || value.durationSeconds > 30) {
    throw new Error(`${label} durationSeconds must be an integer from 1 through 30`);
  }
  expectText(value.imagePrompt, 2_000, `${label} imagePrompt`);
  expectText(value.videoPrompt, 2_500, `${label} videoPrompt`);
  expectText(value.sourceQuote, 500, `${label} sourceQuote`);
  return value as EditableShot;
}

function validateNullableShot(value: unknown, label: string): EditableShot | null {
  return value === null ? null : validateEditableShot(value, label);
}

function validateShotSemantics(shots: EditableShot[], snapshot: SourceSnapshot): void {
  const totalDuration = shots.reduce((total, shot) => total + shot.durationSeconds, 0);
  if (totalDuration > 240) throw new Error("Storyboard total duration cannot exceed 240 seconds");
  for (const shot of shots) {
    if (!snapshot.sceneText.includes(shot.sourceQuote)) throw new Error("Storyboard sourceQuote is absent from the frozen source snapshot");
  }
}

function expectRecord(value: unknown, label: string): asserts value is JsonRecord {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error(`${label} must be an object`);
}

function expectExactKeys(value: JsonRecord, expected: string[], label: string): void {
  const actual = Object.keys(value).sort();
  const sortedExpected = [...expected].sort();
  if (actual.length !== sortedExpected.length || actual.some((key, index) => key !== sortedExpected[index])) {
    throw new Error(`${label} has unsupported or missing fields`);
  }
}

function expectId(value: unknown, label: string): asserts value is string {
  if (typeof value !== "string" || !idPattern.test(value)) throw new Error(`${label} must be a contract ID string`);
}

function expectInteger(value: unknown, label: string): asserts value is number {
  if (!Number.isInteger(value)) throw new Error(`${label} must be an integer`);
}

function expectResultRefType(value: unknown): asserts value is "storyboard" | "shot_proposal" {
  if (value !== "storyboard" && value !== "shot_proposal") throw new Error("Storyboard save response has an invalid resultRef type");
}

function expectString(value: unknown, maxLength: number, label: string): asserts value is string {
  if (typeof value !== "string" || value.length > maxLength) throw new Error(`${label} must be a string within its length limit`);
}

function expectText(value: unknown, maxLength: number, label: string): asserts value is string {
  expectString(value, maxLength, label);
  if (!value.trim()) throw new Error(`${label} must not be blank`);
}

function validateOptionalInstruction(value: unknown, label: string): asserts value is string {
  expectString(value, 1_000, label);
  if (value.length > 0 && !value.trim()) throw new Error(`${label} must be empty or non-blank`);
}

function expectByteLimit(value: unknown, maximum: number, label: string): void {
  if (Buffer.byteLength(JSON.stringify(value), "utf8") > maximum) throw new Error(`${label} exceeds its UTF-8 byte budget`);
}
