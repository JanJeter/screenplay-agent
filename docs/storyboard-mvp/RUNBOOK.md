# 单场景分镜工作台运行与试用说明

适用范围：SB-13 小范围试用准备。本文只说明本仓库当前的单场景分镜工作台；它不替代部署安全评审，也不包含真实密钥、密码、JWT、执行令牌或用户剧本。

## 运行模式与当前门槛

| 模式 | 用途 | 是否可计入真实用户试用 |
| --- | --- | --- |
| fixtures | 前端页面状态演示；`VITE_STORYBOARD_DATA_SOURCE=fixtures` | 否。数据在浏览器内，不能验证 Java、Gateway 或模型。 |
| mock HTTP | Java、Node、PostgreSQL 和浏览器的确定性技术闭环；Gateway 使用 `AGENT_MODE=mock` | 否。SB-11 已用此模式验证流程和异常，不证明模型质量。 |
| live HTTP | Java 保存产物、Gateway 真实 provider、工作台浏览器 | 是，但仅在 SB-12 的真实质量/预算门槛通过后可开始。 |

当前状态：SB-11 工程闭环已通过复验（含真实 PostgreSQL、Java→Gateway HTTP 与浏览器；Gateway 为 `AGENT_MODE=mock`）。完整验收命令和 R1～R8 证据见 `REPAIR_ACCEPTANCE.md`。SB-12 仍因真实 provider 凭据/配置缺失而 BLOCKED；产品负责人不得用 fixtures 或 mock 组织并计数试用，先完成 `SB12_REAL_MODEL_QUALITY_REPORT.md` 的解除阻塞步骤。

## 运行前检查

1. 使用 JDK 21（JDK 17 无法编译本项目）、Node.js 22.19+、Docker Desktop 和可用的 Chromium/Chrome。
2. 在受控环境提供独立的 PostgreSQL 数据库、每位用户的测试账号，以及不含真实密钥的日志采集位置。
3. 确认端口没有被占用。默认示例为 PostgreSQL `55432`、Java `8080`、Gateway `3001`、Vite `5173`；SB-11 脚本会使用隔离端口 `15432/18080/13001/15173`。
4. 在 Gateway 所在受控进程中设置 `AGENT_MODE=live`、`ANTHROPIC_API_KEY` 和明确的 `PI_MODEL`。当前运行时代码只注册 Anthropic live provider；若改 provider，必须先完成代码、契约和 SB-12 复核。
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
| `ANTHROPIC_API_KEY`、`PI_MODEL` | 真实 provider 凭据和本批次确切模型；只放在受控进程环境。 |

### 工作台

| 配置名 | 用途 |
| --- | --- |
| `VITE_JAVA_API_BASE` | 浏览器调用的 Java API 地址，例如 `http://127.0.0.1:8080`。 |
| `VITE_STORYBOARD_DATA_SOURCE` | 试用时必须未设置或非 `fixtures`；`fixtures` 只用于本地演示。 |

浏览器只持有用户 JWT 并调用 Java；**绝不**设置 Gateway 地址、Gateway token、执行令牌或模型密钥为 `VITE_*` 变量。

## 启动顺序（PowerShell）

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

3. 在第二个终端启动 live Gateway：

```powershell
Set-Location D:\desktop\screenplay-agent-backend\examples\pi-agent-gateway
$env:PORT = '3001'
$env:AGENT_GATEWAY_TOKEN = '<shared-gateway-token>'
$env:AGENT_MODE = 'live'
$env:JAVA_MODE = 'http'
$env:JAVA_BASE_URL = 'http://127.0.0.1:8080'
$env:ANTHROPIC_API_KEY = '<provider-key-from-secret-store>'
$env:PI_MODEL = '<approved-model-id>'
npm run start
```

4. 在第三个终端启动工作台。试用不得设置 `VITE_STORYBOARD_DATA_SOURCE=fixtures`：

```powershell
Set-Location D:\desktop\screenplay-agent-backend\apps\storyboard-web
Remove-Item Env:VITE_STORYBOARD_DATA_SOURCE -ErrorAction SilentlyContinue
$env:VITE_JAVA_API_BASE = 'http://127.0.0.1:8080'
npm run dev -- --host 127.0.0.1 --port 5173 --strictPort
```

5. 验证 Java `http://127.0.0.1:8080/actuator/health`、Gateway `http://127.0.0.1:3001/health` 和工作台 `http://127.0.0.1:5173` 均可访问。使用独立试用账号登录，创建一个非敏感场景，完成一次真实生成；只在 Java run 为完成、存在 `resultRef` 且详情页显示保存产物时判为成功。

如需保留运行日志，保存 Java/Gateway 的时间戳、runId、resultRef、状态和 provider usage；过滤 `Authorization`、`executionToken`、API key、刷新 token 和剧本文本。会话结束后关闭三项进程并按本地数据保留政策停止或删除测试数据库。

## 试用前技术验证

以下确定性检查不花费模型费用，也不能替代 SB-12 或真实试用：

```powershell
Set-Location D:\desktop\screenplay-agent-backend
node .\docs\storyboard-mvp\fixtures\validate-fixtures.mjs

Set-Location .\examples\pi-agent-gateway
npm run check
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
