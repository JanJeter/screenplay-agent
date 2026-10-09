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
| `GET /agent/runs/{runId}/ledger` | 终态后查询逐 provider 请求的 usage、费用/失败记录及当前进程预算；使用相同服务鉴权和用户归属校验 |
| `POST /agent/runs/{runId}/cancel` | 请求取消；以终态事件为准 |

同一个 session 同时只跑一次，冲突返回 409。相同 session + clientRequestId + message 返回原 run；复用 key 但更换 message 返回 409。幂等范围只在当前进程、保留期内，生产应存数据库。

事件包括 `run.started`、`status`、`text.delta`、`tool.started`、`tool.completed`、`usage`、`run.completed/failed/cancelled`。`tool.started` 是收到调用请求，真正执行仍须通过 Pi preflight。每个事件有版本、runId、sessionId、seq、type、data；序号在 run 内递增。工具的详细参数、原始返回和 provider 原始 thinking 不直接透传。

SSE 断开只停止订阅，run 继续；用户点停止才调用 cancel。事件在连接建立前就缓存，晚订阅也不会丢开头。服务重启会丢失会话和事件。客户端收到终态后应关闭流；重新订阅时仍需鉴权。Java 转发层也需要保留 id/event/data、发送心跳和关闭代理缓冲。

## 日常工作台预算

模型通过 `get_storyboard_context.sourceQuotes` 读取原文片段并选择 `sourceQuoteId`。ID 绑定当前冻结原文的 hash 和字符区间；Gateway 将它映射为精确 `sourceQuote` 后，再走原 Java 结构与原文包含校验。旧的精确 `sourceQuote` 仍可用；不存在的 ID、改写或拼接引用、ID 与文本冲突均拒绝保存。模型上下文只提供一次原文文本，避免重复占用输入预算。

首次保存失败会立即终止本次 Agent 运行，阻止额外模型请求反复盲改。`storyboard_source_quote_invalid` 表示来源引用无效，`storyboard_result_invalid` 表示结果结构无效，`storyboard_save_failed` 表示未能确认 Java 保存成功；最后一种情况应先查看已有任务结果，再决定是否创建新任务。

日常入口默认 mock，不产生模型费用。live 必须先明确新活动授权，使用 `AGENT_BUDGET_PROFILE=workbench`，不能复用封存 SB-12 的启动参数、账本或运行数据。

| 变量 | 含义 |
|---|---|
| `AGENT_ACTIVITY_ID` | 独立活动 ID，3～80 个字母、数字、点、下划线或横线，不以 SB12/SB-12 开头 |
| `AGENT_BUDGET_LEDGER_PATH` | 独立 JSON 账本的绝对路径，位于持久开发数据目录，不能放入历史 quality-runs |
| `AGENT_BUSINESS_RUN_LIMIT` | 显式授权的业务次数；整场生成、单镜重做及其他 live 任务共用 |
| `AGENT_BUDGET_USD` | 显式授权的美元上限，无默认额度 |
| `AGENT_INPUT_TOKEN_RESERVE`、`AGENT_PROVIDER_INPUT_OVERHEAD_TOKENS` | 每请求输入保守预留及 provider 开销 |

同时设置 `PI_PROVIDER=deepseek`、`PI_MODEL=deepseek-flash`，提供全部 `PI_*_USD_PER_MTOK` 费率（缓存费率为零也须显式设置）和 `PI_PRICING_SOURCE`；正式调用前核对官方模型与适用费率。服务凭据与模型密钥沿用进程安全注入，不写入浏览器。workbench 配置拒绝混用任何 `SB12_*` 变量。`npm run preflight` 的 `readyForLiveRun` 只表示配置通过，无网络请求，也不表示已经获得预算授权。

账本在发请求前同步写入次数和费用预留，记录每次 usage、请求标识、配置费率金额和零自动重试。正常重启承接数据；中断后未取得 usage 的预留保守按全额计入，不能当成供应商确认扣款。账本独占写入，活动 ID、额度、模型或费率与已有账本不一致、账本损坏或写入失败时均拒绝继续请求；不能靠重启或修改配置清零。新授权使用新的独立活动及账本，旧账本保留。

额度拒绝会形成 `run.failed` SSE 终态：`business_run_limit`（次数已满）、`budget_exhausted`（费用不足下次预留）、`business_run_duplicate`（相同 Java 业务任务已执行）、`budget_context_limit`（输入超过预留）或 `budget_uncertain`（账本异常）。已用尽的活动不会调用模型。`npm run budget:check` 包含重启、异常账本、单进程独占、两种分镜任务的费用拒绝及真实 HTTP/SSE 离线回归。

## 历史 SB-12 的真实 LLM 配置（兼容保留）

