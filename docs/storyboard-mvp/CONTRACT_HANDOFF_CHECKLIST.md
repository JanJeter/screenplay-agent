# SB-02 契约冻结交接清单

冻结版本：`2026-10-06.1`。唯一规范是 `API_CONTRACT.md`、`schemas/` 与 `fixtures/`；任何一端发现差异，先更新这三处并通知三端负责人，再编码。

## Java（SB-03 / SB-04）

- [ ] 新建独立 `StoryboardDraft`、`StoryboardShot`、`StoryboardShotProposal` 存储。保存 `sourceSnapshot` 全字段和 `sceneHash`；禁止外键级联删除旧分镜。
- [ ] 实现公共 API、`run-accepted-response`、详情/列表/导出和 `storyboard-detail`；创建、更新、查询全部做组织与项目授权。
- [ ] 保存时执行 schema 对应 DTO 校验与语义校验：LF 长度、`sourceQuote` 子串、4～8 数量、总时长、保存 ID 集合和 0 基连续 orderIndex。
- [ ] 扩展 AgentRun：两个 taskType、结构化冻结上下文、resultRef、`(organizationId,userId,clientRequestId)` 幂等指纹和取消/回调原子状态约束。
- [ ] 实现内部 context/result 与精确 scope；保存产物成功后才令 run `COMPLETED`。用 `internal-result-response-*.json` 验证 artifactId/resultRef。
- [ ] 待联调：Java 现有 AgentRun DTO 当前尚未实现 `resultRef` 或分镜 taskType；本批未改 Java 业务代码。

## Node / Agent（SB-05）

- [ ] 注册 `generate_storyboard`、`rewrite_storyboard_shot` profile；每 profile 只能调用 `get_storyboard_context`、`save_storyboard_result`。
- [ ] 从最新成功的 context tool result 读取上下文；不要从历史用户消息推断项目、镜头或来源。
- [ ] 使用 `model-storyboard-result.schema.json` 和与 Java 相同的语义校验，特别是 sourceQuote、数量、Prompt、单镜模式不带 ID。
- [ ] 以 `internal-context-*.json`、`model-result-*.json`、`invalid-*.json` 做定向测试；配置 6 次工具调用、120 秒、8,192 输出 token/64 KiB 结果上限。
- [ ] 待联调：Java 的内部 endpoints、执行令牌 scope 和 artifact 确认尚未实现；mock 通过不表示真实模型质量通过。

## 前端（SB-06）

- [ ] 仅调用公共 Java API；不得要求 Gateway 地址、模型密钥或内部 JSON。
- [ ] 建立 fixture 模式，使用 `storyboard-detail-6-shots.json`、`storyboard-list-response.json`、`run-*.json` 和 `error-*.json` 覆盖加载、完成、失败、取消、冲突和无权限状态。
- [ ] 用 `resultRef` 打开已保存产物；SSE 文本只展示进度。刷新后从 GET run 恢复；终态后停止订阅/轮询。
- [ ] 编辑始终携带完整镜头 ID 集合和 expectedRevision；遇 `STORYBOARD_REVISION_CONFLICT` 保留本地草稿并提示比较/刷新。
- [ ] 待联调：当前没有本批前端实现或真实 endpoint；先以 fixtures 开工。

## 共同操作

1. 在仓库根目录运行 `node docs/storyboard-mvp/fixtures/validate-fixtures.mjs`。
2. 每端将同名正例作为契约测试输入；`invalid-*.json` 必须得到 `VALIDATION_FAILED` 或 `STORYBOARD_RESULT_INVALID`，不得写入产物。
3. 用 `samples/01-unopened-letter-6-shot-reference.md` 检查道具与动作连续性；fixture/mock 成功后仍需要 SB-12 真模型评价。
4. 集成先跑生成 6 镜头、保存、刷新、单镜重做/采纳；再跑 8 镜头预算、取消后回调、幂等键和过期 revision。
