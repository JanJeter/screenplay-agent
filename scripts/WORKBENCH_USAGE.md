# 日常分镜工作台

在 `D:\desktop\screenplay-agent-backend` 的 PowerShell 中运行：

```powershell
.\scripts\workbench.ps1 -Action Start
.\scripts\workbench.ps1 -Action Status
.\scripts\workbench.ps1 -Action Stop
```

需要 JDK 21（默认 `C:\Users\tcf\.jdks\ms-21.0.9`，可用 `-JdkHome` 指定）、Node.js 22.19+、运行中的 Docker Desktop，以及 `pi-agent`、`apps/storyboard-web` 中安装好的 npm 依赖。首次安装依赖分别运行 `npm ci`。Start 默认构建 Java；仅在已构建当前源码时使用 `-SkipBuild`。Status 可加 `-Json` 供工具读取。

访问 **http://127.0.0.1:5174/**。Java 为 `18084`，Gateway 为 `13005`，独立 PostgreSQL 为 `15436`；只监听本机，不占用历史 `5173/18083/13004/15435` 或其他 `8080` 服务。前端明确使用 Java API。默认 **mock 流程测试，不调用付费模型**；生成内容不代表模型质量。

本机已完成完整 mock 网页流程和停止后重启的数据保留验证。实际下载文件位于 `.local/workbench-dev/browser/分镜-场1.md`。2026-10-08 用户首次真实生成因模型改写原文引用未通过保存校验；修复后，用户于北京时间 21:14 发起的后续真实任务已完成并保存分镜。该结果验证功能可用，不据此宣称模型质量验收通过。

空数据库首次启动会通过现有 `StartupDataConfig` 正常初始化演示组织和账号：`admin@demo.com` / `admin12345`。在网页登录、创建项目、粘贴剧本。该入口仅供本机开发。

重复 Start 对健康服务只检查状态，不更换进程。改过 Gateway/Java 源码或模式后，先 Stop 再 Start。请等当前生成结束、保存编辑后再停止。Stop 只关闭已记录且 PID、启动时间、可执行文件均匹配的进程及其子进程，并正常停止所属 PostgreSQL 容器。已保存项目仍在持久 Docker volume 中，下次启动继续使用。不要删除 `.local/workbench-dev`、其 Docker volume 或执行 Docker volume prune。

`.local/workbench-dev/state.json` 记录进程归属、数据库 volume、启动时 Gateway 源码 SHA256 和日志位置；`secrets.json` 只存开发内部凭据，已被 Git 忽略。每次启动日志独立保留，浏览器验证产物可放在 `.local/workbench-dev/browser`。Stop 不删除日志、数据库或预算账本。

## 真实模型模式

首次 live 调用前需要明确本次新活动的次数与美元上限。脚本不提供默认额度。经确认后，将**不含密钥**的配置保存在 `.local/workbench-dev/live-config.json`，再运行：

本机本次额度已获用户确认，配置已保存：活动 `workbench-20261008-minimal`，最多 2 次业务任务（原计划一次整场生成、一次单镜重做），累计上限 USD 0.20。每项任务可包含多次模型请求，全部计入该上限；没有自动重试。当前已从后端 `.env` 加载密钥并启动 live 服务。用户已执行 2 次真实生成（1 次失败、1 次成功），共 7 次模型请求，按配置费率累计 USD 0.010359384；本活动次数已用完，失败不会退回次数，重启不会恢复次数。账号和后台功能验收没有新增模型调用。此配置文件不含密钥。再次启动真实模式时应使用下面完整的 `-Mode live` 命令，省略该参数会使用默认 mock 模式。

```powershell
.\scripts\workbench.ps1 -Action Stop
.\scripts\workbench.ps1 -Action Start -Mode live -LiveConfigPath .\.local\workbench-dev\live-config.json
```

