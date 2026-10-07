# 单场景分镜工作台运行与试用说明

适用范围：SB-13 小范围试用准备。本文只说明本仓库当前的单场景分镜工作台；它不替代部署安全评审，也不包含真实密钥、密码、JWT、执行令牌或用户剧本。

## 运行模式与当前门槛

| 模式 | 用途 | 是否可计入真实用户试用 |
| --- | --- | --- |
| fixtures | 前端页面状态演示；`VITE_STORYBOARD_DATA_SOURCE=fixtures` | 否。数据在浏览器内，不能验证 Java、Gateway 或模型。 |
| mock HTTP | Java、Node、PostgreSQL 和浏览器的确定性技术闭环；Gateway 使用 `AGENT_MODE=mock` | 否。SB-11 已用此模式验证流程和异常，不证明模型质量。 |
| live HTTP | Java 保存产物、Gateway 真实 provider、工作台浏览器 | 是，但仅在 SB-12 的真实质量/预算门槛通过后可开始。 |

当前状态：SB-11 工程闭环已通过复验（含真实 PostgreSQL、Java→Gateway HTTP 与浏览器；Gateway 为 `AGENT_MODE=mock`）。完整验收命令和 R1～R8 证据见 `REPAIR_ACCEPTANCE.md`。SB-12 的 01-A、01-B 已 completed，01-8-A 在保存八镜后因最终回执格式 failed，批次已停止。累计 3 run / 9 provider requests / 0 retry，配置费率计入 USD 0.007482360，余额 USD 0.992517640；后五项未提交。不得重跑已提交样本或从 01-A 旧余额重新启动七项。

## 运行前检查

1. 使用 JDK 21（JDK 17 无法编译本项目）、Node.js 22.19+、Docker Desktop 和可用的 Chromium/Chrome。
2. 在受控环境提供独立的 PostgreSQL 数据库、每位用户的测试账号，以及不含真实密钥的日志采集位置。
3. 确认端口没有被占用。默认示例为 PostgreSQL `55432`、Java `8080`、Gateway `3001`、Vite `5173`；SB-11 脚本会使用隔离端口 `15432/18080/13001/15173`。
4. 在 Gateway 所在受控进程中设置 `AGENT_MODE=live`、`JAVA_MODE=http`、明确的 `PI_PROVIDER` 和 `PI_MODEL`。当前支持 Anthropic 和 DeepSeek 官方 API；前者注入 `ANTHROPIC_API_KEY` 或 `ANTHROPIC_OAUTH_TOKEN`，后者注入 `DEEPSEEK_API_KEY`。先完成下述无费用预检和契约检查，再执行 SB-12 真实调用；provider 接入通过不等于模型质量已通过。
5. 生成一个随机 `AGENT_GATEWAY_TOKEN` 和独立随机 `AGENT_EXECUTION_SECRET`，通过受控环境注入。不要复用 JWT secret、不要使用 `application.yaml` 的 demo 默认值、不要把值写入 `.env`、文档或前端变量。
6. 确认试用场景和创作要求符合 `PRODUCT_ACCEPTANCE.md` 的范围：场景 ≤8,000 个 LF 字符、要求 ≤1,000 个 LF 字符、一次只处理一个已解析场景和 4～8 个镜头。

## 配置项

### Java / PostgreSQL

| 配置名 | 用途 |
| --- | --- |
| `SERVER_PORT` | Java HTTP 端口；默认 `8080`。 |
| `SPRING_DATASOURCE_URL`、`SPRING_DATASOURCE_USERNAME`、`SPRING_DATASOURCE_PASSWORD` | PostgreSQL 连接。 |
| `AGENT_GATEWAY_URL` | Java 调用的 Gateway 地址，例如 `http://127.0.0.1:3001`。 |
| `AGENT_GATEWAY_TOKEN` | Java 到 Gateway 的服务凭据；须与 Gateway 一致。 |
| `AGENT_EXECUTION_SECRET` | Java 签发内部执行能力令牌的独立密钥。 |
| `JWT_SECRET`、`JWT_EXPIRATION_MS`、`JWT_REFRESH_TOKEN_DURATION_MS` | 现有用户鉴权配置；为试用环境单独设置。 |

### Gateway

