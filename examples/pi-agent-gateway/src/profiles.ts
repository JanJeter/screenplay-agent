export const taskTypes = [
  "general", "analyze_scene", "rewrite_dialogue", "extract_characters", "build_outline", "check_plot_logic",
] as const;
export type TaskType = typeof taskTypes[number];

export type TaskProfile = {
  id: TaskType;
  allowedTools: readonly string[];
  resultContract: string;
  requiredResultKeys: readonly string[];
  maxToolCalls: number;
};

const readTools = ["get_screenplay", "list_scenes", "get_scene"] as const;
const writeTools = [...readTools, "save_draft"] as const;

const profiles: Record<TaskType, TaskProfile> = {
  general: { id: "general", allowedTools: [...writeTools, "delegate_task"], resultContract: "用清晰中文回答；涉及改写时必须保存待审核草稿。仅当用户明确要求并行专项工作时才委派子任务。", requiredResultKeys: [], maxToolCalls: 8 },
  analyze_scene: { id: "analyze_scene", allowedTools: writeTools,
    resultContract: "输出 JSON 对象，字段为 summary、issues、evidence、suggestions；evidence 必须引用已读取场景或剧本原文。保存结果草稿。", requiredResultKeys: ["summary", "issues", "evidence", "suggestions"], maxToolCalls: 6 },
  rewrite_dialogue: { id: "rewrite_dialogue", allowedTools: writeTools,
    resultContract: "输出 JSON 对象，字段为 original、proposed、rationale；保存 proposed 为待审核草稿。", requiredResultKeys: ["original", "proposed", "rationale"], maxToolCalls: 6 },
  extract_characters: { id: "extract_characters", allowedTools: writeTools,
    resultContract: "输出 JSON 对象，字段为 characters（含 name、aliases、evidence）；保存人物提案草稿。", requiredResultKeys: ["characters"], maxToolCalls: 6 },
  build_outline: { id: "build_outline", allowedTools: writeTools,
    resultContract: "输出 JSON 对象，字段为 acts、beats、sceneIdeas；保存大纲草稿。", requiredResultKeys: ["acts", "beats", "sceneIdeas"], maxToolCalls: 6 },
  check_plot_logic: { id: "check_plot_logic", allowedTools: readTools,
    resultContract: "输出 JSON 对象，字段为 contradictions、evidence、fixes；只生成检查报告，不写剧本。", requiredResultKeys: ["contradictions", "evidence", "fixes"], maxToolCalls: 5 },
};

export function parseTaskType(value: unknown): TaskType {
  if (typeof value !== "string" || !taskTypes.includes(value as TaskType)) throw new Error("Unsupported taskType");
  return value as TaskType;
}

export function profileFor(value: TaskType | undefined): TaskProfile {
  return profiles[value ?? "general"];
}
