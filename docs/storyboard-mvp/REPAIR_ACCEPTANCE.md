# 单场景分镜工作台：修复收口验收

日期：2026-10-06（Asia/Shanghai）  
验收目录：`D:\desktop\screenplay-agent-backend`

## 结论

**工程验收：通过。** R1～R8 已在本工作区完成修复并由可重复的 Java、Gateway、浏览器回归复验。工程通过仅表示 mock 技术闭环、持久化、契约和交互边界通过；不表示真实模型或真实用户验证通过。

**真实模型验证（SB-12）：BLOCKED。** 本轮没有配置或调用真实 provider；没有真实 quality run、provider usage、时延、费用或逐镜人工评价。`SB12_REAL_MODEL_QUALITY_REPORT.md` 已明确账本中的上限是最多 8 个业务生成 run，而非 8 次 provider 请求；每 run 的实际 provider 请求数、SDK/transport 重试数和逐轮 usage 必须在真实执行时记录。本轮实际真实 provider run/request/retry/usage 均为无记录，未增加调用或费用额度。

**真实用户试用（SB-13）：待试用。** 本轮没有真实目标用户会话，不以 fixtures、mock 或自动化浏览器作为用户试用证据。

历史发现和失败证据未改写，仍保留于 `FINAL_ACCEPTANCE_REVIEW_2026-10-06.md`。

## 验收快照与范围

- Git `HEAD`：`df8de0af5540e8d2095ac7f10d69cd99d9e07470`。
- 结论针对**该 HEAD 加当前未提交工作区**，不宣称仅对应 HEAD。
- 开始时工作区已有 Java、Gateway、React 与文档的已修改/未跟踪实现；本轮保留所有既有改动。本轮新增可重复回归为 `AgentRecoveryServiceTest`，并更新本文件、`API_CONTRACT.md`、`RUNBOOK.md`、`PRODUCT.md`、`SB12_REAL_MODEL_QUALITY_REPORT.md`。
- 关键未提交实现摘要：`StoryboardService`/`StoryboardDraftRepository` 的写锁与调序 flush；Gateway 的正式模型可见 schema、语义回执及 256 KiB context reader；工作台的编辑版本保护、路由 blocker 和完整 run 状态；对应的 Testcontainers、Gateway contract 与 Playwright 用例。完整文件清单以验收时的 `git status --short` 为准。

## R1～R8 复验

| 项 | 修复位置 | 可重复回归 | 实际结果 | 证据类型 |
| --- | --- | --- | --- | --- |
| R1 并发保存/保存-采纳竞争 | `StoryboardService.java`、`StoryboardDraftRepository.java` | `StoryboardIntegrationTest.concurrent_saves_use_one_locked_revision_and_never_silently_overwrite`；`save_and_accept_race_share_the_same_draft_lock` | 两个同步并发请求恰为一个 200、一个 409；最终 revision 仅增加一次。 | Testcontainers PostgreSQL、并发 HTTP/事务断言；全量 Maven 通过。 |
| R2 PostgreSQL 调序 | `StoryboardService.java` | `StoryboardIntegrationTest.reorder_persists_temporary_indices_before_final_order_and_keeps_ids_and_content` | 上移、下移、完整反转均持久化并刷新，ID/内容不变、顺序连续，每次 revision 只加一。 | Testcontainers PostgreSQL 的真实唯一约束回归。 |
| R3 dirty 采纳与延迟保存编辑保护 | `apps/storyboard-web/src/pages/StoryboardWorkspacePage.tsx` | `storyboard-protection.spec.ts` 的 dirty proposal / delayed save；`storyboard-http.spec.ts` 的真实 HTTP 延迟响应 | dirty 时不发起采纳且保留编辑；保存 A 的延迟响应不会覆盖后输入 B，B 保持 dirty 且可保存。 | Playwright 断言；其中后者经真实 Java→Node HTTP、PostgreSQL 与浏览器复验。 |
| R4 模型可见完整 schema | `examples/pi-agent-gateway/src/storyboard-tools.ts`、`src/storyboard-schema.ts` | `npm run storyboard:check` 中 `captureProviderToolSchema` | 捕获 Anthropic-compatible transport 请求，断言 generate/rewrite 两分支、所有字段/required、枚举、长度/时长和 `additionalProperties:false` 均实际在工具 input schema 中。 | 本地 provider transport 契约回归；非真实模型质量证据。 |
| R5 等价回执/篡改回执 | `examples/pi-agent-gateway/src/runtime.ts`、`src/storyboard-schema.ts` | `npm run storyboard:check` 的 `hasEquivalentSavedStoryboardAcknowledgement` 断言 | 外层及嵌套 `resultRef` 键序变化接受；错误 artifact ID、type、storyboard ID、缺字段和额外字段全部拒绝。 | Gateway 语义比较/负例断言。 |
| R6 Java/Node 全 context 与 HTTP 容量 | `StoryboardService.java`、`StoryboardAgentInternalController.java`、Gateway `tools.ts`/`storyboard-schema.ts` | `StoryboardIntegrationTest.chinese_context_capacity_accepts_long_generate_and_rewrite_and_rejects_oversized_legacy_snapshot`；`npm run storyboard:check`；fixtures 校验 | 256 KiB 完整紧凑 context 上限一致；长中文、JSON 转义和三镜 rewrite 超过旧 16/64 KiB 限制仍被 production HTTP reader 接受；超限在 Java 创建 run/outbox 前以 `STORYBOARD_CONTEXT_TOO_LARGE` 拒绝。 | Testcontainers、Gateway HTTP reader、fixtures/schema 断言。 |
| R7 INTERRUPTED 恢复 | `AgentRecoveryService.java`、工作台 `types.ts`/`StoryboardWorkspacePage.tsx` | 新增 `AgentRecoveryServiceTest`；`storyboard-protection.spec.ts` 的 INTERRUPTED / FINALIZING observer 用例 | 重启把 QUEUED、RUNNING、FINALIZING、CANCELLING 改为 INTERRUPTED，释放 lease 并处理 outbox；页面将 INTERRUPTED 当终态、停止轮询并允许新生成，而 FINALIZING 仍可观察和取消。 | Java 单元状态转换断言 + Playwright 响应注入断言。 |
| R8 取消 Link/后退导航 | `apps/storyboard-web/src/pages/StoryboardWorkspacePage.tsx` | `storyboard-protection.spec.ts` 的 cancelling Link and browser-back 用例 | 取消确认后 URL、页面和本地编辑均保留；Link 和浏览器后退均在路由提交前被拦截。 | Playwright 浏览器导航断言。 |