| 配置名 | 用途 |
| --- | --- |
| `PORT` | Gateway HTTP 端口；默认 `3001`。 |
| `AGENT_GATEWAY_TOKEN` | 接受 Java 调度请求的服务凭据；须与 Java 一致。 |
| `AGENT_MODE` | `live` 才会调用真实 provider；`mock` 仅限技术验证。 |
| `JAVA_MODE` | 试用设为 `http`，以便经 Java 内部接口读取冻结上下文并保存产物。 |
| `JAVA_BASE_URL` | Java 地址，例如 `http://127.0.0.1:8080`。 |
| `PI_PROVIDER`、`PI_MODEL` | 固定本批 provider 和确切模型；DeepSeek 官方本批使用 `deepseek` / `deepseek-flash`，Anthropic 使用 `anthropic` / 已获授权的模型 ID。 |
| `DEEPSEEK_API_KEY` | DeepSeek 官方凭据，仅通过 secret store/受控运行环境注入 Gateway 进程。 |
| `ANTHROPIC_API_KEY`、`ANTHROPIC_OAUTH_TOKEN` | 选择 Anthropic 时注入其中一种凭据；不要沿用其他 provider 的费率。 |
| `SB12_BUDGET_USD` | 本批累计预留上限，最多 `1` 美元；不能通过重启进程重置后续跑。 |
| `SB12_INPUT_TOKEN_RESERVE`、`SB12_PROVIDER_INPUT_OVERHEAD_TOKENS` | 根据冻结后的完整提示词、工具 schema、业务上下文和模型轮次核定的保守输入预留与 provider 开销；执行前确定，本文不给出未经核定的通用数值。 |
| `PI_INPUT_USD_PER_MTOK`、`PI_OUTPUT_USD_PER_MTOK`、`PI_CACHE_READ_USD_PER_MTOK`、`PI_CACHE_WRITE_USD_PER_MTOK`、`PI_CACHE_WRITE_1H_USD_PER_MTOK` | 当前模型的每百万 token 美元费率；使用官方来源核对，DeepSeek 两个独立缓存写入费率为 `0`。 |
| `PI_PRICING_SOURCE` | 本批核对的官方价格页面，连同核对日期记录在运行证据中。 |

### 工作台

| 配置名 | 用途 |
| --- | --- |
| `VITE_JAVA_API_BASE` | 浏览器调用的 Java API 地址，例如 `http://127.0.0.1:8080`。 |
| `VITE_STORYBOARD_DATA_SOURCE` | 试用时必须未设置或非 `fixtures`；`fixtures` 只用于本地演示。 |

浏览器只持有用户 JWT 并调用 Java；**绝不**设置 Gateway 地址、Gateway token、执行令牌或模型密钥为 `VITE_*` 变量。

## 启动顺序（PowerShell）

### SB-12 样本 01-A 一键受控执行

若当前 PowerShell 7.2+ 已由受控 secret store 设置了 `DEEPSEEK_API_KEY`，且 `JAVA_HOME` 指向 JDK 21，可从仓库根目录直接执行。Windows PowerShell 5.1 须先启动 PowerShell 7 子进程，它会继承当前窗口的环境变量：

```powershell
Set-Location D:\desktop\screenplay-agent-backend
.\scripts\run-sb12-01a-live.ps1
```

