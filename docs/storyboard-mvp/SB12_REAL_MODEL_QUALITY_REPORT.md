# SB-12 真实模型质量与预算验证

状态：**READY_FOR_D_REVIEW — 2026-10-07 01-A、01-B completed；01-8-A 因最终回执格式 failed（已保存八镜）；后五项安全续跑入口及无费用验证已准备，尚未获 D 对后五项的新授权，未提交新的 provider 请求。累计 3 run / 9 provider requests / 0 retry。**
执行目录：`D:\desktop\screenplay-agent-backend`
当日预检证据：[quality-runs/20261007/preflight.json](quality-runs/20261007/preflight.json)；供真人逐镜评价的空白材料：[quality-runs/20261007/HUMAN_EVALUATION_PACKET.md](quality-runs/20261007/HUMAN_EVALUATION_PACKET.md)。

本报告将 2026-10-06 的缺配置记录视为历史参考；下方原预检表及 `preflight.json` 保留 2026-10-07 切换 provider 前的快照。下方启动交接、承接审计中的“0 run / 未执行”描述均是执行前历史记录；当前事实以紧接的真实运行记录为准。mock、fixture、契约测试和历史结果均不作为真实模型质量证据。

## 2026-10-07：后五项安全续跑准备（本轮无真实模型请求）

- 新入口：[run-sb12-final-five-live.ps1](../../scripts/run-sb12-final-five-live.ps1) 固定委派 `FinalFive` 计划，唯一顺序为 **`01-8-B → 02-A → 02-B → 03-A → 03-B`**；不能包含或重跑 `01-A`、`01-B`、`01-8-A`。它使用新 Java/Gateway/PostgreSQL 进程（默认端口 `18083/13004/15435`、容器 `screenplay-sb12-final-five-postgres`），因此会启动包含 JSON 代码块回执修复的 Gateway 源码，而不复用尚未部署修复的保留 Gateway。
- 启动器只接受 `01-8-A.provider-ledger.json` 与同次 failed Java run 作为承接源，并强制核对 **3 business runs / 9 provider requests / 0 retry / USD 0.007482360 charged / USD 0.992517640 remaining / USD 0 reserved / 8 run / USD 1.00 cap**。`01-8-A` 必须保持终态 `failed`，但其三个 completed provider requests 仍完整计入；承接不从 01-A 的旧余额重新开始。
- 每个计划目录存在即拒绝执行；入口还在创建服务或提交 POST 前检查五个目录。真实执行时每项仍按“最终 ledger → 冻结 context / Java 产物 / 自动预检查 / 评价材料”顺序导出；任一业务 run 失败、超时、预算拒绝或导出异常即保留证据并停止后项，零自动重试。
- 新的 [D 复验单](SB12_FINAL_FIVE_REVIEW.md)默认是 `PENDING_D_REVIEW`，不是授权。它要求 D 独立改为精确行 `后五项继续执行授权：APPROVED`；没有该行时入口在启动服务前拒绝。此门槛不伪造真人评价，也不改变历史 failed 状态。
- 无费用验证脚本 [verify-sb12-final-five-entry.ps1](../../scripts/verify-sb12-final-five-entry.ps1)以临时批准夹具调用 `-ValidateOnly`：实际读取 01-8-A 账本并验证承接、固定顺序、严格回执修复存在、重复目录拒绝和五项空白逐镜评价模板。验证模式不读取 `DEEPSEEK_API_KEY`、不启动服务、没有 Java POST / Gateway 请求 / provider 请求；临时文件在验证结束后删除。

### 本轮无费用验证（实测）

