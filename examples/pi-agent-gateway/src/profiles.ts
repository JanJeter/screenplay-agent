export const taskTypes = [
  "general", "analyze_scene", "rewrite_dialogue", "extract_characters", "build_outline", "check_plot_logic",
  "generate_storyboard", "rewrite_storyboard_shot",
] as const;
export type TaskType = typeof taskTypes[number];

export type TaskProfile = {
  id: TaskType;
  allowedTools: readonly string[];
  resultContract: string;
  requiredResultKeys: readonly string[];
  maxToolCalls: number;
  maxOutputTokens?: number;
};

const readTools = ["get_screenplay", "list_scenes", "get_scene"] as const;
const writeTools = [...readTools, "save_draft"] as const;
const storyboardTools = ["get_storyboard_context", "save_storyboard_result"] as const;

const profiles: Record<TaskType, TaskProfile> = {
  general: { id: "general", allowedTools: [...writeTools, "delegate_task"], resultContract: "用清晰中文回答；涉及改写时必须保存待审核草稿。仅当用户明确要求并行专项工作时才委派子任务。", requiredResultKeys: [], maxToolCalls: 8 },
  analyze_scene: { id: "analyze_scene", allowedTools: writeTools,
    resultContract: "输出 JSON 对象，字段为 summary、issues、evidence、suggestions；evidence 必须引用已读取场景或剧本原文。保存结果草稿。", requiredResultKeys: ["summary", "issues", "evidence", "suggestions"], maxToolCalls: 6 },
  rewrite_dialogue: { id: "rewrite_dialogue", allowedTools: writeTools,
    resultContract: "输出 JSON 对象，字段为 original、proposed、rationale。读取目标场景后，保存待审核草稿时必须传入 JSON 补丁 {original, proposed, sourceOffset}；sourceOffset 是 original 在该场景原文中的零基字符位置。不得将 proposed 当作完整场景。", requiredResultKeys: ["original", "proposed", "rationale"], maxToolCalls: 6 },
  extract_characters: { id: "extract_characters", allowedTools: writeTools,
    resultContract: "输出 JSON 对象，字段为 characters（含 name、aliases、evidence）；保存人物提案草稿。", requiredResultKeys: ["characters"], maxToolCalls: 6 },
  build_outline: { id: "build_outline", allowedTools: writeTools,
    resultContract: "输出 JSON 对象，字段为 acts、beats、sceneIdeas；保存大纲草稿。", requiredResultKeys: ["acts", "beats", "sceneIdeas"], maxToolCalls: 6 },
  check_plot_logic: { id: "check_plot_logic", allowedTools: readTools,
    resultContract: "输出 JSON 对象，字段为 contradictions、evidence、fixes；只生成检查报告，不写剧本。", requiredResultKeys: ["contradictions", "evidence", "fixes"], maxToolCalls: 5 },
  generate_storyboard: { id: "generate_storyboard", allowedTools: storyboardTools,
    resultContract: "必须先调用 get_storyboard_context。只根据返回的冻结上下文生成与 targetShotCount 完全相等的 shots。原文只在 sourceQuotes 列表提供，每镜选与其内容对应的 sourceQuoteId，允许多个镜头选同一片段；不要自行重写、拼接引用，优先不传 sourceQuote。sourceQuoteId 之外不得输出业务 ID、归属、revision 或额外字段。调用 save_storyboard_result 后，只返回该工具实际返回的 JSON {artifactId,resultRef}，不要再写另一份分镜。",
    requiredResultKeys: [], maxToolCalls: 6, maxOutputTokens: 8192 },
  rewrite_storyboard_shot: { id: "rewrite_storyboard_shot", allowedTools: storyboardTools,
    resultContract: "必须先调用 get_storyboard_context。只生成一个 proposalShot 的可编辑字段，保持来源事实和相邻镜头连续性。原文在 sourceQuotes 列表提供，选择与目标镜头内容对应的 sourceQuoteId；不要改写、拼接引用，优先不传 sourceQuote。不得输出或修改 targetShotId、其他镜头、顺序、revision 或剧本文本。调用 save_storyboard_result 后，只返回该工具实际返回的 JSON {artifactId,resultRef}。",
    requiredResultKeys: [], maxToolCalls: 6, maxOutputTokens: 8192 },
};

export function isStoryboardTaskType(value: TaskType | undefined): value is "generate_storyboard" | "rewrite_storyboard_shot" {
  return value === "generate_storyboard" || value === "rewrite_storyboard_shot";
}

export function parseTaskType(value: unknown): TaskType {
  if (typeof value !== "string" || !taskTypes.includes(value as TaskType)) throw new Error("Unsupported taskType");
  return value as TaskType;
}

export function profileFor(value: TaskType | undefined): TaskProfile {
  return profiles[value ?? "general"];
}

type JsonRecord = Record<string, unknown>;
const record = (value: unknown): value is JsonRecord => !!value && typeof value === "object" && !Array.isArray(value);
const text = (value: unknown): value is string => typeof value === "string" && value.trim().length > 0;
const textArray = (value: unknown): value is string[] => Array.isArray(value) && value.every(text);
const evidence = (value: unknown): boolean => Array.isArray(value) && value.every(item =>
  record(item) && text(item.sceneId) && text(item.quote));

/** Runtime schema guard for final model output. Required keys alone are not a schema. */
export function hasValidProfileResult(taskType: TaskType | undefined, value: unknown): boolean {
  const type = taskType ?? "general";
  if (type === "general") return true;
  if (!record(value)) return false;
  switch (type) {
    case "analyze_scene":
      return text(value.summary) && Array.isArray(value.issues) && evidence(value.evidence) && textArray(value.suggestions);
    case "rewrite_dialogue":
      return text(value.original) && text(value.proposed) && text(value.rationale);
    case "extract_characters":
      return Array.isArray(value.characters) && value.characters.every(character =>
        record(character) && text(character.name) && textArray(character.aliases) && evidence(character.evidence));
    case "build_outline":
      return Array.isArray(value.acts) && Array.isArray(value.beats) && Array.isArray(value.sceneIdeas);
    case "check_plot_logic":
      return Array.isArray(value.contradictions) && evidence(value.evidence) && textArray(value.fixes);
    default:
      return false;
  }
}
