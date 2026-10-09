# SB-12 FinalFive 实际执行结果技术复验（2026-10-08）

## 范围与结论

本复验只核验已经执行的 FinalFive 技术链路和证据；未运行启动器、未提交模型请求、未读取密钥，且未修改任何原始 evidence 或固定批次状态。对仍在运行的 `screenplay-sb12-final-five-postgres` 仅执行了 `BEGIN TRANSACTION READ ONLY` 查询，查询以 `ROLLBACK` 结束。

**工程收尾：通过。** FinalFive 的五项业务提交均已终态化，账本和四个 completed run 的工程证据通过复验；03-B 原 Java run 仍为 failed。03-B 的保存实体现有只读补证，核心 JSON、事件、回执、哈希及 v2 人工评价材料均已核验。业务 run 已达 8/8，禁止重跑或另行提交。

**SB-12 整体验收：仍待真人逐镜评价、供应商扣款对账和产品判定。** 工程收尾通过不等于 SB-12 整体验收通过。

## 2026-10-08：工程收尾复验（最新结论）

本节是本文件的当前结论；下方首次复验中“尚未导出完整 JSON”的表述仅描述补证前的原始目录状态。

| 复验项 | 结果 | 证据 |
| --- | --- | --- |
| 03-B 只读补证的文件齐全性、SHA-256 与归属 | 通过（核心证据） | `03-B.readonly-export-record.json` 列出的 5 个文件均存在，5 个 SHA-256 均与记录一致；保存分镜 `id`、`sourceRunId`、成功保存 event 70、最终回执 artifact/resultRef 及 03-B Java/Gateway run 相互一致。 |
| 03-B 补充人工评价材料可用性 | 通过（v2） | `03-B.HUMAN_EVALUATION_SUPPLEMENT.v2.md` 严格 UTF-8 可读且无异常控制字符；`failed / gateway_failed` 说明、` ```text` 原场景围栏及四镜内容均完整。旧包因控制字符损坏保留存档，不再供评价。 |
| Java 成功保存回执完成判据 | 通过（离线） | 运行时代码以同一 run 的 `save_storyboard_result` 成功工具结果和经 `artifactId/resultRef` 校验的回执判定完成；最终模型文字保留为展示/诊断，不能反向否定成功保存。失败保存、缺少保存或不匹配 artifact 仍被拒绝。 |
| 03-B 真实事件离线回放 | 通过 | `npm run sb12:03b-replay` 使用补证事件/回执，返回 `providerRequestsSent=0`，并断言 Java 历史状态继续为 `failed / gateway_failed`。 |
| 独立初始状态夹具及入口回归 | 通过 | `pwsh -NoProfile -File .\\scripts\\verify-sb12-final-five-entry.ps1` 返回 `status=passed`、`providerRequestsSent=0`。测试从 `scripts/fixtures/sb12-final-five-initial-state.json` 建立临时首次状态，不复制生产 terminal 状态。 |
| 相关 Gateway 回归 | 通过 | `npm run check`、`npm run storyboard:check`、`npm run provider:check` 均通过；provider contract 明确报告无外部 HTTP 请求。 |
| 总报告与 RUNBOOK 八项终态 | 通过 | `SB12_REAL_MODEL_QUALITY_REPORT.md` 页首和 `RUNBOOK.md` 当前状态均写明 6 completed、2 failed（01-8-A、03-B）、8/8 run、25 request、0 retry、USD 0.021636012 已计、USD 0 预留、禁止重跑。后续“3 run / 待提交”文字被明确标为历史准备记录，不能作为当前状态。 |

此前的唯一工程阻断项已关闭：v2 从已保存的只读 JSON 离线生成，记录明确替代旧评价包且不覆盖它。v2 来源 SHA-256 为 `6206045096DEDD65A42EB937EF97F9931F48EB6B9F876760EC07F4D58F07D5F1`，输出 SHA-256 为 `EFC79664D513D69D378A2E8F02852634953FC42A9720AD69593D0E9C01DBEE2D`；其 `storyboardId`、`sourceRunId`、四镜内容和原 Java 失败状态均已交叉验证。原始 `01-8-A`、`03-B` Java failed 状态和所有既有 evidence 均保持不变。

## 批次终态与累计账本

| Run | Java 状态 | Gateway run | provider requests / retry | 技术复验 |
| --- | --- | --- | --- | --- |
| 01-8-B | completed | `04ddfa64-760b-4244-bb5f-7eec2659b2c4` | 3 / 0 | 通过 |
| 02-A | completed | `4d4a2d45-babf-4324-8d7a-3f18a9cd2462` | 3 / 0 | 通过 |
| 02-B | completed | `23b9f1cf-5e0c-41ff-9048-d7b129280e77` | 3 / 0 | 通过 |
| 03-A | completed | `85df10ef-cfce-45c7-8210-d46b51384119` | 3 / 0 | 通过 |
| 03-B | failed (`gateway_failed`) | `ea19a78d-6f3c-4d92-9dbf-bc114de649db` | 4 / 0 | 失败，见下文 |

逐项 ledger（`01-A`、`01-B`、`01-8-A` 与 FinalFive 五项）只读汇总结果为：**8 / 8 business runs、25 provider requests、0 自动重试、按配置费率累计 USD 0.021636012、未结预留 USD 0、余额 USD 0.978363988**。25 个请求均记录为 `completed`；其中 failed 的 01-8-A 和 03-B 仍占用业务 run 与已计费用。金额是 Gateway 配置费率的账本计算值，**不是** DeepSeek 供应商实际扣款或逐请求账单对账。

证据：

- [FinalFive 批次进度](quality-runs/20261008/final-five-batch/batch-progress.json)
- [03-B 最终 ledger](quality-runs/20261008/03-B/03-B.provider-ledger.json)
- [01-8-A 承接 ledger](quality-runs/20261007/01-8-A/01-8-A.provider-ledger.json)
- [2026-10-07 累计汇总](quality-runs/20261007/remaining-batch/batch-summary.json)
- [固定批次终态](quality-runs/SB12_FIXED_EIGHT_BATCH/batch-execution-state.json)

## 四个 completed run 的保存与材料完整性

对每个 completed run 已核对：`accepted.runId = java-run.id`、`resultRef.storyboardId = java-storyboard.id`、保存镜头数等于配置目标、冻结上下文的镜头数/要求与请求一致、ledger 本 run 请求均 completed 且 retry 为 0、自动预检查的镜头数、来源引用和双 Prompt 字段均通过。

| Run | Java 保存镜头 | 保存分镜 / ledger / 冻结上下文 / 自动预检查 | 人工评价材料 |
| --- | ---: | --- | --- |
| 01-8-B | 8 / 8 | 完整；自动预检查通过 | 完整逐镜材料，未填写 |
| 02-A | 6 / 6 | 完整；自动预检查通过 | 完整逐镜材料，未填写 |
| 02-B | 6 / 6 | 完整；自动预检查通过 | 完整逐镜材料，未填写 |
| 03-A | 4 / 4 | 完整；自动预检查通过 | 完整逐镜材料，未填写 |

四项的原始文件均位于 `quality-runs/20261008/<run>/`，包括 `<run>.java-storyboard.json`、`<run>.provider-ledger.json`、`<run>.java-frozen-context.json` 和 `<run>.automatic-precheck.json`。人工材料在 [FinalFive 人工评价包](quality-runs/20261008/final-five-batch/HUMAN_EVALUATION_PACKET_FINAL_FIVE_BATCH.md)：四项的原场景及逐镜内容已追加，`直接用 / 小改可用 / 需要重做` 全部仍为空，未代填任何真人结论。

## 03-B：事件链、保存实体与失败原因

### 身份与终态

- Java run：`9b0f71b0-07bf-4bfb-850d-9f9de08cfd80`，traceId `21736005-bd7d-48be-882d-7a3fc5369d50`，终态 `FAILED`，errorCode `gateway_failed`。
- Gateway run：`ea19a78d-6f3c-4d92-9dbf-bc114de649db`；与 Java `agent_runs.gateway_run_id`、导出的 provider ledger 和固定批次状态一致。
- Java AgentEvent 链从 `run.started` 开始，四次 `provider.request_started/completed` 均完成，最终为 sequence 179 的 `run.failed`：`data.code=invalid_structured_output`、`errorCode=gateway_failed`。outbox 为 `DISPATCHED`、attempt 1，没有二次 dispatch 或自动重试。

### 已保存实体：原始目录未导出，但已有只读补证

这是对 PostgreSQL 的直接只读实体查询，而非从 resultRef 推断：

- `storyboard_drafts` 中存在 `be29bb7c-28fc-4d0c-9232-2af921ed1784`，`source_run_id` 为该 Java run，revision 1，保存时间 `2026-10-08T10:46:50.421204Z`。
- `storyboard_shots` 对该 storyboard 有 4 条记录；4 条均有非空 imagePrompt 和 videoPrompt。
- AgentEvent sequence 68/70 显示第二次 `save_storyboard_result` 调用完成且 `isError=false`；此前 sequence 35/36 的第一次保存调用为 `isError=true`。该 run 的自动预检查也因此不能从导出文件读取完整分镜。

原始运行目录中仍**不存在** `quality-runs/20261008/03-B/03-B.java-storyboard.json`，原自动预检查保持 `actualShotCount=null`、`Java storyboard artifact unavailable`，没有被改写。其后新增的 [只读恢复分镜](quality-runs/20261008/03-B/03-B.java-storyboard.readonly-recovered.json) 由保留 PostgreSQL 的只读导出取得：其 `id`、`sourceRunId`、4 镜数、事件 sequence 70 和最终回执均已交叉验证，且记录的 SHA-256 与文件一致。这补足了技术核验所需的保存实体证据，但不把 03-B Java run 改写为 completed。

[03-B v2 人工评价补充包](quality-runs/20261008/03-B/03-B.HUMAN_EVALUATION_SUPPLEMENT.v2.md) 已从同一保存分镜 JSON 离线生成，可供真人在“已保存实体、原 Java run failed”的限定下评价；[v2 生成记录](quality-runs/20261008/03-B/03-B.human-evaluation-v2-record.json)锁定来源/输出哈希和替代关系。旧包因控制字符损坏保留存档，不得用于评价。

### `gateway_failed` 的实际触发原因

原因可获取，且不需要推测：第二次保存成功后，最后一次模型文本先输出了说明文字（包括“已保存联板（4 个镜头）。”），再输出一个 JSON Markdown 代码块。该运行版本的 `parseSavedStoryboardAcknowledgement` 只接受两种形式：纯 JSON，或整段文本恰为一个完整 JSON Markdown 代码块；它明确拒绝代码块外的额外说明。最终回执不满足该严格结构，`hasValidStructuredResult()` 为 false，Gateway 产生 `invalid_structured_output`，随后 Java 记录 `gateway_failed`。

运行时实现哈希与 03-B 配置快照的 `runtime.ts`、`profiles.ts`、`storyboard-tools.ts`、`storyboard-schema.ts`、`budget.ts` 五项 SHA-256 均一致；因此上述源码规则可对应到实际运行版本，而不是事后不同版本。

证据：

- [03-B Java run](quality-runs/20261008/03-B/03-B.java-run.json)
- [03-B provider ledger](quality-runs/20261008/03-B/03-B.provider-ledger.json)
- [03-B 冻结上下文](quality-runs/20261008/03-B/03-B.java-frozen-context.json)
- [03-B 自动预检查](quality-runs/20261008/03-B/03-B.automatic-precheck.json)
- [03-B 运行配置及实现哈希](quality-runs/20261008/03-B/03-B.configuration.json)
- [严格回执校验实现](../../examples/pi-agent-gateway/src/storyboard-schema.ts) 与 [Gateway 失败映射](../../examples/pi-agent-gateway/src/server.ts)

## 逐项复验结论

1. **03-B Java/Gateway/事件链：通过。** Java run、traceId、Gateway run 和 sequence 179 终态事件相互一致。
2. **03-B 保存实体与只读补证：通过。** 保存 storyboard、4 个镜头及恢复 JSON 的归属、哈希均通过；v2 人工评价材料无控制字符、围栏和四镜内容均与保存 JSON 一致。原 Java run 仍 failed。
3. **03-B 失败归因：通过。** 直接事件代码与实际运行版本的严格回执规则共同指向“额外说明文字使最终确认回执不合格”，不是 provider 请求失败或自动重试。
4. **四个 completed run 的工程证据：通过。** 保存分镜、账本、冻结上下文、自动预检查齐全并一致；人工材料齐全但全部待真人填写。
5. **批次累计：通过。** 8 / 8 runs、25 requests、0 retry、USD 0.021636012 已计、USD 0 预留、USD 0.978363988 余额均已复算一致。
6. **工程收尾：通过。** v2 补证已完成上述“无控制字符、原场景 fence 完整、原 failed 状态不变”的离线校验，并以新增、带哈希的文件替代评价用途，未覆盖损坏旧包。run 次数已经耗尽，仍禁止重跑。

## SB-13 准入剩余事项

1. 真人评价负责人按既有量表完成六个 completed run 的逐镜评价；03-B 仅能使用 v2 包并以“已保存实体、原 Java run failed”的限定进行人工判断。不得代填。
2. 费用负责人以 DeepSeek 供应商账单/可用请求记录完成 25 个 provider 请求的实际扣款对账；Gateway 的 USD 0.021636012 只是配置费率计算，不能替代账单。
3. 产品负责人基于真人质量结论、费用对账和 SB-12 的正式验收门槛作出整体判定。仅在 SB-12 通过后，才可按 RUNBOOK 为 SB-13 另行确认试用预算、受控 live 配置和真实用户试用；本工程收尾结论本身不构成 SB-13 准入。
