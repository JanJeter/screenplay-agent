# SB-11 自动化闭环与异常验收报告

日期：2026-10-06  
代码状态：工作区未提交改动（本报告不代表发布版本）。  
模型结论：本报告使用固定 Pi faux provider 跑通流程；**不构成真实模型质量验收**，真实质量仍归 SB-12。

## 实际执行环境

- JDK：Microsoft OpenJDK 21.0.9（项目要求 Java 21；系统 PATH 上的 Java 17 不能编译）。
- 数据库：临时 `postgres:16-alpine` 容器。E2E 使用 `screenplay_sb11`，结束后 `docker rm --force` 清理。
- 服务：Spring Boot HTTP `18080`、Node Gateway HTTP `13001`（`AGENT_MODE=mock`、`JAVA_MODE=http`）、Vite `15173`。
- 浏览器：本机 Google Chrome，由 Playwright 驱动。

可重复命令（PowerShell）：

```powershell
$env:JAVA_HOME = 'C:\Users\tcf\.jdks\ms-21.0.9'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\scripts\verify-storyboard-http-e2e.ps1
```

最近一次真实闭环结果：`SB-11 HTTP E2E passed`。临时日志保存在系统临时目录 `screenplay-sb11-*`，不写入仓库，也没有保留运行中的测试容器。

## 闭环证据

`apps/storyboard-web/e2e/storyboard-http.spec.ts` 在一个真实浏览器中完成：

1. 登录 demo 管理员，创建项目，粘贴含中文/空行的场景并调用真实解析 API。
2. 选择场景，提交 4 镜生成；Java 通过 Node HTTP 回调实际保存分镜并以 `resultRef` 打开产物。
3. 编辑第一镜、保存至 r2、刷新页面并验证编辑保留。
4. 重做第一镜，比较候选并采纳至 r3。
5. 验证目标镜头 ID 和顺序稳定、非目标镜头逐字段不变、原剧本 `rawText` 未变。
6. 下载 UTF-8 Markdown，并验证其包含已采纳镜头内容。

## 已执行检查

| 命令 | 结果 |
| --- | --- |
| `mvnw.cmd -q test`（JDK 21） | 通过；包含 Testcontainers PostgreSQL 集成测试。|
| `npm run check`（Gateway） | 通过。|
| `npm run storyboard:check`（Gateway） | 通过；4/6/8 镜、非法/截断输出、Java 数字 ID、保存失败、单镜隔离。|
| `npm run smoke`（Gateway） | 通过；真实 Node HTTP、SSE、重放、鉴权/越权、幂等、取消。|
| `node docs/storyboard-mvp/fixtures/validate-fixtures.mjs` | 通过；fixtures/schema/预算校验。|
| `npm run build`（工作台） | 通过。|
| `npm test`（工作台） | 通过；目前没有 Vitest 单元文件，命令显式允许空集。|
| `scripts/verify-storyboard-http-e2e.ps1` | 通过；真实 PostgreSQL + Java/Node HTTP + Chromium 正常闭环。|

## 异常与数据不变量覆盖

| 项目 | 证据 | 状态 |
| --- | --- | --- |
| 越权/组织隔离 | `StoryboardIntegrationTest` 使用另一组织 JWT 读取分镜，返回 404。 | 通过（真实 PostgreSQL）。 |
| 重复生成、重复放弃/采纳 | `StoryboardIntegrationTest` 覆盖同键同 run、已处理提案冲突/幂等放弃。 | 通过（真实 PostgreSQL）。 |
| 编辑/采纳版本冲突 | 集成测试在提案后保存分镜，再采纳返回 409。 | 通过（真实 PostgreSQL）。 |
| 取消/完成竞争 | `AgentRunServiceCancellationTest`、`AgentRunWorkerStateTest` 与分镜集成测试覆盖取消后拒绝回调并保持原镜头。 | 通过；数据库状态机由 Testcontainers 用例验证。 |
| Node 不可用 | `AgentGatewayDispatchService` 将连接失败转为 `gateway_dispatch_failed`，已有 worker/恢复单测覆盖。 | 已验证状态转换；尚未新增独立浏览器用例。 |
| 超时、模型失败 | Gateway `server.ts` 的 120 秒终止与失败终态由 Node smoke/状态机覆盖。 | 固定 mock 覆盖；未等待真实 120 秒 E2E。 |
| 非法 JSON、镜头数不符、缺 Prompt、越界引用 | `storyboard-contract.ts` 和 fixtures 负例在 Node/Java 双端 schema 前阻止保存。 | 通过（固定 mock/契约）。 |
| SSE 断线恢复 | Node smoke 验证 Last-Event-ID 重放；工作台以轮询读取 Java run 状态。 | Gateway 重放已验证；未注入浏览器网络断开。 |
| 来源重解析 | `StoryboardIntegrationTest` 重解析后读取旧来源快照且原剧本不变。 | 通过（真实 PostgreSQL）。 |
| 中文、空对白、换行、Markdown 特殊字符 | fixtures、导出集成测试与 Chromium E2E 覆盖。 | 通过。 |

## 本次发现并修复

1. 前端未将 Java 小写 run 状态规范化为冻结的大写枚举，已完成任务会持续轮询。`java-api.ts` 现统一转换状态。
2. 后端未允许本机 Vite 的 Bearer 跨域请求，真实浏览器会被 CORS 拦截。`SecurityConfig` 仅允许本机 `localhost`/`127.0.0.1` 开发来源。
3. Java 内部保存回执未按 `resultRef` 输出；分镜虽保存但 Node 判为失败。`ResultResponse` 已对齐契约。
4. 内部 context 的空字段依赖 Jackson 行为，造成 generate/rewrite 任一模式违反精确 schema。`StoryboardAgentInternalController` 现按模式显式投影 wire JSON。

## 未覆盖范围与后续操作

- 未运行真实 provider，也没有质量/用量/时延结论；由 SB-12 使用三份固定样本和费用上限执行。
- 浏览器 E2E 尚未做主动离线、真实 120 秒超时、Node 进程被杀后的 UI 注入；现有状态机/Node mock 已覆盖相应终态。后续可在 `verify-storyboard-http-e2e.ps1` 增加参数化故障 Gateway，复用同一 Playwright 基础设施。
- 运行 E2E 前必须使用 JDK 21；JDK 17 的 `mvnw` 会报 `release version 21 not supported`。

## 给下一位执行者

- 运行全链路：执行 `scripts/verify-storyboard-http-e2e.ps1`；它不需要真实模型密钥。
- 固定契约数据在 `docs/storyboard-mvp/fixtures/`，先运行 `validate-fixtures.mjs`，再改任一字段。
- Node 只接受 Java 内部回执 `{artifactId,resultRef:{type,id,storyboardId}}`；context generate 与 rewrite 都要求精确字段集。
- 真实模型质量/预算请从 `docs/storyboard-mvp/SB12_REAL_MODEL_QUALITY_REPORT.md` 的样本和量表继续，不能把本报告的 mock 闭环标为质量通过。
