# 单场景分镜工作台：冻结契约（SB-02）

状态：**冻结且已实现**（2026-10-06）。版本 `2026-10-06.2`。任何字段、枚举、限制或错误码变更必须同时改本文件、`schemas/`、`fixtures/` 并由 Java、Node、前端负责人确认。Java、Gateway 和 React 的 mock HTTP 工程闭环已复验；这不等同于真实 provider 质量（SB-12 仍 BLOCKED）或真实用户试用（SB-13 待试用）。本次工程证据见 `REPAIR_ACCEPTANCE.md`，历史失败证据保留在 `FINAL_ACCEPTANCE_REVIEW_2026-10-06.md`。

所有外部 ID 都是 JSON 字符串，即使 Java 实体使用 `Long`。所有文本均先将 `CRLF`/`CR` 规范化为 `LF`，再做长度检查与哈希；时间为 RFC 3339 UTC；对象未说明的字段一律拒绝（`additionalProperties: false`）。所有浏览器请求使用现有 Bearer 鉴权，组织、用户身份不可由请求体指定；未知或非本组织资源返回 `404 RESOURCE_NOT_FOUND`。

## 1. 枚举、限制与四种结构

| 名称 | 冻结值 / 限制 |
| --- | --- |
| `shotSize` | `ESTABLISHING`, `WIDE`, `MEDIUM`, `CLOSE_UP`, `EXTREME_CLOSE_UP` |
| `cameraMovement` | `STATIC`, `PAN`, `TILT`, `DOLLY_IN`, `DOLLY_OUT`, `TRACK`, `HANDHELD` |
| `targetShotCount` | 整数 4～8，默认 6 |
| `instructions` | 字符串 0～1,000；允许空字符串，不允许仅空白；缺省视为 `""` |
| `sceneText` | 1～8,000 个规范化 LF Java/JS 字符；仅存在来源快照/内部上下文，浏览器生成请求不可提交 |
| `heading` | 1～255；`sceneNo` 为正整数；`sceneHash` 为 64 位小写 SHA-256 十六进制 |
| 镜头文本 | `visualDescription` 1～2,000；`dialogue`、`sound` 各 0～1,200；`sourceQuote` 1～500；`imagePrompt` 1～2,000；`videoPrompt` 1～2,500 |
| 时长 | `durationSeconds` 整数 1～30；一个分镜总时长上限 240 秒 |
| IDs / 请求键 | 1～128 的 `[A-Za-z0-9][A-Za-z0-9._:-]*`；`clientRequestId` 1～128，不能空白 |

四个互不替代的 JSON 结构如下，正式 schema 位于 `schemas/`：

1. **模型生成输出** `model-storyboard-result.schema.json`：仅可编辑镜头内容，生成模式为有序 `shots`，重做模式为 `proposalShot`。模型不得提供镜头 ID、组织、项目、来源或 revision。
2. **Java 持久化产物** `storyboard-detail.schema.json`：`StoryboardDraft`、不可变 `sourceSnapshot`、稳定 shot ID、revision 和提案摘要。只有 Java 分配 ID、顺序和归属。
3. **编辑保存请求** `storyboard-save-request.schema.json`：带 `expectedRevision` 及完整现有镜头数组。集合必须与当前已有 shot ID 完全相同；允许改字段和顺序，不可增删镜头。
4. **单镜提案** `shot-proposal.schema.json`：固定目标 `targetShotId`、`baseStoryboardRevision` 与候选内容。采纳只替换目标镜头可编辑字段，绝不改 ID、顺序、其他镜头或剧本文本。

`sourceQuote` 的“是 `sourceSnapshot.sceneText` 的精确非空子串”以及编辑镜头 ID 集合相等、重做目标匹配、总时长限制是**语义校验**，不能只依赖 JSON Schema。Node 在发送前验证模型输出，Java 在持久化前权威复验。

## 2. 公共 Java API

公共前缀为 `/api/v1/screenplay`。下表路径均接在此前缀后；错误统一为第 6 节的 `error-response.schema.json`。