| 检查 | 实测结果 |
| --- | --- |
| PowerShell 入口解析 | 通过：`run-sb12-remaining-live.ps1`、`run-sb12-final-five-live.ps1`、`verify-sb12-final-five-entry.ps1` 均通过 PowerShell AST 解析。 |
| 五项入口流程 | 通过：`pwsh -NoProfile -File scripts/verify-sb12-final-five-entry.ps1` 返回 `status=passed`、`providerRequestsSent=0`。先验证默认 `PENDING_D_REVIEW` 被精确授权闸门拒绝，再验证固定五项顺序、01-8-A 失败终态账本承接、回执修复存在。 |
| 累计请求核对 | 通过：01-8-A 的终态 ledger 自身包含该 run 的 3 次请求；脚本同时核对受保护 `remaining-batch/batch-summary.json` 的跨进程累计 9 次请求、0 retry、USD 0.007482360。预算源仍是最新 01-8-A ledger。 |
| 防重复与评价材料 | 通过：临时 `01-8-B` 目录被入口在启动前拒绝；生成的预览包含 5 个 run、每个目标镜头的 `直接用 / 小改可用 / 需要重做` 空白栏。 |
| Gateway 离线检查 | 通过：`npm run check`、`npm run provider:check`、`npm run budget:check` 均成功；provider 契约明确验证严格 plain/fenced 回执、零自动重试和“no external HTTP requests”。 |

以上均为无费用工程验证，不是 Java 真正运行、真人评价或供应商扣款对账，也没有改变当前累计值。
- D 复验并写入授权后，应在保留 `DEEPSEEK_API_KEY` 的原 PowerShell 7 窗口执行：

```powershell
Set-Location D:\desktop\screenplay-agent-backend
$env:JAVA_HOME = 'C:\Users\tcf\.jdks\ms-21.0.9'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
& 'C:\Users\tcf\.cache\codex-runtimes\codex-primary-runtime\dependencies\native\powershell\pwsh.exe' -NoProfile -File .\scripts\run-sb12-final-five-live.ps1
```

该命令不要求重新输入、不显示或写入 `DEEPSEEK_API_KEY`；运行前若密钥、授权、端口/容器、账本、预算或构建检查不满足会停止。真实运行之后仍须由 D 复验技术证据，并由真人填写评价；供应商扣款对账同样仍待完成。

## 2026-10-07 20:39：恢复准备现场，执行到 01-8-A 后停止

- 用户重新启动时触发 `remaining-batch` 防覆盖保护。现场核实专用 Java/Gateway/PostgreSQL 已健康启动，但数据库 run/project/context 均为 0，七个 run 目录均不存在。承接文件、副本、D 授权哈希以及原进程的服务关联、预算初始化值一致。[准备现场恢复记录](quality-runs/20261007/remaining-batch/prepared-recovery.json)
- 启动器新增 `-ResumePreparedBatch` / `-ResumeCheckOnly`，核实未提交后复用原服务，保留独占执行锁、阶段、提交标记和 accepted 响应。检查模式、错误端口拒绝、并发执行拒绝通过。另修复人工评价模板字符串的中文引号参数解析错误；该次准备失败发生在登录前，未产生业务 run 或 provider 请求。
- `01-B` 实际 completed，Java 保存 6 镜，3 次 provider 请求、0 重试，本 run 配置费率费用 **USD 0.002221284**。
- `01-8-A` 实际 failed，Gateway 错误为 `invalid_structured_output`。保存工具已成功，Java 保存了 8 镜；模型最终回执的 ID 与保存产物一致，但外层包裹 Markdown JSON 代码块，旧 runtime 直接 `JSON.parse` 导致拒绝。保留原始失败状态与费用，未重试。[失败诊断及实际回执](quality-runs/20261007/01-8-A/01-8-A.failure-diagnosis.json)、[补取的八镜产物](quality-runs/20261007/01-8-A/01-8-A.java-storyboard.json)
- 源码已兼容单个完整 JSON 代码块，仍要求严格字段结构及与真实成功保存回执完全一致；不接受额外说明、多个代码块、伪造 ID 或没有成功保存的回执。`npm run check`、`npm run provider:check` 及该真实回执的离线回放通过。原 Gateway 未重启，修复尚未部署到保留的运行进程，原 failed 状态未改写。
- 失败 run 的最终账本已在后续产物导出之前保存；其 3 次 provider 请求均完成、0 重试，本 run 配置费率费用 **USD 0.002603928**。连同 01-A，累计 **3 run / 9 requests / USD 0.007482360**，剩余 **5 run / USD 0.992517640**，未结预留 **USD 0**。失败 run 未从预算中扣除，也未把模型费用等同于供应商实际扣款。[累计核账与逐项状态](quality-runs/20261007/remaining-batch/batch-summary.json)
- 后续 `01-8-B → 02-A → 02-B → 03-A → 03-B` 均未提交。`-ResumePreparedBatch` 仅用于未提交的准备现场，不能用于这次已执行两项的批次；旧的从 01-A 承接七项命令也不能再运行。下一步先独立复核回执兼容修复及已用账本，再为后五项设计承接 **3 run / USD 0.007482360** 的继续执行路径。
- 原运行实现按配置中记录的 SHA-256 补存于 [implementation-at-run](quality-runs/20261007/remaining-batch/implementation-at-run/manifest.json)，用于区分实际运行代码与失败后修复。真人评价及供应商扣款对账仍待完成。