脚本不会重新要求、回显或持久化 API key。它使用隔离端口 `15433/18081/13002`，在唯一一次真实 01-A run 前完成无费用 preflight、离线 provider/预算契约检查以及 PostgreSQL、Java、Gateway 连通检查。它会保留进程、容器和 `docs\storyboard-mvp\quality-runs\<日期>\01-A\` 证据以供核验；同名容器或证据目录已存在时会停止，绝不覆盖旧记录。手工停止可使用脚本输出的进程 PID，并在确认导出完成后执行 `docker rm --force screenplay-sb12-01a-postgres`。

本固定样本的保守配置为 `SB12_INPUT_TOKEN_RESERVE=50000`、`SB12_PROVIDER_INPUT_OVERHEAD_TOKENS=2000`、`maxOutputTokens=8192`、`maxRetries=0`。该值针对单场景样本 01 的冻结上下文、系统提示词、两个可见工具 schema 及最多三个模型轮次；任何请求实际上下文超过预留都会被 Gateway 在发送前拒绝。它不可以直接复用于后续样本，后续样本须在已导出的账本基础上重新核定输入上限和剩余额度。

### 01-A 准备阶段中断与重跑

启动脚本会先检查 JDK 21、依赖、Docker 引擎、隔离端口和同名容器，再创建正式证据目录。Java 构建统一使用所选 JDK，并在仓库根目录执行；完整构建输出位于 `01-A/logs/java-package.log`。

需要单独排查准备阶段时，可在上述 PowerShell 7 环境执行 `./scripts/run-sb12-01a-live.ps1 -PrepareOnly`。它只做配置检查、离线契约检查和 Java 打包，不启动数据库或服务，也不提交生成。记录放在系统临时目录 `sb12-01a-preparation-<随机标识>`，不能当作真实 01-A 证据。Java 打包跳过测试，成功不等于 Java 测试或真实模型验收通过。

每个阶段会输出进度并更新 `01-A.launch-state.json`；异常阶段写入 `01-A.launch-failure.json`。生成 POST 之前先保存 `01-A.generation-request.json` 并标记 `submissionPossible=true`，收到响应后保存 `01-A.accepted.json`。一旦可能已提交，即使没有最终产物，也应保留数据库与服务，先查 run 和账本，不能通过移走目录后重跑规避已用预算。

遇到目录已存在时，不删除或覆盖旧记录。只有核实目录仅含准备材料、未到生成提交阶段、无活跃启动脚本/专用服务/数据库容器后，才可把整个目录归档并校验文件哈希，再进行首次真实提交。2026-10-07 的这次恢复已完整保留 11 个原始文件，详情见 [恢复记录与哈希清单](quality-runs/20261007/01-A.preflight-only-20261007-192628/RECOVERY.json)。旧记录未保存原始中断错误，不能据此断言中断的具体原因。

若已提交生成、只在 `exporting-evidence` 阶段报错，先查看 `01-A.java-run.json`，并保留服务、数据库和账本。PowerShell 的 GBK 原生命令解码可能破坏 PostgreSQL UTF-8 JSON；导出应采用 ASCII Base64 传输与严格 UTF-8 解码，并先保存最终账本。2026-10-07 的真实 01-A 属于此情况：同一次已完成 run 的证据现已补齐，原失败记录仍保留，当前恢复状态与哈希以 [导出恢复记录](quality-runs/20261007/01-A/01-A.export-recovery.json) 为准。不要据旧失败阶段文件再次提交生成。

编码修复的无费用回归命令为 `./scripts/verify-sb12-postgres-json.ps1`，使用现有隔离 PostgreSQL 容器，只执行只读 SELECT。覆盖 UTF-8 / GBK 下中文、引号、反斜杠、换行及长文本完整性，以及空记录、多记录、SQL 错误、非法 JSON / runId 的拒绝；不启动服务、不修改数据库或证据、不请求模型。

### SB-12 后五项的受控承接（当前有效入口）

当前累计为 **3 run / 9 provider requests / 0 retry / USD 0.007482360**，余额 **USD 0.992517640**，未结预留为 `0`。`01-A`、`01-B` 已 completed；`01-8-A` 必须保持 failed 且不重跑。当前唯一可提交的顺序是 `01-8-B → 02-A → 02-B → 03-A → 03-B`。

先由 D 完成 [后五项复验单](SB12_FINAL_FIVE_REVIEW.md)，并将其中唯一授权行改为 `后五项继续执行授权：APPROVED`。未获此独立授权时，下面的入口会在创建服务或提交请求前停止。授权后，在原本已配置 `DEEPSEEK_API_KEY` 的 PowerShell 7 窗口运行：

```powershell
Set-Location D:\desktop\screenplay-agent-backend
$env:JAVA_HOME = 'C:\Users\tcf\.jdks\ms-21.0.9'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
& 'C:\Users\tcf\.cache\codex-runtimes\codex-primary-runtime\dependencies\native\powershell\pwsh.exe' `
  -NoProfile -File 'D:\desktop\screenplay-agent-backend\scripts\run-sb12-final-five-live.ps1'
```

该入口使用新的 `15435/18083/13004` PostgreSQL/Java/Gateway 端口与 `screenplay-sb12-final-five-postgres` 容器，会启动当前含单一 JSON 代码块回执兼容修复的 Gateway 源码。它从 `01-8-A` 最新终态账本承接预算，同时核对 `remaining-batch/batch-summary.json` 的 9 次累计请求；任一已存在的后五项证据目录、端口/容器冲突、预算不一致、失败、超时或导出异常都会停止，不能重试或清零。