## 已执行命令

| 检查 | 实际结果 |
| --- | --- |
| JDK 21：`mvnw.cmd -Dapi.version=1.44 -B test` | **110 tests，0 failures，0 errors，0 skipped**；Docker/Testcontainers 可用，含 PostgreSQL integration tests。 |
| `examples/pi-agent-gateway`: `npm run check` | 通过。 |
| `examples/pi-agent-gateway`: `npm run storyboard:check` | 通过；含 R4、R5、R6 与 4/6/8、负例、HTTP reader 断言。 |
| `examples/pi-agent-gateway`: `npm run smoke` | 通过；含 profile、SSE、回放、权限、幂等、并发、取消断言。 |
| `node docs/storyboard-mvp/fixtures/validate-fixtures.mjs` | 通过；4/6/8、revision/resultRef、正反例与 UTF-8 预算校验。 |
| `apps/storyboard-web`: `npm run build` | 通过。 |
| `apps/storyboard-web`: `npm test` | 命令退出 0，但 Vitest 报告 **No test files found**；不作为前端功能通过证据。 |
| `scripts/verify-storyboard-http-e2e.ps1` | 通过，Playwright **7 passed**：独立 PostgreSQL、Java、Node、Vite、Chromium；Gateway 为 `AGENT_MODE=mock`、`JAVA_MODE=http`，前端未设置 fixtures。脚本本轮创建的临时资源已清理。 |
| `git diff --check` | 通过（收口前最终复核）。 |

## 运行与契约交接

- `API_CONTRACT.md` 状态更新为“冻结且已实现”，并补全 `FINALIZING`、`CANCELLING`、`INTERRUPTED` 的状态契约及工程联调状态。
- `RUNBOOK.md` 明确 SB-11 复验所用的 mock HTTP 组合和本报告入口；live 试用仍须先完成 SB-12。
- `PRODUCT.md` 链接本报告，但没有把工程闭环表述成真实模型或用户验证。
- `SB12_REAL_MODEL_QUALITY_REPORT.md` 将账本改为业务 run、provider 请求、重试和逐轮 usage 的分离记录；不改变 USD 1.00 费用上限或 8 个业务 run 上限。

## 仍需外部输入的事项

1. 由受控环境提供真实 provider 配置并按 SB-12 账本执行，才可解除 BLOCKED。
2. 在 SB-12 通过后，按 `PILOT_REPORT.md` 组织 3～5 位真实目标用户试用；在此之前 SB-13 保持待试用。
