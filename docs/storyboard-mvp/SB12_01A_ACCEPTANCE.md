# SB-12 样本 01-A 独立复验

复验日期：2026-10-07（Asia/Shanghai）  
验收范围：仅核验已有 01-A 证据和只读 PostgreSQL 编码回归；未调用模型、未运行 live 启动脚本、未重启服务、未删除或修改原始证据。

## 独立结论

| 项目 | 结论 |
| --- | --- |
| 1. 真实链路 | **通过。** Java run `05d93636-7cf1-43db-beed-3dd2047291af` 为 `completed`，其 `resultRef` 指向 Java 已保存的六镜 storyboard `d9a4954e-a67b-4532-b998-720a11381163`；同一证据链关联 Gateway run `a1c93b20-3717-4612-9e64-a288f3728ee8`。 |
| 2. 证据完整性 | **运行与工程审计证据通过；供应商扣款对账未完成。** 输入/配置/源码快照、请求、Java run、冻结 context、保存产物、最终账本、自动预检查、真人评价材料及 export-recovery 均在；恢复记录的 11 个受保护文件哈希和 6 个源码快照哈希实测一致。独立 provider request ID 未导出，DeepSeek 实际账单也未对账，二者不能补造。 |
| 3. 预算及导出修复 | **通过。** 最终账本为 1 业务 run、3 provider 请求、0 retry、按配置费率 USD 0.002657148、余额 USD 0.997342852、未结预留 USD 0。只读编码回归在原容器通过；恢复记录为 0 新 generation、未重启服务，且两个启动脚本均先导出最终账本再导出冻结 context。 |
| 4. 真人评价 | **待真人评价。** 自动预检查通过不等同剧情、连续性、可拍性或 Prompt 的人工结论。 |
| 5. SB-12 整体 | **仍未通过。** 其余七项未执行，01-A 也尚无真人逐镜评价和供应商账单对账。 |

继续执行授权：APPROVED

该授权仅满足仓库 `run-sb12-remaining-live.ps1` 对剩余七项的既定技术门槛：已完成 01-A、存在有效 Java run 与最终账本、初始 1 run / USD 0.002657148 不超过 8 run / USD 1.00，且本报告给出明确授权。它不代替真人评价、供应商账单对账，也不宣布 SB-12 通过。

## 证据范围、历史与快照

- 工作区 `HEAD`：`d6bf0e0980e3ef38dcf80a00e625f8f69f17aaa7`。本结论针对该 HEAD 加当前未提交工作区，而非仅 HEAD。
- 真实运行证据：`quality-runs/20261007/01-A/`。
- 旧版本报告中“未执行 / 0 run”的结论是 01-A 启动**前**基于仅有 `preflight.json` 所作判断；不删除该历史事实。本次复验的新增目录和 `01-A.export-recovery.json` 证明随后同一 run 已完成并补齐导出。
- 原始 `01-A.launch-state.json` 与 `01-A.launch-failure.json` 都保留在 `exporting-evidence` 阶段。它们不含 Java 或 provider 失败状态，不能单独用来判定生成失败。
- 冻结样本 `01-unopened-letter-dialogue.md` 的 SHA-256 为 `BE76B378C65FFA98AAFC064A65257ACD99D3BDE149C8FD55CB4F790B1462A435`，与配置快照一致。

## 1. Java、Gateway 与保存产物关联

| 核验项 | 实际证据 | 结果 |
| --- | --- | --- |
| Java run | `01-A.java-run.json`：ID `05d93636-7cf1-43db-beed-3dd2047291af`、`status=completed`、`errorCode=null`、`startedAt=11:29:56.035225Z`、`endedAt=11:30:06.455535Z`。 | 通过；持久化运行耗时 10,420.310 ms，不含构建、服务启动和恢复导出。 |
| `resultRef` → Java 保存产物 | `resultRef.type=storyboard`，`id=storyboardId=d9a4954e-a67b-4532-b998-720a11381163`；`01-A.java-storyboard.json.id` 相同且 `sourceRunId` 为该 Java run。 | 通过；不是仅凭脚本退出码判断。 |
| Gateway run 关联 | `01-A.provider-ledger.json.runId`、`01-A.automatic-precheck.json.gatewayRunId`、`01-A.export-recovery.json.gatewayRunId` 均为 `a1c93b20-3717-4612-9e64-a288f3728ee8`；三者均引用同一 Java run/trace `5f37a648-5ab3-4238-a683-3c9f5ac4c885`。启动脚本从 Java 持久化的 `agent_runs.gateway_run_id` 读取绑定后获取 ledger。 | 通过。 |
| 原始导出失败与恢复 | launch 文件时间为 `11:30:08Z`、阶段 `exporting-evidence`；恢复记录为 `11:35:29Z`，并明确 `newGenerationsSubmitted=0`、`servicesRestarted=false`。 | 通过；故障发生在证据序列化/导出之后，不改变同次业务 run。 |

