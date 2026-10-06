# 分镜工作台前端修复记录

日期：2026-10-06（Asia/Shanghai）  
范围：`apps/storyboard-web`，未修改 Java、Gateway 或共享契约。

## 修复项与用户行为

### R3，本地编辑保护

- 保存会记录提交时的编辑代次和请求快照。若用户在保存 A 的响应回来前继续输入 B，A 只更新已保存版本和 revision，B 保留为未保存编辑，保存按钮继续可用。
- 采纳候选时若存在 dirty 编辑，不再发送采纳请求或整体替换本地分镜。界面明确提示先保存或加载服务器版本。
- 保存后的候选若因 revision 已变化而过期，采纳仍按后端 revision 校验，前端显示候选过期提示，不修改 revision 规避冲突。
- 采纳请求进行中，编辑控件锁定，避免采纳响应与新本地输入竞争。

### R7，运行状态与 observer

- `RunStatus` 已与 Java `AgentRunStatus` 对齐：`QUEUED`、`RUNNING`、`FINALIZING`、`COMPLETED`、`FAILED`、`CANCELLING`、`CANCELLED`、`INTERRUPTED`。
- `FINALIZING` 继续显示为进行中；`INTERRUPTED` 为终态，停止 SSE/轮询，显示中断原因，并允许用户生成新任务。
- 每个观察器、加载请求和候选读取均带有失效保护。切换任务、切换已保存分镜或卸载组件会中止旧 observer，迟到结果不能覆盖当前页面。

### R8，未保存导航

- 路由切换已使用 React Router 数据路由的 blocker，在提交导航之前拦截站内 Link 和浏览器后退。
- 取消后 URL、工作台和本地编辑都保持不变；确认后才继续导航。
- 刷新和关闭页面仍使用 `beforeunload` 保护。

### 令牌续期

- API client 保持稳定，不会因为 session 状态更新而重新创建并触发工作台加载。
- client 从 ref 读取当前会话；刷新成功时先同步 ref，再重试原请求，因此重试使用新 access token。
- 已用浏览器响应注入验证这个风险路径。该验证不等同于真实认证服务的 token 签发、过期策略或刷新并发策略验证。

## 自动化结果

### 响应注入浏览器回归

命令：

```powershell
cd D:\desktop\screenplay-agent-backend\apps\storyboard-web
$env:E2E_WEB_URL='http://127.0.0.1:15174'
$env:PLAYWRIGHT_CHROMIUM_EXECUTABLE='C:\Program Files\Google\Chrome\Application\chrome.exe'
npm run test:e2e -- --grep "response injected"
```

结果：5 passed。

- dirty 下采纳候选：断言本地其他镜头编辑保留，且没有采纳请求；保存后对旧候选返回 409，断言前端提示过期而不改写 revision。
- 延迟保存响应：断言 A 的响应不能覆盖后续输入 B，dirty 保留且可再次保存。
- 取消 Link 与取消浏览器后退：断言 URL 和编辑器内容不变。
- `INTERRUPTED`：断言恢复检查后不再轮询，且可提交新的生成任务。
- 刷新 token：断言初始旧 token 收到 401 后，原导出请求以新 token 重试；同时断言 session 更新没有重新加载工作台或丢失 dirty 编辑。

### 真实 HTTP 浏览器回归，临时数据库

命令：

```powershell
cd D:\desktop\screenplay-agent-backend
$env:JAVA_HOME='C:\Users\tcf\.jdks\ms-21.0.9'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\scripts\verify-storyboard-http-e2e.ps1
```

结果：6 passed。脚本创建临时 PostgreSQL、Java、mock Gateway、Vite 和 Chromium，完成后清理临时容器与进程。

原有正常工作流继续覆盖登录、项目/剧本、生成、保存、刷新恢复、重做、采纳和 Markdown 导出。新增真实 HTTP 断言让 PUT 先到 Java，再延迟浏览器收到响应：服务器保存 A，用户输入 B，页面保留 B 且保持 dirty。

### 构建

```powershell
cd D:\desktop\screenplay-agent-backend\apps\storyboard-web
npm run build
```

结果：通过。

## 验证边界与未完成事项

- `INTERRUPTED` 的浏览器用例为响应注入，证明前端终态处理和停止轮询；未通过真实 Java 进程重启制造该状态。后端恢复集成证据仍应由 Java 测试维护。
- token 用例为浏览器响应注入，已经验证新 token 重试和前端会话更新不重载工作台；未验证生产认证服务的实际过期时间、刷新令牌轮换或多请求同时刷新。
- dirty 采纳用例验证了前端不静默放弃编辑。保存后候选过期的实际 409 依赖 Java 的既有 revision 协议，本轮前端不修改 Java 或尝试改写 revision。
- 未包含 SB-12 真实 provider 质量/费用/时延验收，也未包含 SB-13 真实用户试用。