## 历史记录 — 2026-10-07 19:30：01-A 已完成，导出故障已恢复

- Java run `05d93636-7cf1-43db-beed-3dd2047291af` 为 `completed`，持久化时间范围 `11:29:56.035225Z` 至 `11:30:06.455535Z`，运行耗时 **10,420.310 ms**（不含构建、服务启动和证据恢复）。[Java run](quality-runs/20261007/01-A/01-A.java-run.json)
- Java 已保存分镜 `d9a4954e-a67b-4532-b998-720a11381163`，共 **6 镜**。冻结场景 SHA-256 与提交前快照相同，创作要求与生成请求一致；每镜原文引用均能定位，图像及视频 Prompt 均非空。这些是自动预检查结果，不能替代剧情与可拍性的真人评价。[分镜](quality-runs/20261007/01-A/01-A.java-storyboard.json)、[自动预检查](quality-runs/20261007/01-A/01-A.automatic-precheck.json)
- Gateway run `a1c93b20-3717-4612-9e64-a288f3728ee8` 已导出最终账本：**1 个业务 run / 3 个 provider 请求 / 0 次重试**，模型 `deepseek-flash`；按本批配置费率计入 **USD 0.002657148**，剩余额度 **USD 0.997342852**，无未结预留。该金额是账本计算值，未与供应商扣款单对账。[逐请求账本](quality-runs/20261007/01-A/01-A.provider-ledger.json)
- 原脚本在 `exporting-evidence` 阶段失败：PostgreSQL 返回 UTF-8 JSON，被 PowerShell 原生命令输出的 GBK/936 编码解码，造成中文损坏及 position 114 的 JSON 解析错误。已用同一只读查询精确复现，数据库 JSON 本身合法。改用 ASCII Base64 传输，再显式按 UTF-8 解码，已补取同一次 run 的冻结上下文和保存产物。
- 恢复期间没有新增生成请求、没有重启 Java/Gateway、没有清零预算。原始启动状态和失败记录保留；补取的账本、上下文、分镜、自动预检查及哈希详见 [导出恢复记录](quality-runs/20261007/01-A/01-A.export-recovery.json)。
- 下一步由 D 基于新增证据复核 [独立验收报告](SB12_01A_ACCEPTANCE.md)，真人填写 [实际六镜评价材料](quality-runs/20261007/01-A/01-A.HUMAN_EVALUATION.md)。本次修复没有代签继续执行授权，也没有执行剩余批次；**不宣布 SB-12 整体通过**。

## 历史记录 — 2026-10-07 执行前：D 已授权，剩余七项待受控终端执行

