# F1～F4 修复独立验收

日期：2026-10-06（Asia/Shanghai）  
目录：`D:\desktop\screenplay-agent-backend`  
范围：复核报告 `REPAIR_REVIEW_2026-10-06.md` 中的 F1～F4。

## 结论

**F1、F2、F3、F4 均通过本次验收。** F1 默认入口返回路径已补齐，复核报告指出的最后一个 P1 缺口已关闭。

| 项目 | 验收结果 |
| --- | --- |
| F1 导航保护 | 比较实际编辑资源的 pathname 与 `storyboard` 参数。只有非空的显式允许标记才能绕过 blocker，因此默认入口的缺省参数不会被误放行；`run` 等仅进度参数的内部更新不拦截。 |
| F2 完成结果详情迟到 | 返回详情前后均核验页面代次、编辑资源、编辑代次与 dirty。迟到结果只更新已保存分镜列表，不替换编辑区。 |
| F3 旧保存响应跨分镜污染 | 保存成功、409 冲突与候选采纳均绑定发起时的分镜和页面会话；失效响应不能改写当前 `working`、`detail`、dirty 或冲突状态。 |
| F4 冻结 revision | 既有重做任务读取持久化的 `baseStoryboardRevision`。草稿推进后，同一任务上下文仍使用创建任务时冻结的 revision，旧结果被后端 revision 校验拒绝。 |

## F1 默认入口回归

新增 Chromium 响应注入用例：

1. 从不带 `?storyboard=` 的默认入口加载 A。
2. 切到 B 并修改，不保存。
3. 浏览器后退。取消确认后，URL 仍为 B，标题、编辑内容和 dirty 均保持。
4. 再次后退并确认后，才回到无 `storyboard` 参数的默认入口 A。

修复的关键是允许跳转标记必须为非空值后才可与目标分镜比较，避免 `null === null` 将默认资源切换当成已确认导航。

## 本轮证据

| 命令 / 套件 | 结果 | 验证类型 |
| --- | --- | --- |
| `npm run build`（`apps/storyboard-web`） | 通过。 | 前端构建。 |
| `npm run test:e2e -- --grep "default storyboard entry"` | **1 passed**。 | F1 默认入口的确定性浏览器响应注入。 |
| `npm run test:e2e -- --grep "response injected"` | **17 passed**。 | 确定性浏览器响应注入，覆盖 F1～F3 及既有 R3/R7/R8、令牌续期。 |
| `mvnw.cmd -Dapi.version=1.44 -B -Dtest=StoryboardIntegrationTest test`（JDK 21） | **8 tests，0 failures，0 errors**。 | Testcontainers PostgreSQL，覆盖 F4 冻结上下文。 |
| `scripts/verify-storyboard-http-e2e.ps1 -PostgresPort 35432 -JavaPort 38080 -GatewayPort 33001 -WebPort 35173` | **18 passed**：1 条真实 HTTP 工作流、17 条响应注入。 | 临时 PostgreSQL、Java、mock Gateway、Vite 与 Chromium；执行后容器和进程已清理。 |
| `git diff --check` | 通过。 | 静态差异检查。 |

完整联调日志：`C:\Users\tcf\AppData\Local\Temp\screenplay-sb11-33368\playwright.log`。

## 验证边界与未完成事项

- F1～F3 的竞争时序由浏览器响应注入稳定复现与断言，不应表述为真实 Java/Node 后端时序联调。
- 真实 HTTP 用例验证正常端到端工作流；它使用临时测试数据库和 mock Gateway，不调用真实模型。
- F4 有 Testcontainers PostgreSQL 集成覆盖。此轮未修改 Gateway 或共享契约。
- SB-12 真实 provider 质量、费用与时延验收仍为 BLOCKED；SB-13 真实用户试用仍待开展。

## 最后一处 F1 修复的独立关闭复核

本轮独立读取生产实现和新增用例后，重新执行前端构建、全部 `response injected` 浏览器回归及 `git diff --check`，均通过。浏览器实际结果为 **17 passed（15.3s）**，包含默认入口后退的确认、取消保留编辑和确认后切换三个断言阶段。

`allowedStoryboardId !== null` 已阻止原先 `null === null` 提前放行。新增用例显式等待确认框计数，避免在导航尚未处理时提前通过。**F1 最后一个 P1 缺口关闭，结合此前 F2/F3/F4 复核，本轮工程修复项全部签收。**

本次针对最后的前端修改验证；Java、Gateway 和真实 HTTP 闭环的结论沿用此前已记录的验证结果。本次没有真实模型调用或用户试用。

独立浏览器日志：`C:\Users\tcf\AppData\Local\Temp\storyboard-f1-closure-31272\playwright.log`。本次自建 Vite 与浏览器进程已停止。
