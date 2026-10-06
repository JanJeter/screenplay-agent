# 修复交付后的独立复核

日期：2026-10-06（Asia/Shanghai）  
目录：`D:\desktop\screenplay-agent-backend`  
基线：`df8de0af5540e8d2095ac7f10d69cd99d9e07470` 加本轮未提交工作区。审核未修改业务代码。

## 结论

**暂不签收整体验收通过。** 三条修复线的主要改动有效，原有回归全部通过；补充检查仍复现了 4 项边界问题，其中 2 项会直接丢失未保存编辑。它们不在当前 7 个浏览器用例及 110 个 Java 用例的覆盖范围内。

本文件补充 `REPAIR_ACCEPTANCE.md`，保留原交付记录，不改写之前的测试结果。SB-12 仍未执行真实模型验证，SB-13 仍待真实用户试用。

## 本轮实际重跑

| 检查 | 结果 |
| --- | --- |
| JDK 21：`mvnw.cmd -Dapi.version=1.44 -B test` | **110 tests，0 failures，0 errors，0 skipped**，包含 Testcontainers PostgreSQL。 |
| Gateway check / storyboard:check / smoke | 全部通过，含实际 transport 中的模型工具 schema、回执语义及容量检查。 |
| 前端 build | 通过。 |
| fixtures 校验 | 通过。 |
| 独立 PostgreSQL + Java + Node mock HTTP + Chromium | **7 passed**：1 条真实 HTTP 闭环，6 条浏览器响应注入用例。不能将 7 条都描述为完整真实后端闭环。 |
| 补充 Node HTTP reader 边界探针 | 精确 262144 字节接受；262145 字节在 Content-Length、chunked 两种传输下均拒绝。 |
| `git diff --check` | 通过。 |

原问题复核：R1 的首次写锁和真实并发回归有效；R2 调序 flush 有效；R4 模型可见完整 schema、R5 回执语义比较有效；R6 的 256 KiB 容量和入队前预检有效；R7 终态恢复、令牌续期处理已有对应回归。R3/R8 的原始用例通过，但跨分镜和异步时序仍未完整保护。

## F1 · P1 · 同一工作台后退切换分镜，绕过未保存确认

位置：[StoryboardWorkspacePage.tsx:41](D:/desktop/screenplay-agent-backend/apps/storyboard-web/src/pages/StoryboardWorkspacePage.tsx:41)，加载后清空编辑的位置为第 29 行。

`useBlocker` 只比较 pathname。已保存分镜 A/B 使用相同 pathname，区别在 `?storyboard=`，所以浏览器后退切换不会被拦截。

复现：打开 A → 点击已保存分镜 B → 修改 B 不保存 → 浏览器后退。

实际 Chromium 响应注入探针结果：**确认框次数 0，URL 回到 A，编辑器显示 A 原内容，dirty=false**。B 的未保存内容丢失。现有后退用例只验证不同 pathname 之间跳转。

修复要求：以实际编辑资源变化判断导航是否应拦截，覆盖 storyboard 查询参数；同时区分仅更新 run 等进度参数的内部导航，避免误拦截正常任务更新。

回归：A/B 之间后退与前进，取消确认后 URL、分镜 ID、编辑内容和 dirty 都保留；确认离开才切换。

## F2 · P1 · 生成完成后的详情请求仍会吞掉后续编辑

位置：[StoryboardWorkspacePage.tsx:56](D:/desktop/screenplay-agent-backend/apps/storyboard-web/src/pages/StoryboardWorkspacePage.tsx:56)，下一行在 `await api.getStoryboard(...)` 返回后写入 working。

`applyCompletedRun` 只在请求详情前检查 dirty，请求后只检查 observer 身份。同一个 observer 有效，不代表用户在等待过程中没有继续编辑。

复现：当前 A 没有 dirty，观察生成 B 的任务 → COMPLETED 后延迟 GET B 的响应 → 在 A 输入未保存内容 → 放行 GET B。

实际 Chromium 响应注入结果：**URL 自动切到 B，编辑器变为 B 原内容，dirty=false**。等待期间输入的 A 内容丢失。