## 2. 冻结输入、提交、产物与哈希链

以下独立重新计算均为一致：

- 恢复记录列出的 11 个文件（accepted、Java run、generation request、configuration、frozen context、storyboard、ledger、precheck、human packet、launch state、launch failure）SHA-256 **全部匹配**。
- 配置快照声明的 6 个 Gateway 源码快照（`runtime.ts`、`profiles.ts`、`storyboard-tools.ts`、`storyboard-schema.ts`、`live-provider.ts`、`budget.ts`）SHA-256 **全部匹配**对应 `snapshot-*` 文件。
- 冻结 context、Java storyboard 与 configuration 的 `frozenSceneSha256` 均为 `d5a923751a6e721ee6159ca231cbf0c189f297995b1063cd5107b8da51d94a96`。
- `targetShotCount=6`、生成 instructions 和 `scriptId/sceneId=1/1` 在 generation request、冻结 context、configuration 和自动预检查中一致；保存产物来源快照与冻结 context 的场景文本/哈希一致。

未发现覆盖、混用或恢复阶段补造新的模型结果的证据。恢复方式是对既有 PostgreSQL context 的只读读取、对既有 Java storyboard 的认证读取，以及在保留的 Gateway 内存进程上读取既有 ledger。

## 3. 分镜结构与内容自动预检查

**以下为自动预检查，不是真人逐镜评价。**

| 检查 | 实际结果 |
| --- | --- |
| 镜头数量与字段 | Java 保存产物恰为 6 镜；每镜均有稳定 ID、连续 `orderIndex` 0–5、景别、运镜、画面、声音、时长、`sourceQuote`、非空 image/video Prompt。 |
| 来源可定位 | 6 个 `sourceQuote` 都是冻结 `sceneText` 的非空子串；自动预检查也记录 `allSourceQuotesInJavaFrozenScene=true`。 |
| 信封事实 | 镜 2、4、5 的画面和两个 Prompt 均写明 sealed/unopened/not opening；未发现拆开、阅读的文本。 |
| 关键对白 | 镜 3 为“你说有东西要给我。”，镜 4 为“不是现在。”，镜 5 为“那我先替你保管。”；与冻结原场景一致。 |
| 动作顺序 | 镜 2 取出未拆信封，镜 4 藏信后放到茶几，镜 5 许晴拿起而不拆；顺序符合“林舟藏信 → 放信 → 许晴收信”。 |
| 结局/新增事件 | 镜 6 保持林舟沉默、雨声更响；未发现和解、离开或新增事件。 |

自动预检查不能可靠代替真人对镜头可拍性、表演节奏、情绪、镜间道具连续性和 Prompt 制作价值的判断。

## 4. Provider、usage 与预算

配置快照记录本次 provider/model 为 `deepseek` / `deepseek-flash`，计费来源为 DeepSeek 官方价格页，费率为 input USD 0.30、output USD 1.20、cache read USD 0.006（均为每百万 token；其他 cache 写入费率为 0）。最终账本的三个 completed 请求都为该 provider/model，`maxRetries=0` 且 `retries=0`。

| 请求 | usage（input / output / cacheRead） | 账本费用 USD | 按配置费率独立复算 USD | 状态 |
| --- | --- | ---: | ---: | --- |
| 1 | 1,378 / 37 / 0 | 0.000457800 | 0.000457800 | completed |
| 2 | 270 / 1,615 / 1,408 | 0.002027448 | 0.002027448 | completed |
| 3 | 185 / 81 / 3,200 | 0.000171900 | 0.000171900 | completed |
| 合计 | 1 个业务 run / 3 provider 请求 / 0 retry | **0.002657148** | **0.002657148** | 一致 |

最终 `budget` 为 `businessRuns=1`、`chargedUsd=0.002657148`、`reservedUsd=0`、`remainingUsd=0.997342852`、`capUsd=1`；独立复算 `1 - chargedUsd` 与账本余额一致。每请求开始前的最坏预留是 USD 0.0248304，但三个请求都以实际 usage 结算，未留下未结预留。