以下 `SB12_*` 配置仅保留给原固定批次代码兼容，仍限制八次业务运行和最多 USD 1；封存批次不重跑，日常操作使用上面的独立 workbench 配置。

live 支持通过 `PI_PROVIDER` 选择 Anthropic 或 DeepSeek 官方 API；凭据只从 Gateway 本地进程环境读取，不读取 coding-agent 的用户 OAuth 配置。先通过 secret store 向进程注入所选 provider 的凭据和与 Java 匹配的服务凭据，不能把实际密钥写入命令历史、仓库、日志或前端环境。

共同配置（Java 必须已提供可用的持久化链路）：

```powershell
$env:AGENT_MODE = 'live'
$env:JAVA_MODE = 'http'
$env:JAVA_BASE_URL = 'http://127.0.0.1:8080'
$env:SB12_BUDGET_USD = '1'
# AGENT_GATEWAY_TOKEN 已由安全环境注入，须与 Java 一致。
# SB12_INPUT_TOKEN_RESERVE、SB12_PROVIDER_INPUT_OVERHEAD_TOKENS
# 须按冻结的完整提示词、工具 schema、上下文及模型轮次核定后注入，不能直接照抄其他批次。
```

**DeepSeek 官方配置**：`DEEPSEEK_API_KEY` 必须通过安全环境注入；本例使用官方 endpoint `https://api.deepseek.com`，沿本地 Pi 的 `openai-completions` 工具流适配，`thinkingLevel` 关闭。非秘密配置如下：

```powershell
$env:PI_PROVIDER = 'deepseek'
$env:PI_MODEL = 'deepseek-flash'
# DEEPSEEK_API_KEY 已由 secret store 注入当前 Gateway 进程。
$env:PI_INPUT_USD_PER_MTOK = '0.30'
$env:PI_OUTPUT_USD_PER_MTOK = '1.20'
$env:PI_CACHE_READ_USD_PER_MTOK = '0.006'
$env:PI_CACHE_WRITE_USD_PER_MTOK = '0'
$env:PI_CACHE_WRITE_1H_USD_PER_MTOK = '0'
$env:PI_PRICING_SOURCE = 'https://api-docs.deepseek.com/quick_start/pricing/'
```

以上价格为 **2026-10-07** 核对的 `deepseek-flash` 峰值时段每百万 token 美元费率；官方非峰值费率为其一半。本批使用峰值费率作为保守预算及记录依据，不能将计算结果宣称为实际扣款；调用前须再次核对 [DeepSeek 官方价格](https://api-docs.deepseek.com/quick_start/pricing/) 与账号、endpoint、模型是否一致。缓存未命中按普通输入计费，无独立缓存写入收费，故两个写入费率设为 `0`。

**Anthropic 配置仍可使用**：从安全环境注入 `ANTHROPIC_API_KEY` 或 `ANTHROPIC_OAUTH_TOKEN`，并设置该模型对应的全部输入、输出和缓存费率，不能沿用上述 DeepSeek 数值。

```powershell
$env:PI_PROVIDER = 'anthropic'
$env:PI_MODEL = 'claude-haiku-4-5-20251001'
$env:PI_PRICING_SOURCE = 'https://platform.claude.com/docs/en/about-claude/pricing'
# 注入已核对的 PI_INPUT_USD_PER_MTOK、PI_OUTPUT_USD_PER_MTOK、
# PI_CACHE_READ_USD_PER_MTOK、PI_CACHE_WRITE_USD_PER_MTOK、PI_CACHE_WRITE_1H_USD_PER_MTOK。
```

模型 ID 示例不保证账号具有访问权限。选定 provider 后，在同一个受控环境执行无费用检查：

```powershell
npm run preflight
npm run check
npm run provider:check
npm run budget:check
npm run storyboard:check
npm run smoke
# preflight 的 readyForSb12LiveRun 为 true，且 Java 链路独立检查通过后再启动。
npm start
```

`preflight` 只检查配置，不证明密钥有效、账户有余额或 Java 可达；上述确定性检查不调用真实 provider。真实调用前先冻结配置、样本及证据目录。

SB-12 的受控执行限于 **单 Gateway 进程、串行、`generate_storyboard`**，最多 8 个业务 run、USD 1.00 的累计预留控制；SDK 自动重试为 0，失败请求保守消耗预留费用。业务 run 不等于 provider 请求，每个真实预检、失败尝试与重试都要纳入账本和本批限额。每个 run 达到终态后立即导出 `GET /agent/runs/{runId}/ledger` 和 Java 保存的实际产物；运行中的 ledger 不能作为完整证据。账本、计数和预算均在内存，run 约 30 分钟后清理；进程退出或崩溃必须停止批次并核账，不得通过重启清零后继续。本批不能用其他 taskType 做付费诊断，也不能把 SB-12 的 8 次额度当成后续用户试用额度。

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
