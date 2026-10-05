# 剧本 Agent Gateway MVP

这个示例直接使用本仓库 `pi-agent/packages/agent/src` 和 `packages/ai/src`，不修改 Pi 核心和 Java 生产代码。Node 要求 >=22.19.0。默认的 mock 模式使用 **真实 Pi Agent runtime + faux LLM provider + 内存业务数据**，回答是预设演示，不代表模型分析质量。

本次验证：`npm run check`、`npm run smoke` 通过；Java 示例在 JDK 17 编译通过，并已实际连接本 Gateway 收到工具事件、文本增量和 `run.completed`。真实 LLM、真实 Java 回调、Spring 应用整体构建未验证。

## 启动与验证（PowerShell）

首次安装本地 Pi 锁定的依赖，不执行安装脚本：

```powershell
Set-Location D:\desktop\screenplay-agent-backend\pi-agent
npm ci --ignore-scripts --no-audit --no-fund
Set-Location ..\examples\pi-agent-gateway
npm run check
npm run smoke

# 本地演示凭据；两端必须一致，不要用于生产。
$env:AGENT_GATEWAY_TOKEN = 'local-demo-change-this-token'
npm start
```

另开 PowerShell，用 Java 示例走完整请求链路（JDK 17+；现有后端要求 JDK 21）：

```powershell
Set-Location D:\desktop\screenplay-agent-backend\examples\pi-agent-gateway
$env:AGENT_GATEWAY_TOKEN = 'local-demo-change-this-token'
java '-Dfile.encoding=UTF-8' examples/java/AgentGatewayClient.java
```

也可通过 PowerShell 创建会话和请求，再用 `curl.exe -N` 看流：

```powershell
$headers = @{
  Authorization = "Bearer $env:AGENT_GATEWAY_TOKEN"
  'X-Agent-User' = 'demo-user'
}
$session = Invoke-RestMethod http://127.0.0.1:3001/agent/sessions -Method Post -Headers $headers -ContentType application/json -Body '{"projectId":"demo-project","screenplayId":"demo-screenplay"}'
$request = @{sessionId=$session.sessionId; message='Analyze the scene and save a draft'; clientRequestId=[guid]::NewGuid().ToString()} | ConvertTo-Json
$run = Invoke-RestMethod http://127.0.0.1:3001/agent/chat -Method Post -Headers $headers -ContentType application/json -Body $request
curl.exe -N -H "Authorization: Bearer $env:AGENT_GATEWAY_TOKEN" -H 'X-Agent-User: demo-user' "http://127.0.0.1:3001/agent/runs/$($run.runId)/events"
```

## 文件职责

```text
pi-agent-gateway/
  package.json                   使用本地 Pi 的 tsx/tsc，不另装一份 Pi
  tsconfig.json                  将包名映射到用户这份 Pi 源码
  src/server.ts                  REST、run 生命周期、SSE、鉴权与限额
  src/runtime.ts                 Agent 初始化、provider、事件投影
  src/tools.ts                   get_screenplay / save_draft，Java HTTP 适配
  scripts/smoke.ts               无密钥集成验证，自启与清理子进程
  examples/java/AgentGatewayClient.java   JDK HTTP/SSE 客户端
```

生产建议将此目录提升为根目录的独立 `agent-gateway/` 服务，打包经过测试的 Pi commit 为内部 npm 包并锁版本。这里的源码 paths 映射仅用于跑通本地 checkout；不要凭 `package.json` 版本号推断已发布 npm 包一定与本地源码相同。

## 已实现的 Node 接口

除 `/health` 外，全部要求服务 Bearer 凭据和由可信 Java 生成的 `X-Agent-User`。不向浏览器发放服务凭据。

| 接口 | 请求 / 结果 |
|---|---|
| `POST /agent/sessions` | mock: `{projectId, screenplayId}`; HTTP Java mode also requires `{executionToken, applicationSessionId, applicationRunId}` → `{sessionId}` |
| `POST /agent/chat` | `{sessionId, message, clientRequestId}` → 202 `{runId}` |
| `GET /agent/runs/{runId}` | `{runId,status}` |
| `GET /agent/runs/{runId}/events` | SSE，支持 `Last-Event-ID` 或 `?after=N` |
| `POST /agent/runs/{runId}/cancel` | 请求取消；以终态事件为准 |

