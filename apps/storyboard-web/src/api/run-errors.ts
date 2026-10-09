const runErrorMessages: Record<string, string> = {
  storyboard_source_quote_invalid: '生成结果中的原文引用与剧本不一致，未通过保存校验。原剧本未修改，请保留当前任务供排查。',
  storyboard_result_invalid: '生成结果的格式或镜头字段未通过校验。原剧本和已有分镜未修改。',
  storyboard_save_failed: '暂时无法确认分镜是否已保存。请查询原任务或查看分镜结果后再决定是否重试。',
  invalid_structured_output: '生成服务返回的结果格式无效，任务未完成。请查询原任务或查看已保存分镜。',
  gateway_failed: '生成服务未能完成任务。请查询原任务状态；原剧本和已有分镜仍保留。',
  budget_exhausted: '本活动剩余额度不足以继续生成。请确认并调整活动预算后再试；已保存分镜仍可查看和编辑。',
  business_run_limit: '本活动的生成次数已达到上限（含单镜重做）。请确认新的次数额度后再继续。',
  business_run_duplicate: '该任务已提交，不能重复执行。请查询原任务或打开已保存分镜。',
  budget_context_limit: '当前场景或创作要求超过本活动允许的输入长度。请缩短内容后再试。',
  budget_uncertain: '费用记录暂时无法确认，已暂停新生成。请检查活动账本后恢复；已保存内容不受影响。',
  recovery_interrupted: '服务恢复前任务未完成，请重新发起任务；已保存内容不受影响。'
}

export function runErrorMessage(code: string | null | undefined): string | undefined {
  return code ? runErrorMessages[code.toLowerCase()] : undefined
}