真实提交前可运行无费用入口验证：`pwsh -NoProfile -File .\scripts\verify-sb12-final-five-entry.ps1`。它会测试 D 授权闸门、预算承接、严格回执修复、重复证据拒绝和空白人工逐镜评价模板，且不会启动服务或请求模型。

### 历史说明：SB-12 剩余七项的首次承接

**以下七项启动命令仅保留为首次启动的历史说明，不能再次执行。** 它们从 01-A 承接，会错误忽略 01-B 和 01-8-A 已占用的额度。

仅在 01-A 的 `SB12_01A_ACCEPTANCE.md`、`01-A.java-run.json` 与 `01-A.provider-ledger.json` 都已保存在本项目，并且 D 在验收文档增加单独一行 `继续执行授权：APPROVED` 后，才可从同一个已设置 `DEEPSEEK_API_KEY` 的 PowerShell 执行：

```powershell
Set-Location D:\desktop\screenplay-agent-backend
.\scripts\run-sb12-remaining-live.ps1
```

建议在保有 `DEEPSEEK_API_KEY` 的原 PowerShell 窗口使用明确的 PowerShell 7 与 JDK 21 路径；该命令继承密钥但不显示或写入它：

```powershell
Set-Location D:\desktop\screenplay-agent-backend
$env:JAVA_HOME = 'C:\Users\tcf\.jdks\ms-21.0.9'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
& 'C:\Users\tcf\.cache\codex-runtimes\codex-primary-runtime\dependencies\native\powershell\pwsh.exe' `
  -NoProfile -File 'D:\desktop\screenplay-agent-backend\scripts\run-sb12-remaining-live.ps1'
```

如 01-A 证据存放在非默认位置，显式传入路径（仍不得传入任何密钥）：

```powershell
.\scripts\run-sb12-remaining-live.ps1 `
  -AcceptancePath 'D:\desktop\screenplay-agent-backend\docs\storyboard-mvp\SB12_01A_ACCEPTANCE.md' `
  -JavaRunPath 'D:\desktop\screenplay-agent-backend\docs\storyboard-mvp\quality-runs\<日期>\01-A\01-A.java-run.json' `
  -LedgerPath 'D:\desktop\screenplay-agent-backend\docs\storyboard-mvp\quality-runs\<日期>\01-A\01-A.provider-ledger.json'
```

脚本会把 01-A 的业务 run 数和保守已计入费用写入新的 Gateway 初始余额，因而重启不能清零；若剩余额度不能容纳请求、任一运行失败或已有证据目录，脚本会停止并保留全部记录。它只执行剩余的 7 个计划 run，不会重复提交 01-A。

剩余启动器提供 `-ResumePreparedBatch -ResumeCheckOnly` 检查和 `-ResumePreparedBatch` 恢复入口，仅适用于原服务仍存活、尚未创建业务项目或提交任何生成的准备现场。必须保有原 Gateway 服务凭据、核实原进程预算配置和数据库关联；HTTP 健康或鉴权成功本身不能证明预算连续。脚本校验 D 授权、承接哈希、固定端口 `15434/18082/13003`、零业务数据库及无 run 目录，并持有独占锁。检查模式不发 POST；实际恢复不重建服务或重置密钥/预算。任何已有业务或提交证据都会拒绝恢复，不能用此开关重跑本次 failed run。

运行阶段写入 `batch-stage.json`，错误保存独立的 `batch-failure-*.json`；生成 POST 前保存提交标记、响应到达后立即保存 accepted 信息。2026-10-07 的准备恢复及本批累计状态分别见 [prepared-recovery.json](quality-runs/20261007/remaining-batch/prepared-recovery.json) 和 [batch-summary.json](quality-runs/20261007/remaining-batch/batch-summary.json)。

以下命令使用占位符；在受控终端替换并从安全变量存储注入，切勿把真实值粘贴回本文档或 shell 历史。启动前确保已安装本仓库锁定的依赖；不要在试用现场临时升级依赖。

1. 启动 PostgreSQL（本地 demo 数据库）：

```powershell
Set-Location D:\desktop\screenplay-agent-backend
docker compose up -d db
```

2. 在第一个终端启动 Java。`<...>` 均代表由受控环境设置的值：