同一个 session 同时只跑一次，冲突返回 409。相同 session + clientRequestId + message 返回原 run；复用 key 但更换 message 返回 409。幂等范围只在当前进程、保留期内，生产应存数据库。

事件包括 `run.started`、`status`、`text.delta`、`tool.started`、`tool.completed`、`usage`、`run.completed/failed/cancelled`。`tool.started` 是收到调用请求，真正执行仍须通过 Pi preflight。每个事件有版本、runId、sessionId、seq、type、data；序号在 run 内递增。工具的详细参数、原始返回和 provider 原始 thinking 不直接透传。

SSE 断开只停止订阅，run 继续；用户点停止才调用 cancel。事件在连接建立前就缓存，晚订阅也不会丢开头。服务重启会丢失会话和事件。客户端收到终态后应关闭流；重新订阅时仍需鉴权。Java 转发层也需要保留 id/event/data、发送心跳和关闭代理缓冲。

## 接入真实 LLM

```powershell
$env:AGENT_MODE = 'live'
$env:ANTHROPIC_API_KEY = '<通过本地安全环境配置实际密钥>'
$env:PI_MODEL = 'claude-haiku-4-5-20251001'
npm start
```

示例模型 ID 来自本地 catalog，不保证账号具有访问权限，可通过 PI_MODEL 修改。默认只读本进程环境变量，不访问 coding-agent 的用户 OAuth 配置。真实 provider 调用会产生费用，本示例的自动验证不调用真实模型。

这份源码的 `pi-ai` 根入口已改用 `createModels()` + provider factory。`packages/agent/README.md` 的旧 `getModel` 示例与当前根入口不一致；旧 API 在 `/compat`。本示例使用新入口。

## Java 回调契约：需要新增，并非已有接口

默认 `JAVA_MODE=mock`。切换为 `JAVA_MODE=http` 前必须在 Java 实现并鉴权以下接口；当前项目的 `/api/v1/screenplay/...` 不接受这里的共享服务凭据。

```powershell
$env:JAVA_MODE = 'http'
$env:JAVA_BASE_URL = 'http://127.0.0.1:8080'
```

`JAVA_MODE=http` 不再使用共享的 `JAVA_AGENT_TOKEN` 或 `X-Agent-User` 作为 Java 回调授权。Java 在创建业务 run 后签发短期 `executionToken`，并在创建 Gateway session 时一并传入：

```json
{
  "projectId": "45",
  "screenplayId": "123",
  "applicationSessionId": "<Java agent_sessions.id>",
  "applicationRunId": "<Java agent_runs.id>",
  "executionToken": "<Java audience-bound JWT>"
}
```

该令牌只保存在 Gateway 运行上下文中，不会进入 Pi 消息、工具参数或 SSE。`JAVA_AGENT_TOKEN` 仅作为过渡性本地兼容回退，正式 HTTP 模式不会使用它。

1. `GET /internal/agent/projects/{projectId}/screenplays/{screenplayId}`

   返回 `{screenplayId, projectId, title, content, version}`。Java 将现有 ScriptVersion 的 id/rawText 映射成字符串 screenplayId/content；本示例的 version 是非负整数 revision。正式契约也可改用内容 hash，但应同步调整 TypeScript 校验。查询同时检查组织、用户权限，以及剧本归属项目。

2. `POST /internal/agent/projects/{projectId}/drafts`

   请求 `{screenplayId, sessionId, content, sourceVersion}`，header `Idempotency-Key: sessionId:toolCallId`。返回 `{draftId, status:"pending_review"}`。保存提案，不替换正式剧本。Java 建立唯一幂等约束，并校验 sourceVersion；冲突返回 409。

工具也接受 `{code:0,data:上述对象}` 包装；这是适配能力，不代表 Java 当前采用该响应包装。HTTP 工具只用固定 JAVA_BASE_URL，禁止重定向，10 秒超时，传播取消信号，限制响应体大小。项目/用户/剧本由会话闭包绑定，不是模型可以修改的工具参数。

生产的安全协议应从共享凭据升级为 Java 签发的短期 run capability JWT（iss/aud/exp/jti、organizationId、userId、projectId、scriptId、runId、scopes），Node 校验后回调 Java，Java重新鉴权；部署使用内网 TLS 或 mTLS。目前示例仅绑定 127.0.0.1，服务凭据代表受信 Java，**没有实现组织/项目 ACL 或 scope JWT 验证**。