| 方法 / 路径 | 请求 | 成功响应 | 规则 |
| --- | --- | --- | --- |
| `POST /projects/{projectId}/storyboards/generations` | `generation-request` | 202 `run-accepted-response` | 校验 script、scene 属于项目；冻结场景快照；创建 `generate_storyboard` run。 |
| `GET /projects/{projectId}/storyboards?scriptId=&page=&size=` | 无 body | 200 `storyboard-list-response` | `size` 1～100，按创建时间倒序；`scriptId` 可选。 |
| `GET /storyboards/{storyboardId}` | 无 body | 200 `storyboard-detail` | 返回保存数据，不从 SSE 拼装。 |
| `PUT /storyboards/{storyboardId}` | `storyboard-save-request` | 200 `storyboard-detail` | expectedRevision 一致才保存；成功 revision +1。 |
| `POST /storyboards/{storyboardId}/shots/{shotId}/regenerations` | `shot-regeneration-request` | 202 `run-accepted-response` | 冻结目标、相邻镜头、来源快照和基准 revision；创建 `rewrite_storyboard_shot` run。 |
| `GET /storyboards/{storyboardId}/shot-proposals/{proposalId}` | 无 body | 200 `shot-proposal` | 只允许所属项目/组织读取。 |
| `POST /storyboards/{storyboardId}/shot-proposals/{proposalId}/accept` | `proposal-mutation-request` | 200 `storyboard-detail` | PENDING 且 revision 一致时替换目标字段并 revision +1。 |
| `POST /storyboards/{storyboardId}/shot-proposals/{proposalId}/reject` | 空 body | 200 `shot-proposal` | PENDING→REJECTED；重复 reject 返回同一 REJECTED（幂等）。 |
| `GET /storyboards/{storyboardId}/export?format=markdown` | 无 body | 200 `text/markdown; charset=utf-8` | 只从已保存产物生成；`format` 必须为 `markdown`。 |

两个创建接口固定返回 `{"runId":"...","status":"QUEUED"}`；后续使用该 `runId` 调用既有 `GET /agent/runs/{runId}`、取消和 SSE。扩展既有 run 查询体的可选 `resultRef`（既有 `id` 字段仍为 run ID）：

```json
{"type":"storyboard","id":"sb_01J...","storyboardId":"sb_01J..."}
```

或：

```json
{"type":"shot_proposal","id":"sp_01J...","storyboardId":"sb_01J..."}
```

旧任务保留 `resultRef: null`。只有 Java 已成功保存相应产物后，run 才能进入 `COMPLETED` 并携带 resultRef；前端据此加载详情。SSE 只作状态/进度展示，断线可轮询该 run，终态停止订阅。

## 3. 内部 Java ↔ Node 回调

Node 只使用 Java 签发的执行令牌；浏览器永不调用这些接口。路径不挂公共前缀：

| 方法 / 路径 | scope | 返回/提交 | 权威规则 |
| --- | --- | --- | --- |
| `GET /internal/agent/runs/{runId}/storyboard-context` | `storyboard:read` | `internal-storyboard-context` | run 冻结的输入；`mode` 是 `generate` 或 `rewrite`。 |
| `POST /internal/agent/runs/{runId}/storyboard-result` | generate: `storyboard:create`; rewrite: `shot-proposal:create` | `internal-storyboard-result-request` → `internal-storyboard-result-response` | Java 由 run 决定模式、项目、目标与来源；不得相信 body 的归属。 |

Node profile 名称固定为 `generate_storyboard`、`rewrite_storyboard_shot`，且仅开放 `get_storyboard_context`、`save_storyboard_result`。每 run 最多 6 次工具调用，沿用 120 秒运行截止时间。Node 只提交 schema 与语义均合法的内容；Java 必须再次校验、原子保存产物、再返回 `artifactId`。回调 body 指纹按 run 存储：同 run 相同内容重复提交返回首次成功响应；内容不同返回 `409 RESULT_ALREADY_RECORDED`。

## 4. Revision、幂等、取消和保存规则

- 新 `StoryboardDraft.revision = 1`。成功编辑保存或成功采纳后递增 1；提案 reject 不改变 revision。
- `PUT` 和 accept 都必须带当前 `expectedRevision`。不一致返回 `409 STORYBOARD_REVISION_CONFLICT`，并给出 `currentRevision`；前端必须保留本地未保存内容，不得静默覆盖。
- 生成和重做的幂等作用域为 `(organizationId, userId, clientRequestId)`。请求指纹分别包含 taskType、project/script/scene、targetShotCount、规范化 instructions，或 storyboard/shot/base revision/规范化 instruction。同键同指纹返回原 run（202）；同键不同指纹返回 `409 IDEMPOTENCY_KEY_REUSED`。
- 用户要整场重新生成时使用新的 `clientRequestId`，创建另一份草稿，绝不覆盖已有草稿。
- 已取消的 run 不接受新的结果回调（`409 RUN_NOT_ACCEPTING_RESULT`）。取消与提交受同一 run 状态锁/事务保护；任何已审计的迟到数据不得让页面显示成功。
- accept 只允许一次实际应用；已 ACCEPTED 的再次 accept 返回 `409 PROPOSAL_NOT_PENDING`，不再递增 revision；已 ACCEPTED 的 reject 同样返回该错误。REJECTED 的重复 reject 成功返回原状态。
- 重新解析剧本会替换场景 ID；分镜后续查询、导出与重做全部使用保存的 `sourceSnapshot`，不得依赖仍存在的 `sourceSceneId`。

## 5. 预算与传输链路验证