- 已按 [D 的独立复验](SB12_01A_ACCEPTANCE.md)和原始账本确认承接值：**1 业务 run / 3 provider request / 0 retry / USD 0.002657148 已计费 / USD 0.997342852 余额 / USD 0 未结预留**。这只是按配置费率计算的账本金额；DeepSeek 实际账单和逐请求 provider request ID 仍未对账、不能补造。
- D 已写入 `继续执行授权：APPROVED`。真人逐镜评价仍为待完成，且不因本授权或自动预检查而通过。
- 剩余启动器已完成无费用复核：强制 PowerShell 7.2+、解析并设置 JDK 21 `JAVA_HOME`；根目录 Java 编译使用 `-Dspring-boot.repackage.skip=true` 与独立 runtime classpath，避免覆盖仍由 01-A 进程占用的普通 jar；PostgreSQL UTF-8/Base64 只读回归在 code page 65001 和 936 均通过；账本先于冻结 context 导出，端口 `15434/18082/13003` 与 `screenplay-sb12-remaining-postgres` 会冲突即停止。
- 当前智能体进程只确认到 `DEEPSEEK_API_KEY=absent`（未读取或输出值），所以没有提交 `01-B` 及之后的真实调用。`01-B`、`01-8-A`、`01-8-B`、`02-A`、`02-B`、`03-A`、`03-B` 均为**待受控终端执行**，而不是失败或完成。
- 在用户原有密钥终端执行 [run-sb12-remaining-live.ps1](../../scripts/run-sb12-remaining-live.ps1) 后，它会严格串行处理上述七项；任意失败、超时、预算拒绝或导出异常即停止后续项，且在每项终态后先写最终 ledger。证据将写入 `quality-runs/20261007/<run>/`，批次承接/进度和人工评价材料位于 `quality-runs/20261007/remaining-batch/`。

## 历史记录 — 2026-10-07 执行前：剩余批次承接审计

- 已读取 [SB12_01A_ACCEPTANCE.md](SB12_01A_ACCEPTANCE.md)：D 的独立结论为 **01-A 未执行、真实链路未证明成功、当前不具备继续其余样本的可验证技术条件**。同时未找到 `01-A.provider-ledger.json`、`01-A.java-run.json`、Java 保存产物或已填写真人评价；当前 `quality-runs/20261007/` 仅有历史的 `preflight.json` 和空白评价模板。端口 `13002/18081/15433` 也未监听，不能复用 01-A Gateway 内存账本。
- 因此，**不能**把 01-A 记为完成、推断其费用，或从零重启继续真实调用。01-A 原始证据一旦放回本项目所列路径，必须原样保留；不得覆盖、改名或以新运行替代。
- 新增 [run-sb12-remaining-live.ps1](../../scripts/run-sb12-remaining-live.ps1)。它在提交任意付费请求前强制读取 01-A 验收（且要求 D 写入 `继续执行授权：APPROVED`）、Java run 与最终 provider ledger，将其中的 `businessRuns` 与 `chargedUsd` 作为 Gateway 初始账本值；缺失/非法/未获授权/超过 8-run 或 USD 1.00 上限立即停止。
- 脚本串行处理 `01-B`、`01-8-A`、`01-8-B`、`02-A`、`02-B`、`03-A`、`03-B`；每项终态后立刻导出冻结上下文、Java run/保存产物、逐请求账本、自动预检查与逐镜人工评价栏。失败、预算拒绝或超时均保留证据并停止后续项，绝不重启清零。
- 已完成无费用验证：PowerShell 两个执行脚本可解析，`npm run check`、`npm run budget:check`（含承接金额/次数）、`npm run provider:check` 均通过。没有在此智能体进程中发出 provider 请求。

## 历史记录 — 2026-10-07 执行前：01-A 受控启动交接

- 新增 [run-sb12-01a-live.ps1](../../scripts/run-sb12-01a-live.ps1)。必须从已设置 `DEEPSEEK_API_KEY` 的**同一 PowerShell 窗口**执行；脚本只检查其存在性，不读取、打印或写入其值。
- 脚本生成独立的 Gateway、Java execution 和 JWT 随机值，启动隔离 PostgreSQL / Java HTTP / live Gateway；Java 签发内部 capability token，操作人员无需也不应手工提供 execution token。前端不参与本次固定样本运行，因而没有 fixture 数据源；脚本不会启动 fixture。
- 在提交唯一的 01-A 业务 run 前，脚本会保存 presence-only preflight，并通过 TypeScript、离线 DeepSeek adapter、预算账本契约检查及 Java/Gateway 健康检查。离线检查不产生 provider 费用。
- 01-A 的输入预留固定为每个 provider 请求 50,000 input token + 2,000 token provider serialization headroom，输出上限 8,192、SDK retries 0。按 DeepSeek Flash 峰值费率，每请求最坏预留为 USD 0.0248304；账本先预留后结算，累计至 USD 1.00 或 8 个业务 run 会拒绝后续请求。脚本只提交这一个 run，且不在重启后继续批次。
- 运行成功或失败都会在 `quality-runs/<执行日期>/01-A/` 留下 Java run、冻结上下文、实际 Java 分镜（若保存成功）、受保护 Gateway 导出的逐请求账本、输入/提示词/schema 源码快照、自动预检查和人工参考。JWT、API key、Gateway token、execution token 与 Authorization 均不落盘。产物的自动预检查并非人工结论。
- 截至本报告更新时，脚本尚未在持有用户终端凭据的进程中运行，因此真实计数仍是 **0 run / 0 provider request / 0 retry / USD 0.00**；不能将此工程准备视为 SB-12 通过。