## 推荐落地架构

```text
前端 --用户 JWT / REST + SSE--> Java API
                               | 权限、组织隔离、项目事实、草稿审批、业务事务
                               v
                          Node Agent Gateway
                               | session/run、上下文组装、工具白名单、事件转换
                               v
                             Pi Agent -> LLM
                               |
                               +--受限业务工具--> Java internal API
```

现有 Java `AnalysisTransactions.replace()` 在事务内调用 `AgentClient.analyzeScript()`；外围 `AnalysisLock` 还持有 PostgreSQL advisory transaction lock。不要直接将规则 parser 换成远程 LLM：这会在生成过程中占用两条数据库连接。新增独立 AgentJob 流程：短事务读快照/登记任务 → 事务外调用 Gateway → 短事务校验版本/保存草稿 → 用户确认后应用。

现有 Java 已有 project/scripts/scenes/elements 查询，适配这些 service 即可；无需 Node 直接连 PostgreSQL。人物设定库、Agent 草稿、项目上下文和 Agent 会话持久化尚需新增。

`analyze_scene`、`rewrite_dialogue`、`extract_characters`、`build_outline`、`check_plot_logic` 建议先作为明确的任务类型/profile。LLM 主代理可以直接完成分析和写作；工具用于 `get_scene`、`get_characters`、`save_draft` 等数据与可验证操作。如果把某任务包装成子 Agent 工具，给子 Agent 独立上下文、有限工具和预算，禁止调用自己的父级工具造成递归。

## session、memory 与多 Agent 预留

一个项目可有多个 session（每用户、分支、任务），项目事实跨 session 共享但由 Java 管理。持久化 session 的绑定身份、profile/version、完整 AgentMessage[]；单独保存 run 状态、事件序号、耗时/用量、工具审计。`Agent.sessionId` 只是 provider 缓存提示，不负责授权和保存历史。

上下文由“固定任务说明 + 权限已筛选的项目/人物事实 + 项目摘要 + 最近完整对话轮次 + 当前场景”组成。Java 当前允许 50 万字符剧本，必须按场景分片检索。压缩不能切断 assistant toolCall 与 toolResult 配对；中文不能将 Pi 的 chars/4 估算当精确 token 数。摘要是辅助记忆，不作为角色设定的唯一事实源。

下一阶段增加 Postgres 的 agent_session/agent_run/agent_message/agent_draft 表；高并发时增加 Redis Streams 事件日志与 run 队列、session 租约和 fencing token。事件先落日志再广播；只有持久化成功后才发业务完成事件。worker 崩溃后将 run 标为 interrupted，写操作通过幂等键去重；不要自动重放已产生副作用的工具。

多 Agent 用 `AgentProfile {id,systemPrompt,model,allowedTools,outputSchema,budget}` 和 `Run {parentRunId,agentId,contextVersion}` 预留。结构、人物、对白、市场分别有自己的上下文；总控收到结构化提案后合并。限制 delegation 深度/数量/总预算，取消沿子任务传播，正式写入由 Java 统一提交。市场 Agent 的外部查询要单独授权。当前 Pi orchestrator 明确 experimental，不作为生产业务依赖。

## MVP 边界

- 内存存储，空闲会话/已完成 run 保留约 30 分钟；无进程重启恢复、Redis、数据库或多副本锁。
- 每个 run 120 秒，工具调用数和输出长度受限；100 会话、300 run 上限。不是精确费用配额系统。
- 取消或失败后的会话要求新建，避免不完整 toolCall/result 历史污染下一轮；正式版需要恢复策略。
- 没有自动摘要、项目事实同步、五个专业 Agent、前端页面、Spring Controller 或真实 Java 内部接口。
- mock 保存是本地演示；真实保存结果、用户采纳、事务一致性仍需 Java 实现。
- 没有注册文件读写、shell 或任意 URL 工具；这不等于 Node 进程已经被 OS 沙箱隔离。部署时仍需最小权限账号、只读文件系统、出网限制和独立密钥。

参考：本地 Pi README、agent.ts/types.ts/agent-loop.ts、Harness Session 实现；[Spring MVC SSE](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-async.html)、[SSE 事件及重连](https://developer.mozilla.org/en-US/docs/Web/API/Server-sent_events/Using_server-sent_events)。