```powershell
Set-Location D:\desktop\screenplay-agent-backend
$env:JAVA_HOME = '<JDK-21-home>'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$env:SPRING_DATASOURCE_URL = '<jdbc-postgresql-url>'
$env:SPRING_DATASOURCE_USERNAME = '<db-user>'
$env:SPRING_DATASOURCE_PASSWORD = '<db-password>'
$env:AGENT_GATEWAY_URL = 'http://127.0.0.1:3001'
$env:AGENT_GATEWAY_TOKEN = '<shared-gateway-token>'
$env:AGENT_EXECUTION_SECRET = '<independent-execution-secret>'
$env:JWT_SECRET = '<pilot-jwt-secret>'
.\mvnw.cmd spring-boot:run
```

3. 在第二个终端启动 live Gateway。以下采用 DeepSeek 官方 API；通过 secret store 注入 `DEEPSEEK_API_KEY` 和 `AGENT_GATEWAY_TOKEN` 后再执行非秘密配置片段：

```powershell
Set-Location D:\desktop\screenplay-agent-backend\examples\pi-agent-gateway
$env:PORT = '3001'
$env:AGENT_MODE = 'live'
$env:JAVA_MODE = 'http'
$env:JAVA_BASE_URL = 'http://127.0.0.1:8080'
$env:PI_PROVIDER = 'deepseek'
$env:PI_MODEL = 'deepseek-flash'
$env:SB12_BUDGET_USD = '1'
$env:PI_INPUT_USD_PER_MTOK = '0.30'
$env:PI_OUTPUT_USD_PER_MTOK = '1.20'
$env:PI_CACHE_READ_USD_PER_MTOK = '0.006'
$env:PI_CACHE_WRITE_USD_PER_MTOK = '0'
$env:PI_CACHE_WRITE_1H_USD_PER_MTOK = '0'
$env:PI_PRICING_SOURCE = 'https://api-docs.deepseek.com/quick_start/pricing/'
# DEEPSEEK_API_KEY、AGENT_GATEWAY_TOKEN 已由安全环境注入。
# SB12_INPUT_TOKEN_RESERVE、SB12_PROVIDER_INPUT_OVERHEAD_TOKENS
# 已按冻结上下文及模型轮次核定并注入，不能套用任意示例值。
npm run preflight
# 核对 readyForSb12LiveRun=true，并完成下述无费用检查后再启动。
npm run start
```