费用口径严格区分如下：

- **按配置费率计算：** 上表和账本的 USD 0.002657148，已验证算式一致。
- **失败请求保守占用：** 无；三个请求均 completed、0 retry、`reservedUsd=0`。这不表示未来失败请求免费。
- **供应商实际扣款：** **未对账**；证据记录为 `Not verified against supplier billing`，不能把配置计算值写成 DeepSeek 已确认扣款。

缺失项：每笔 ledger 没有独立 provider request ID。影响是无法与供应商侧单请求日志逐笔关联；不影响 ledger 中顺序、时间、usage、费率和总额的内部一致性，也不改变本次 8 run / USD 1.00 的技术门槛。它是后续账单对账前必须保留的审计限制，不能补造 ID。

## 5. 导出修复复核

审阅 `scripts/lib/sb12-postgres-json.ps1`、两个启动脚本及 `verify-sb12-postgres-json.ps1`，并在仍存活的 `screenplay-sb12-01a-postgres` 容器上实际运行只读回归，结果如下：

| 要求 | 复核结果 |
| --- | --- |
| 编码传输 | 通过。库函数将 PostgreSQL JSON 先 `convert_to(...,'UTF8')` 再 `encode(...,'base64')`；PowerShell 拼接全部 Base64 输出行，严格 Base64 解码并以 throwing UTF-8 decoder 解码。 |
| 长 Base64 不截断 | 通过。回归生成跨多条 PostgreSQL 76 列 Base64 行的中英文、引号、反斜杠、LF 数据，在 code page 65001 与 936 均逐字一致。 |
| 异常明确失败 | 通过。无记录/SQL NULL、JSON null、多记录标量子查询、SQL 错误、非法 JSON、非法 run GUID 均得到预期拒绝；psql 使用 `ON_ERROR_STOP=1` 和只读 `PGOPTIONS`。 |
| 导出次序 | 通过。`run-sb12-01a-live.ps1` 第 276–287 行、`run-sb12-remaining-live.ps1` 第 154–163 行均先导出最终 Gateway ledger，才读取/导出冻结 context。 |
| 不新增生成或重置预算 | 通过。恢复记录明示 0 新 generation、未重启服务；只读回归输出明确没有 provider request 或 artifact write。恢复后的账本仍为原 1 run / USD 0.002657148。 |

## 6. 尚存问题与负责角色

| 严重程度 | 问题 | 证据位置 | 负责角色 | 最小处理动作 |
| --- | --- | --- | --- |
| P2 | DeepSeek 单请求 ID 未导出，无法逐笔和供应商日志关联。 | `01-A.provider-ledger.json` 三笔记录均无该字段。 | Gateway / 费用核账负责人 | 后续实现或确认 SDK 可用 request ID 的安全导出；在 SB-12 最终费用结论前以供应商账单/日志完成对账。不得伪造 01-A ID。 |
| P2 | 供应商实际扣款未验证。 | `01-A.export-recovery.json.providerBilledAmount`。 | 费用核账负责人 | 在不泄露密钥的前提下，获取可审计的 DeepSeek 账单或使用记录，并与配置计算额分栏记录。 |
| P1（质量门槛） | 六镜尚无真人结论。 | `01-A.HUMAN_EVALUATION.md` 全部评价栏为待填写。 | 产品 / 独立真人评价者 | 对六镜填写“直接用 / 小改可用 / 需要重做”及理由；严重剧情错误必须标为需要重做。该项阻止 SB-12 整体验收，不阻止按既定技术门槛承接其余七项。 |

## 7. 真人评价状态与继续条件

真人评价状态：**待真人评价**。`01-A.HUMAN_EVALUATION.md` 已包含冻结原场景与六镜 Java 保存结果，但没有评价人、时间或任何结论；本报告未代填。

仓库现有的剩余批次启动器明确要求：本报告包含 `继续执行授权：APPROVED`，并且可读取 01-A Java run、最终 ledger、合法的初始 business run/charged USD，且 1 + 7 不超过 8 run 上限。以上条件均已独立核验；因此授权继续执行既定七项。执行者仍必须在受控环境中使用原有预算、承接 `1 run / USD 0.002657148`，逐项保留证据，并在任一失败、预算拒绝或超时时停止后续项。

本授权不改变验收门槛，也不表示 SB-12 的真实模型质量或用户价值已通过。
