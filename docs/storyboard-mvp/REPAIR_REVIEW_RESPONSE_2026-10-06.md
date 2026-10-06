# 复核项修复与回归证据

日期：2026-10-06（Asia/Shanghai）  
对应复核：[REPAIR_REVIEW_2026-10-06.md](REPAIR_REVIEW_2026-10-06.md)  
范围：F1–F4；保留既有未提交改动及原交付记录，不改写此前测试结论。

## 修复结果

| 编号 | 修复 | 回归证据 |
| --- | --- | --- |
| F1 | `useBlocker` 以实际编辑资源判断是否离开：比较 pathname 与 `storyboard` 查询参数；仅 `run` 等进度参数的内部更新不拦截。 | Chromium：A/B 已保存分镜之间后退、前进；取消确认后 URL、分镜、编辑内容及 dirty 保持，确认后才切换。 |
| F2 | 完成任务详情请求发起时冻结当前资源、页面代次和编辑代次；响应返回后再次核验。失效响应仅更新已保存分镜列表并通知，不替换编辑区。 | Chromium：延迟生成 B 的详情请求，在 A 编辑后放行；A 的内容和 dirty 保留，B 可从列表主动打开。 |
| F3 | 保存成功、保存冲突、采纳及放弃提案均绑定发起时的分镜 ID、页面代次和编辑代次。失效响应最多更新对应摘要，不能覆盖当前 `working`、`detail`、dirty 或冲突状态。 | Chromium：分别延迟 A 的保存成功、409 和采纳成功，再切换 B 后放行；B 的 URL、标题、镜头内容和已保存状态保持不变。 |
| F4 | `rewriteContext` 显式接收 base storyboard revision。入队预检传锁定草稿的当前 revision；读取既有任务传 `StoryboardRunContext.baseStoryboardRevision`，不再使用草稿当前 revision。 | PostgreSQL/Testcontainers 集成测试：r1 创建重做任务，保存草稿至 r2 后重读同一 run；整个 context 与初始值相等，base revision 仍为 1。 |

## 本轮验证

| 命令 / 套件 | 结果 |
| --- | --- |
| `npm run build`（`apps/storyboard-web`） | 通过。 |
| `npx playwright test e2e/storyboard-protection.spec.ts --reporter=line` | **11 passed**。本轮直接重跑的资源保护子套件，含新增的延迟采纳响应回归。 |
| `npm run test:e2e -- --grep "response injected"` | **16 passed**。确定性覆盖 F1–F3，以及既有 R3/R7/R8/令牌续期回归。 |
| `mvnw.cmd -Dapi.version=1.44 -B -Dtest=StoryboardIntegrationTest test`（JDK 21） | **8 tests，0 failures，0 errors**。通过 Testcontainers PostgreSQL 覆盖 F4 冻结上下文。 |
| `mvnw.cmd -Dapi.version=1.44 test`（JDK 21） | **111 tests，0 failures，0 errors，0 skipped**。 |
| `node docs/storyboard-mvp/fixtures/validate-fixtures.mjs` | 通过；R6 正反样例和容量预算有效。 |
| `scripts/verify-storyboard-http-e2e.ps1 -PostgresPort 25432 -JavaPort 28080 -GatewayPort 23001 -WebPort 25173` | **17 passed**：1 条真实 HTTP 工作流 + 16 条响应注入浏览器回归。脚本使用临时 PostgreSQL、mock Gateway，并已清理容器和进程。 |
| `git diff --check` | 通过。 |

## 交接与边界

- F1–F3 浏览器用例为确定性响应注入测试，覆盖资源切换和异步响应时序；完整 HTTP 用例验证正常端到端工作流，但不将 F1–F3 误表述为真实 Java/Node 后端时序闭环。
- F4 通过真实 PostgreSQL/Testcontainers 的 Java 集成套件覆盖。
- 未调用真实模型；SB-12 仍为 **BLOCKED**，SB-13 仍待真实用户试用，均未标记完成。