DeepSeek 使用官方 endpoint `https://api.deepseek.com`，经本地 Pi 的 `openai-completions` 适配处理工具流，`thinkingLevel` 关闭。上述为 **2026-10-07** 核对的 `deepseek-flash` 峰值时段费率；官方非峰值费率为其一半。本批按峰值做保守预算与记录，计算费用不是实际扣款凭证；调用前再次核对 [官方价格页面](https://api-docs.deepseek.com/quick_start/pricing/)。缓存未命中按输入计费，无独立缓存写入收费，两个写入费率设为 `0`。

如使用 Anthropic，保留同一 Java 配置，改为 `PI_PROVIDER=anthropic` 和获授权的精确 `PI_MODEL`，从安全环境注入 `ANTHROPIC_API_KEY` 或 `ANTHROPIC_OAUTH_TOKEN`，并替换全部五项费率及 `PI_PRICING_SOURCE` 为 [Anthropic 官方价格](https://platform.claude.com/docs/en/about-claude/pricing) 对应模型的记录；不能保留上述 DeepSeek 费率。`preflight` 只验证环境配置，不发真实请求，也不验证密钥有效性或服务可达性。

4. 在第三个终端启动工作台。试用不得设置 `VITE_STORYBOARD_DATA_SOURCE=fixtures`：

```powershell
Set-Location D:\desktop\screenplay-agent-backend\apps\storyboard-web
Remove-Item Env:VITE_STORYBOARD_DATA_SOURCE -ErrorAction SilentlyContinue
$env:VITE_JAVA_API_BASE = 'http://127.0.0.1:8080'
npm run dev -- --host 127.0.0.1 --port 5173 --strictPort
```

5. 验证 Java `http://127.0.0.1:8080/actuator/health`、Gateway `http://127.0.0.1:3001/health` 和工作台 `http://127.0.0.1:5173` 均可访问。SB-12 阶段使用本批固定样本执行首个真实生成；每次真实预检都计入本批预算和次数，不能另外增加未记账的试生成。只在 Java run 为完成、存在 `resultRef` 且详情页显示保存产物时判为链路成功；内容质量仍需真实评价。SB-13 必须在 SB-12 验收后，以另外确认的试用运行配置和预算开始。

### SB-12 受控执行与账本导出

- 最多 8 个业务 run、累计 USD 1.00；一个业务 run 可包含多个 provider 请求。仅单 Gateway 进程串行执行 `generate_storyboard`，不通过其他 taskType 做付费诊断或绕开本批控制。
- SDK 自动重试固定为 0；失败请求仍可能产生费用，账本保守按已预留费用计入。真实预检、失败尝试及任何重新发起的 run 均保留证据并占用相应额度，不因失败而抹除。
- 每个 run 进入终态后，立即通过受服务凭据和用户归属校验保护的 `GET /agent/runs/{runId}/ledger` 导出账本，并保存 Java 实际产物、输入/提示词/schema 快照及标识。运行中的 ledger 尚不完整，不能当作最终无用量证据；导出时过滤服务凭据与执行令牌。
- 预算计数和账本目前只在内存中，run 约 30 分钟后清理。进程退出、重启或崩溃时立即停止批次，先依据已有导出和 provider 证据核账；禁止重启清零后继续生成。无法核实剩余额度时保持停止。

如需保留运行日志，保存 Java/Gateway 的时间戳、runId、resultRef、状态和 provider usage；过滤 `Authorization`、`executionToken`、API key、刷新 token 和剧本文本。会话结束后关闭三项进程并按本地数据保留政策停止或删除测试数据库。

## 试用前技术验证

以下确定性检查不花费模型费用，也不能替代 SB-12 或真实试用：

```powershell
Set-Location D:\desktop\screenplay-agent-backend
node .\docs\storyboard-mvp\fixtures\validate-fixtures.mjs

Set-Location .\examples\pi-agent-gateway
npm run check
npm run provider:check
npm run budget:check
npm run storyboard:check
npm run smoke

Set-Location ..\..\apps\storyboard-web
npm run build
```

完整 mock HTTP 浏览器闭环使用以下命令；它创建临时 PostgreSQL 容器和进程后自动清理：

```powershell
Set-Location D:\desktop\screenplay-agent-backend
$env:JAVA_HOME = '<JDK-21-home>'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\scripts\verify-storyboard-http-e2e.ps1
```

在启动真实试用前，产品负责人还须核对 `PILOT_REPORT.md` 的准入条件；用户执行和记录方法以该文件为准。

## 现场演示与故障处置

- 使用流程：登录 → 创建/选择项目 → 粘贴并解析剧本 → 选择场景 → 选 4～8 镜头/填写要求 → 生成 → 以 Java `resultRef` 打开分镜 → 编辑保存 → 重做单镜并比较/采纳或放弃 → 刷新 → 复制 Prompt 或导出 Markdown。
- 如果 run 失败、取消或超时，保留 `runId`、页面状态和脱敏日志；不要通过 Swagger、直接 SQL、手改数据库、直接调用内部接口或重启服务来“补完成”用户流程。按用户选择可重试新任务或结束会话，并记录为未完成。
- 如果 SSE 断开，页面应依据 Java run 查询恢复；观察员只记录现象，不应将断线直接解释为取消。
- 如果保存或采纳出现 revision 冲突，保留用户本地编辑，向用户说明服务端有较新版本；不要默默覆盖。
- 如果发现真实模型严重改变剧情事实、缺镜、缺 Prompt、越界引用或未保存 resultRef，停止该次用户试用，保留脱敏证据并回到 SB-12/相应缺陷处理；不得将 mock 结果替换为真实结果。

## 交接给产品负责人

1. 先让技术值守完成 live 预检，并将 SB-12 账本状态从 `BLOCKED` 更新为有真实 evidence 的结论。
2. 邀请 3～5 位目标用户；每位用自己的一个场景，使用独立账号完成 `PILOT_REPORT.md` 的脚本。观察员不代操作。
3. 每完成一位，即刻填入单用户模板，区分模型等待与用户操作时间，并记录镜头保留/修改/重做、真实复制/导出和用途。
4. 完成至少三位后，按记录证据排序三个最影响完成任务的问题；只将有记录支持的需求转入下一轮。