| 环节 | 冻结上限 | 责任 |
| --- | --- | --- |
| 浏览器生成/重做 JSON 请求 | 16 KiB UTF-8 | Java/API 网关 |
| 内部冻结上下文 HTTP body | 256 KiB UTF-8 | Java/Node |
| Node→Java 模型结果 | 64 KiB UTF-8 | Java/Node |
| 保存编辑请求 / 详情响应 | 128 KiB UTF-8 | Java/前端 |
| 模型结构化输出预算 | 8,192 tokens，且结果 UTF-8 不超过 64 KiB | Node profile |

内部冻结上下文的预算必须对完整紧凑 wire JSON 计数（包括 `sourceSnapshot`，以及重做的目标镜、前镜和后镜），不可仅对 `sceneText` 计数，也不可截断。Java 在创建 run/outbox 前序列化其实际 HTTP 响应并预检；超过 256 KiB 返回 `400 STORYBOARD_CONTEXT_TOO_LARGE` 且不得创建任务。Gateway 的 response body 上限必须至少 256 KiB，并对同一完整 JSON 使用相同 UTF-8 计数。场景的 8,000 个规范化 LF 字符限制不变。详见 `REPAIR_CONTRACT.md`。

`fixtures/model-result-8-shots.json` 是最大镜头数的正例。`fixtures/validate-fixtures.mjs` 对其执行结构、语义和 UTF-8 预算验证，并断言请求、结果、保存产物分别不超过上述限制。此检查证明合同的序列化容量；不证明 provider 在真实调用中不会截断，真实 provider 校准属于 SB-05/SB-12。

## 6. 错误码和状态

所有非成功 JSON 响应：`{"code":"...","message":"面向用户的简短说明","traceId":"...","currentRevision":2?}`。`currentRevision` 只在版本冲突时出现。

| HTTP | code | 使用场景 |
| --- | --- | --- |
| 400 | `VALIDATION_FAILED` | Schema、长度、枚举、镜头数、总时长或 `sourceQuote` 校验失败。 |
| 400 | `STORYBOARD_CONTEXT_TOO_LARGE` | 完整冻结生成/重做上下文超过 256 KiB；不创建 run 或 outbox，也不截断上下文。 |
| 401 | `UNAUTHENTICATED` | 缺失/无效外部 Bearer 或内部执行令牌。 |
| 403 | `INSUFFICIENT_SCOPE` | 内部令牌缺 storyboard 精确 scope。 |
| 404 | `RESOURCE_NOT_FOUND` | ID 不存在或非当前组织/项目资源。 |
| 409 | `IDEMPOTENCY_KEY_REUSED` | 同幂等键不同请求指纹。 |
| 409 | `STORYBOARD_REVISION_CONFLICT` | 编辑/采纳的 expectedRevision 已过期。 |
| 409 | `RESULT_ALREADY_RECORDED` | 同 run 回调内容与已有成功提交不同。 |
| 409 | `RUN_NOT_ACCEPTING_RESULT` | run 已取消、失败或不是可提交状态。 |
| 409 | `PROPOSAL_NOT_PENDING` | accept/reject 对非 PENDING 提案。 |
| 409 | `PROPOSAL_BASE_REVISION_CONFLICT` | 提案基准 revision 与当前草稿不一致。 |
| 422 | `STORYBOARD_RESULT_INVALID` | 回调结构/业务约束失败；不得产生可用产物。 |
| 429 | `RUN_CAPACITY_EXHAUSTED` | 沿用现有运行容量限制。 |
| 503 | `AGENT_GATEWAY_UNAVAILABLE` | Gateway/Node 无法调度。 |

run 状态沿用已有 AgentRun 枚举；前端映射为：`QUEUED=排队中`、`RUNNING=正在读取场景/正在生成分镜`、`FINALIZING=正在保存`、`CANCELLING=正在停止`、`COMPLETED=已完成`、`FAILED=失败`、`CANCELLED=已取消`、`INTERRUPTED=服务恢复前中断`。`COMPLETED` 对分镜 run 必有非空 resultRef；`FAILED`、`CANCELLED` 和 `INTERRUPTED` 是终态，前端必须停止观察并允许新任务；其他终态的 resultRef 必为 null。

## 7. Fixture 使用与联调前提

`fixtures/MANIFEST.md` 是三端唯一 fixture 清单。Java 用 schema 与负例写 Controller/Service 测试；Node 用 model/internal fixtures 实现 profile 和工具测试；前端用公共 API response fixtures 渲染状态。前端不得将任何 fixture 当作生产成功结果。

SB-03～SB-06 的实体、快照、公共 API、resultRef/task context、scope 回调、受限 profile、工具及预算校验均已实现并完成 mock HTTP 联调。持续回归必须运行 fixtures 校验、Gateway `check`/`storyboard:check`/`smoke`、Java Testcontainers 测试和 `scripts/verify-storyboard-http-e2e.ps1`。该状态只覆盖工程链路，不能作为 SB-12 或 SB-13 的替代证据。