修复要求：请求发起时记录当前编辑资源与编辑代次；返回时同时复核资源、代次和 dirty。若有新编辑，只通知新分镜已保存并更新列表，不替换编辑区。

回归：延迟完成结果详情，期间编辑当前分镜，释放响应后当前内容及 dirty 保留，新产物仍可由用户主动打开。

## F3 · P2 · 切换分镜后，旧保存响应覆盖当前页面

位置：[StoryboardWorkspacePage.tsx:94](D:/desktop/screenplay-agent-backend/apps/storyboard-web/src/pages/StoryboardWorkspacePage.tsx:94)，后续仅以全局 editVersion 判断是否整体替换 working。

复现：编辑 A 并保存，延迟 PUT 响应 → 确认切换已保存分镜 B，等待 B 显示 → 放行 A 保存响应。

实际 Chromium 响应注入结果：**URL 仍是 storyboard=b，编辑器和标题却重新显示 A**。`state.detail` 同样被旧响应覆盖。用户后续操作可能针对自己没有打算编辑的分镜。

修复要求：保存响应绑定分镜 ID 和页面/编辑会话代次。失效响应最多更新对应分镜的已保存摘要，不能替换当前 working、detail、conflict 或 dirty。核对错误响应及采纳等其他异步操作使用同一保护原则。

回归：延迟 A 的保存成功及冲突响应，切换 B 后再放行，URL、标题、镜头 ID、内容、dirty 均属于 B。

## F4 · P2 · 重做任务的冻结 revision 随当前草稿变化

位置：[StoryboardService.java:56](D:/desktop/screenplay-agent-backend/src/main/java/com/urke/saasbackendstarter/screenplay/service/StoryboardService.java:56)、[StoryboardService.java:88](D:/desktop/screenplay-agent-backend/src/main/java/com/urke/saasbackendstarter/screenplay/service/StoryboardService.java:88)。

重构后的 context 读取调用 `rewriteContext`，辅助方法使用当前 `draft.getRevision()`，没有读取任务持久化的 `c.getBaseStoryboardRevision()`。目标镜头快照仍是旧值，因而会返回“新 revision + 旧镜头快照”。

运行当前已编译生产 `StoryboardService.contextWire()` 的隔离服务探针，repository 使用 JDK Proxy stub，结果为：

```text
frozenExpected=1
initialReturned=1
afterBoardRevision2Returned=2
targetSnapshotUnchanged=true
fullContextEqual=false
```

这是**隔离服务探针，不是 HTTP/PostgreSQL 复现**。正确的不变性断言失败。结果保存仍检查原冻结 revision，未观察到该问题导致覆盖新数据。

修复要求：辅助方法显式接收 base revision；入队预检传受锁保护的当前 revision，读取既有任务时传冻结的 `c.getBaseStoryboardRevision()`。

回归：创建 r1 重做任务 → 读取上下文 → 保存草稿至 r2 → 再读取同一 run；上下文仍完全一致，基准 revision 仍为 1，旧提案不能覆盖 r2。

## 收口顺序

1. 前端将 F1/F2/F3 一起处理，统一“当前编辑资源 + 页面代次 + 编辑代次”的有效性判断，补 3 条对应的浏览器时序回归。
2. Java 修复 F4，补冻结上下文不变性回归。Gateway 本轮复核没有新增修复项。
3. 重跑相关回归与完整 HTTP 闭环，再更新工程签收结论；继续保持 SB-12/BLOCKED、SB-13/待试用的真实状态。

## 证据位置

- 本轮 Maven 日志：`C:\Users\tcf\AppData\Local\Temp\storyboard-repair-recheck-maven.log`。
- 本轮浏览器套件日志：`C:\Users\tcf\AppData\Local\Temp\screenplay-sb11-17592\playwright.log`。
- 前端补充探针：`C:\Users\tcf\AppData\Local\Temp\storyboard-repair-web-review.cjs`，独立 Vite 15176 + Chromium 响应注入，未写业务数据库。
- Java 冻结探针：`C:\Users\tcf\AppData\Local\Temp\storyboard-context-review-ece6af5fc1aa4bc99b347cd6e4351b66\ContextFreezeProbe.java`。
- 所有本轮创建的测试服务和浏览器已停止，临时测试容器已由脚本清理；未调用真实模型。