## 2026-10-07 追加：选择 DeepSeek 官方 API

- 用户已选择 DeepSeek 官方 API。本批拟配置 `PI_PROVIDER=deepseek`、`PI_MODEL=deepseek-flash`，从 Gateway 进程读取 `DEEPSEEK_API_KEY`；实际付费生成仍为 **0 run / 0 provider request / 0 retry / USD 0.00**。
- Gateway 已支持按 provider 选择凭据与模型，保留 Anthropic。DeepSeek 使用官方 `https://api.deepseek.com` 及 Pi 的 OpenAI Completions 适配，关闭思考模式。当前官方推荐模型名见 [DeepSeek 模型与价格](https://api-docs.deepseek.com/quick_start/pricing/)；Gateway 在应用层补充该模型名，不修改 Pi 自动生成的模型目录。
- 预检允许显式的零缓存费率，校验所选 provider 的模型注册；服务启动同样拒绝不匹配的模型。缺失、非法或全零 usage 按预留保守扣额，不能当作真实零费用释放预算。
- 已通过 `npm run check`、`npm run provider:check`、`npm run budget:check`、`npm run storyboard:check`、`npm run smoke`。`provider:check` 使用真实 SDK 解析本地替代的 HTTP 流，验证读取上下文→工具保存→回执、流式工具参数、缓存命中用量、关闭思考、500 错误不重试及缺失 usage 的保守扣费；它阻断所有外发网络请求，不构成真实模型质量或真实 Java 持久化证据。
- 选择 DeepSeek/模型后再次执行无费用预检：模型注册检查通过，检查进程仍未读取到 `DEEPSEEK_API_KEY`、完整 live/Java 配置及预算费率，`readyForSb12LiveRun=false`。完整配置步骤、官方费率来源及进程重启后的核账规则见 [RUNBOOK](RUNBOOK.md)。
- 下一步由执行者完成其余本地启动和非秘密预算配置，凭据经本地环境提供后执行既定 `01-A`，再按剩余额度继续原计划。新一批证据必须重新记录本次 DeepSeek 模型、配置及源码 hash；不得沿用下方 Anthropic 预检快照作为实际执行配置。

## 切换 DeepSeek 前的当日预检快照

| 检查项 | 当前结果 | 结论 |
| --- | --- | --- |
| `AGENT_MODE` | absent | 未确认 `live`；默认会走 mock。 |
| `JAVA_MODE` | absent | 未确认 `http`；默认会走 mock。 |
| `PI_MODEL` | absent | 没有可审计的确切模型，不能冻结费率或调用上限。 |
| `ANTHROPIC_API_KEY` / `ANTHROPIC_OAUTH_TOKEN` | absent / absent | 没有当前代码可用的 live provider 凭据。 |
| `ANTHROPIC_BASE_URL` / `ANTHROPIC_API_URL` | absent / absent | 没有受控 endpoint 覆盖；非必要，但记录为 absent。 |
| `AGENT_GATEWAY_TOKEN`、Java callback 所需的执行能力配置 | absent | Gateway 无法按 HTTP 链路安全启动/回调。 |
| `VITE_STORYBOARD_DATA_SOURCE` | 进程中 absent；web 目录只有未被 Vite 自动加载的 `.env.example`（示例值为 `fixtures`） | 当前没有启用 fixtures 的证据；但前端也未运行，不能作为端到端证明。 |
| 服务端口 `3001/8080/13001/18080/5173/15173` | 均未监听 | 没有可复用的 live Java/Gateway/Web 进程。 |
| 当前运行时实际支持的 provider | 仅 `anthropicProvider()` | 只可按 Anthropic 链路运行；未为缺失配置扩展 OpenAI 或其他 provider。 |

`runtime.ts` 的 live 分支固定注册 `anthropicProvider()`，而 `AGENT_MODE !== live` 时创建 faux provider。`generate_storyboard` 的系统提示词/账本实现源码 hash 为 `38AACADE791939F30354D344DCF38851CF92283C7BB457C3C1E3566BF3E4AB35`；profile/输出限制 hash 为 `BA063F85FB517AA990770AD3E999B641E138BB245EC62F23E55502DA1AE57DB6`，可见工具 schema hash 为 `655995A68EC056DABB08F6F9047E3BC7AE43F58D8DDFAC7D900DE22480DDEA25`。完整 hash、运行时参数和环境存在性结果均在当日 JSON 证据中。

## 已完成的工程准备（无需真实 provider）

- `examples/pi-agent-gateway/src/budget.ts` 新增只读取进程环境的安全 live 预检；不会加载 `.env`，且不会输出密钥值。`npm run preflight` 只报告变量存在性及缺失项。
- live 启动现在要求明确 `PI_MODEL`、Java HTTP 链路、Anthropic 凭据和可审计的 token 费率/输入预留；配置不完整时服务拒绝以 live 方式启动，而不是退回 mock。
- 每个 live 业务 run 先计入进程级 8-run 上限；每个 provider request 在发送前按保守的输入 token 预留和 `maxOutputTokens`（当前 profile 为 8192）预留费用。累计预留或实际费用达到 `SB12_BUDGET_USD`（默认 1.00，拒绝大于 1.00）即拒绝下一请求。请求上下文超过预留也会在发送前拒绝。
- live transport 显式使用 `maxRetries: 0`。账本逐请求记录开始/结束、provider/model、受限 request ID、原始 usage、实际/预留/保守计入费用及重试策略；失败或中断请求因可能已被计费，保守消耗全部预留。可经受保护的 `GET /agent/runs/{runId}/ledger` 查询。
- 已用本地测试模型费率和本地 faux/HTTP stub 验证配置拒绝、预算预留、usage 计算、失败请求保守计费、超额/上下文拒绝及现有 HTTP 分镜链路；未发出真实 provider 请求。

## 预算门槛

- 批次硬上限保持为 **8 个业务生成 run**、**USD 1.00**；本次已使用：**3 run / 9 provider requests / 0 retry / USD 0.007482360**（配置费率计算），剩余 **5 run / USD 0.992517640**。01-8-A 的 failed run 仍计入额度。
- 当前 `generate_storyboard` 的 `maxOutputTokens` 是 **8192**，`maxToolCalls` 是 **6**。预算控制现已在每个 provider request 前执行预留，并把实际 usage 与保守失败费用累计到进程级 USD 上限；它不替代对费率和输入预留配置的审核。
- 01-A 实际模型、费率和输入预留已记录于 [运行配置快照](quality-runs/20261007/01-A/01-A.configuration.json)。下方 Anthropic 依据为切换前历史参考，不用于本次 DeepSeek 费用计算；后续执行须承接已导出账本，不可重新从零计数。
- 不以“默认模型名”代替明确的 `PI_MODEL`，不提高预算，也不以失败/诊断调用绕过账本。任何预检、失败、重试都占本批的 provider 请求数和费用。

官方依据：[Anthropic API Pricing](https://platform.claude.com/docs/en/about-claude/pricing)（2026-10-07 查询）。该页的具体费率随模型和 endpoint 变化；由于本批模型未配置，报告不擅自选择或引用某个模型的费率。

## 冻结输入与执行计划

| 固定输入 | SHA-256 |
| --- | --- |
| `samples/01-unopened-letter-dialogue.md` | `BE76B378C65FFA98AAFC064A65257ACD99D3BDE149C8FD55CB4F790B1462A435` |
| `samples/02-blackout-stairwell-action.md` | `EB3027446E97216393490A9AE6794F3F190497B8A7CF5257437F65F6E0DF612F` |
| `samples/03-dawn-platform-atmosphere.md` | `9AB678B1F4930AAB6DC83C13E269B428F933B87808C9CD43EB013660F697A622` |

| 业务 run | 固定输入 / 目标镜头 | 状态 | Java 保存产物 / 真实 usage |
| --- | --- | --- | --- |
| 01-A | 样本 01 / 6 | COMPLETED；独立技术复验通过，待真人评价 | 六镜 Java 分镜 / 三次 provider usage |
| 01-B | 样本 01 / 6 | COMPLETED；待本轮复验及真人评价 | 六镜 Java 分镜 / 三次 provider usage |
| 01-8-A | 样本 01 / 8 | FAILED；最终回执格式拒绝，未重试 | 八镜已保存并补取 / 三次 provider usage |
| 01-8-B | 样本 01 / 8 | BLOCKED，未启动 | 无 / 无 |
| 02-A | 样本 02 / 6 | BLOCKED，未启动 | 无 / 无 |
| 02-B | 样本 02 / 6 | BLOCKED，未启动 | 无 / 无 |
| 03-A | 样本 03 / 4 | BLOCKED，未启动 | 无 / 无 |
| 03-B | 样本 03 / 4 | BLOCKED，未启动 | 无 / 无 |

01-A、01-B、01-8-A 的 Java/Gateway run ID、traceId、resultRef、Java 保存产物及逐请求 usage 均已留存；01-8-A 的原自动预检查因 failed 状态未导出分镜而记录无产物，之后补取结果见失败诊断与累计核账，原记录未覆盖。provider 返回的独立 request ID 未出现在账本，供应商扣款未对账，均不能补造。其余五项未执行；真人评价尚未发生。

## 已完成的非付费准备

- 新建当日证据目录；预检 JSON 未包含密钥、JWT、执行令牌或 Authorization 头。
- 准备了三份原场景、八个计划 run 的逐镜对照区和“直接用 / 小改可用 / 需要重做”空白栏。它不是模型自评，也不是人工结论。
- 历史非付费的 `npm run storyboard:check` 仅说明 fixture/契约覆盖；不计为本次真实运行，也不改变本报告状态。

## 安全解除阻塞步骤

在受控 secret store 或受控进程环境中（不要发到聊天、不要写入仓库或 `.env`）配置并启动：

1. Gateway：`AGENT_MODE=live`、`JAVA_MODE=http`、明确且已获授权的 `PI_MODEL`、`ANTHROPIC_API_KEY`、`AGENT_GATEWAY_TOKEN`、`JAVA_BASE_URL`。
2. Java：独立的数据库、`AGENT_GATEWAY_URL`、与 Gateway 匹配的 `AGENT_GATEWAY_TOKEN`、`AGENT_EXECUTION_SECRET` 及所需数据库/JWT 变量；让 Java 为每个业务 run 签发短期 execution token。
3. Web：保持 `VITE_STORYBOARD_DATA_SOURCE` 未设置或非 `fixtures`，并仅将 Java API 暴露给浏览器。
4. 在 secret store 外以受控非秘密配置固定 `SB12_INPUT_TOKEN_RESERVE`、`SB12_PROVIDER_INPUT_OVERHEAD_TOKENS` 及模型的 input/output/cache 单价（每百万 token）；输入预留必须覆盖场景、系统提示词、工具 schema、工具回传和 provider 工具开销。保持 `SB12_BUDGET_USD<=1.00`；重试固定为 0。冻结后串行执行表中的 8 个 run。

每个真实 run 必须写入 `quality-runs/YYYYMMDD/<run>.json`：输入和系统提示词/schema 快照或 hash、模型参数、run/result/artifact/trace 标识、Java 保存的完整 JSON、每轮原始 usage、请求/重试及原因、费用公式与累计、开始/结束/耗时，以及“自动预检查”结果。完成真实运行后仍只能写“待人工评价”；取得真人逐镜评价前，SB-12 不得宣布整体通过。