配置字段：`AGENT_ACTIVITY_ID`（新活动名，不以 `sb12`、`sb-12`、`sb_12` 开头）、`AGENT_BUSINESS_RUN_LIMIT`（整场生成与单镜重做共用）、`AGENT_BUDGET_USD`、`AGENT_INPUT_TOKEN_RESERVE`、`AGENT_PROVIDER_INPUT_OVERHEAD_TOKENS`、`PI_PROVIDER=deepseek`、`PI_MODEL=deepseek-flash`、`PI_INPUT_USD_PER_MTOK`、`PI_OUTPUT_USD_PER_MTOK`、`PI_CACHE_READ_USD_PER_MTOK`、`PI_CACHE_WRITE_USD_PER_MTOK`、`PI_CACHE_WRITE_1H_USD_PER_MTOK`、`PI_PRICING_SOURCE`。缓存写入不收费时仍显式填写 `0`。费率在首次调用前按官方当前定价核对；账本金额是配置费率估算。启动前会运行 Gateway 自身的离线配置校验，不产生模型调用；继承的历史 `SB12_*` 参数会被拒绝，请在当前开发 PowerShell 中移除这些旧参数。

脚本强制 `AGENT_BUDGET_PROFILE=workbench` 并将账本放在 `.local/workbench-dev/budget/<活动名>.json`。同一活动重启继续承接计数和费用；额度耗尽后停止新增调用，不能通过换名、删账本或重启恢复已授权额度。`DEEPSEEK_API_KEY` 优先从调用 PowerShell 的进程环境读取；缺失时仅读取 `examples/pi-agent-gateway/.env` 中的同名字段，支持单行裸值或引号值。该文件已被 Git 忽略，文件中的其他字段不会被导入。密钥仅临时注入配置检查和 Gateway 进程，不写入状态、预算配置、前端或输出。修改密钥后先 Stop，再按上述 live 命令 Start，刷新网页即可。历史 SB-12 数据和启动器不参与日常操作。

原文引用修复后，模型可选择冻结原文的片段编号，由 Gateway 取回精确原文再提交 Java；原有引用一致性校验继续保留。首次保存失败会结束该任务，避免继续付费请求盲目改写。历史失败任务保持原状态，“查询原任务”只读取旧结果，不会重新生成。前端将历史环境标为只读，当前 live 环境标为“真实模型 · 当前工作台”。

## 团队账号与管理后台

本阶段按团队共用工作区实现。注册的新成员加入配置的工作区，并取得普通成员角色；邮箱验证后可登录，管理员在后台分配角色或停用账号。已有开发账号和项目继续保留。后台管理的范围是当前工作区，包括用户、角色权限、生成任务与用量；任务和用量查询不发起模型请求。费用展示为配置费率估算，缺少计费记录的历史任务会标为未知。

开发启动器明确使用本地邮件模式。注册验证、重发验证和找回密码产生的邮件保存在 **`.local/workbench-dev/mail`**，每封是 UTF-8 `.eml` 文件。按修改时间打开最新邮件，正文中的完整验证或重置链接指向 `http://127.0.0.1:5174/`。本地模式不会把邮件发送到外部邮箱；该目录不通过网页公开，也不加入 Git。

正式邮件接入使用后端 `AUTH_MAIL_MODE=smtp`、`AUTH_MAIL_FROM`、标准 `SPRING_MAIL_HOST/PORT/USERNAME/PASSWORD` 及适用的 TLS 设置，并设置实际 `AUTH_FRONTEND_BASE_URL`。当前日常开发启动器固定为 `local`；接入正式邮件时再修改启动配置，不能只设置 SMTP 密码就认为已经发出邮件。SMTP 模式发送失败会报告错误，不回退为本地投递。

工作区由 `AUTH_REGISTRATION_ORGANIZATION_ID` 或 `AUTH_REGISTRATION_ORGANIZATION_SLUG` 指定，本机启动器使用 `demo-org`。演示账号初始化需要显式 `AUTH_DEMO_SEED_ENABLED=true`，本机启动器已设置；独立部署默认不开启。服务端管理邮箱验证、一次性重置链接、账号停用和会话失效，不能通过隐藏前端按钮代替权限校验。

已在实际开发服务中通过浏览器验证注册、邮件链接验证、登录、找回密码、旧会话撤销、管理员角色创建与分配、成员启停、后台任务和用量读取。测试账号结束后已停用。桌面及窄屏截图和不含凭据的结果位于 `.local/workbench-dev/accounts-browser`。原有分镜 r4 的读取和刷新也已回归通过。

需要重复这项本地验证时，运行 `node scripts/verify-workbench-accounts.cjs`。脚本创建一个测试成员和测试角色，结束后停用该成员；使用本地 `.eml` 邮件，不调用模型。管理入口为 `http://127.0.0.1:5174/admin/users`，账号页面为 `/register`、`/forgot-password`；管理员也可以从右上角账号菜单进入后台。
