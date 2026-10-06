# 单场景分镜工作台：独立验收复核

日期：2026-10-06（Asia/Shanghai）  
验收目录：`D:\desktop\screenplay-agent-backend`  
范围：当前工作区的 SB-01～SB-13 交付物、Java/Node/React 实现、现有自动化及补充边界探针。审核期间未修改业务代码。

## 1. 签收结论

**本轮不通过整体验收，需要修复后复验。**

正常的 mock HTTP 闭环可以运行，试用准备文档也已交付；但是镜头调序、并发保存以及前端编辑保护仍存在问题。SB-12 没有真实 provider 质量证据，SB-13 没有真实用户试用记录。因此不能将“代码和准备材料已交付”签为“产品已完成验收”。

- 工程正常流程：通过现有测试，另有本报告列出的边界缺陷。
- SB-12：**BLOCKED**，未执行真实模型质量、用量和时延验收。
- SB-13：**准备完成、待试用**，不能标记为试用通过。
- 本轮未调用付费 provider，也未组织或模拟真实用户试用。

## 2. 本轮实际执行结果

| 检查 | 结果与证明范围 |
| --- | --- |
| JDK 21，`mvnw.cmd -Dapi.version=1.44 -B test` | **104 tests，0 failures，0 errors，0 skipped**；包含 Docker/Testcontainers PostgreSQL。新增 `StoryboardIntegrationTest` 为 3 个测试方法。 |
| Gateway `npm run check` | 通过。 |
| Gateway `npm run storyboard:check` | 通过；契约、固定输出、工具保存链路检查，不代表真实模型质量。 |
| Gateway `npm run smoke` | 通过；包含 Node HTTP、SSE、回放、权限、幂等及取消检查。 |
| `node docs/storyboard-mvp/fixtures/validate-fixtures.mjs` | 通过；4/6/8 镜头正反例、revision/resultRef、样例字节预算。 |
| 工作台 `npm run build` | 通过。 |
| 工作台 `npm test` | 退出码 0，但输出 **No test files found**；不能算前端功能测试通过。 |
| `scripts/verify-storyboard-http-e2e.ps1` | 通过。独立 PostgreSQL、Java、Node、Chromium，真实 HTTP，`AGENT_MODE=mock`、`JAVA_MODE=http`，前端非 fixtures。 |
| 补充 API 探针 | 实际复现调序 500，以及同 revision 并发保存全部成功的覆盖问题，见 R1/R2。 |
| 补充 Chromium 探针 | 实际复现两种编辑丢失及取消离开仍跳转；注入 INTERRUPTED 响应复现持续忙碌状态，见 R3/R7/R8。 |
| 补充 Node 隔离探针 | 确认模型可见 schema 缺失、等价 JSON 回执被拒绝、中文上下文超预算；无真实 LLM 调用。 |
| `git diff --check` | 通过。 |

原有浏览器用例完成了：登录 → 创建项目 → 导入并解析 → 生成 4 镜 → 编辑保存 → 刷新恢复 → 单镜重做 → 采纳 → 下载 Markdown，并检查原剧本与非目标镜头不变。它没有覆盖调序、并发写、本地编辑与异步响应竞争等情况。

## 3. 必须修复的发现

### R1 · P1 · 同 revision 并发保存发生静默覆盖

位置：[StoryboardService.java:52](D:/desktop/screenplay-agent-backend/src/main/java/com/urke/saasbackendstarter/screenplay/service/StoryboardService.java:52)、[StoryboardDraft.java:24](D:/desktop/screenplay-agent-backend/src/main/java/com/urke/saasbackendstarter/screenplay/domain/StoryboardDraft.java:24)。

保存先普通读取草稿、比较 `expectedRevision`，随后改镜头并加一。`revision` 是普通列，没有真正的乐观锁；保存也未参与采纳使用的锁协议。

**真实 HTTP 复现：** 对同一分镜同时提交 6 份不同内容，均携带 `expectedRevision=3`。6 个请求全部返回 **200 / revision=4**，最后数据库仅保留其中一份内容。没有任何一个请求收到预期的 409。

修复要求：保存、采纳等修改统一采用“首次读取即锁定最新草稿，再校验 revision”，或使用数据库 CAS/真正的乐观锁，将并发冲突映射为 409。不能只给后续写语句加锁，继续使用之前缓存的旧实体。

复验：两个不同 PUT 同 revision 并发，必须恰好一个成功、另一个 409；保存与采纳并发也不能静默覆盖结果。

### R2 · P1 · 镜头调序保存返回 500

位置：[StoryboardService.java:52](D:/desktop/screenplay-agent-backend/src/main/java/com/urke/saasbackendstarter/screenplay/service/StoryboardService.java:52)。

代码先设置负序号，调用 `saveAll`，随即设置最终序号，中间没有 flush。Hibernate 将最终序号直接更新到数据库，交换现有顺序时触发 `(storyboard_id, order_index)` 唯一约束。

**真实 HTTP 复现：** GET 当前 r3 分镜，将保存数组的前两镜交换，保持 ID 集合与其余字段不变，PUT 返回 **500 / Internal server error**。Java 日志明确记录 `duplicate key value violates unique constraint "uk_storyboard_shot_order"`。

修复要求：在同一受并发保护的事务中，先持久化临时顺序并 flush，再写最终顺序；也可以采用经验证的等效数据库方案。

复验：真实 PostgreSQL 下上移、下移、完整反转后保存和刷新；ID 与内容稳定、顺序正确、revision 只增加一次。不能仅用内存 fixtures 验证。

### R3 · P1 · 异步回写会覆盖本地未保存编辑

位置：[StoryboardWorkspacePage.tsx:74](D:/desktop/screenplay-agent-backend/apps/storyboard-web/src/pages/StoryboardWorkspacePage.tsx:74)、[StoryboardWorkspacePage.tsx:87](D:/desktop/screenplay-agent-backend/apps/storyboard-web/src/pages/StoryboardWorkspacePage.tsx:87)。

两条路径均无条件整体替换 `working` 并清除 `dirty`：

1. 已生成候选后，修改另一个镜头但不保存，再返回候选镜头采纳。服务器返回的是已保存分镜，前端会丢弃另一个镜头的本地修改。
2. 提交保存 A，在响应返回前继续输入 B。编辑框仍可输入，旧响应返回后将 B 覆盖，并显示为已保存。

**真实浏览器复现：** 第一条的 `unsavedEditPreserved=false`；第二条将真实 PUT 的响应暂缓投递后，`newerEditPreserved=false`、`oldRequestSnapshotReplacedEditor=true`。两次结果均为保存按钮禁用，说明本地新编辑丢失后还被标记为已保存。这两条使用真实 Java/Node HTTP 和临时数据库，模型为 mock。

修复要求：采纳前明确处理 dirty，不能默默丢弃；保存应跟踪提交时的编辑版本，只确认已提交内容，保留之后的输入。也可在明确的提交阶段禁用会导致覆盖的操作。

复验：分别用真实保存回调和延迟响应验证上述两条路径，检查编辑内容及 dirty 状态。

### R4 · P1 · 真实模型没有获得完整的分镜结果 schema

位置：[storyboard-tools.ts:13](D:/desktop/screenplay-agent-backend/examples/pi-agent-gateway/src/storyboard-tools.ts:13)、[profiles.ts:33](D:/desktop/screenplay-agent-backend/examples/pi-agent-gateway/src/profiles.ts:33)。

`save_storyboard_result` 使用 `Type.Unknown()` 定义 `result`。模型实际看到的参数是：

```json
{"type":"object","required":["result"],"properties":{"result":{}},"additionalProperties":false}
```

系统提示也没有镜头字段与枚举的完整定义。生成上下文仅包含场景和目标数量，没有镜头结构可参考；但保存时却要求严格结构。mock 在程序内直接构造合规对象，不能证明 live 模型知道该格式。

这是已确认的输入契约缺口，**不代表本轮已经观察到真实模型失败**，本轮没有调用真实模型。

修复要求：将正式结果 schema 实际注册为模型可见工具参数，包含 generate/rewrite 分支、mode、所有镜头字段、枚举及限制；保持与 Java 权威校验一致。

复验：检查 provider 实际收到的工具 schema，再在 SB-12 中验证真实模型能完成读取、结构化生成和保存。

### R5 · P2 · JSON 键顺序导致已保存结果被误判失败

位置：[runtime.ts:180](D:/desktop/screenplay-agent-backend/examples/pi-agent-gateway/src/runtime.ts:180)。

最终确认使用 `JSON.stringify(finalValue) === JSON.stringify(savedValue)`。隔离探针将完全相同的 `artifactId` 和 `resultRef` 调整键顺序，`hasValidStructuredResult()` 实际返回 false，随后会进入 `invalid_structured_output`。这会出现产物已保存、任务却显示失败的情况。

修复要求：校验回执结构后，按字段语义比较；不要依赖对象键顺序。

复验：外层及 resultRef 内部换序都应接受；ID、类型或归属被篡改仍须拒绝。

### R6 · P2 · 8,000 字符输入限制与 16 KiB 上下文预算不一致

位置：[storyboard-schema.ts:68](D:/desktop/screenplay-agent-backend/examples/pi-agent-gateway/src/storyboard-schema.ts:68)、[StoryboardService.java:68](D:/desktop/screenplay-agent-backend/src/main/java/com/urke/saasbackendstarter/screenplay/service/StoryboardService.java:68)。

Java/前端以 LF 字符长度判断场景不超过 8,000；Node 对整个冻结 context 限制 16 KiB UTF-8。Java 入队前没有按该完整 context 预算预检。

**隔离验证：** 合法 fixture 中替换为标题加 6,000 个中文“字”，重算正确 hash，得到 `sceneChars=6012`、`contextBytes=18370`，Node 拒绝：`Storyboard context exceeds its UTF-8 byte budget`。该字符长度通过 Java 源码中的长度条件；本轮未对这一项单独执行完整 Java HTTP 生成。

修复要求：统一并说明字符与字节边界，在入队前检查完整 UTF-8 context。若保留 8,000 字符产品承诺，应同步调整各层容量；单镜重做还要计入目标镜与相邻镜快照，不能静默截断。

复验：覆盖接近上限的中文生成和重做，所有层的接受/拒绝结果一致，拒绝时不先创建注定失败的任务。

### R7 · P2 · INTERRUPTED 终态被前端当成进行中

位置：[StoryboardWorkspacePage.tsx:10](D:/desktop/screenplay-agent-backend/apps/storyboard-web/src/pages/StoryboardWorkspacePage.tsx:10)、[types.ts:13](D:/desktop/screenplay-agent-backend/apps/storyboard-web/src/types.ts:13)。

[AgentRecoveryService.java:25](D:/desktop/screenplay-agent-backend/src/main/java/com/urke/saasbackendstarter/screenplay/service/AgentRecoveryService.java:25) 在重启恢复时将未完成任务设为 INTERRUPTED。前端只将 COMPLETED、FAILED、CANCELLED 认作终态，导致持续轮询、显示停止操作，并禁用新生成。

**浏览器响应注入复现：** 返回符合后端状态的 `status=interrupted`，页面仍将其作为 busy，生成按钮禁用，观察到持续状态轮询。本项没有真实杀掉或重启 Java；Java 产生该状态的路径来自源码核对。

修复要求：对齐完整运行状态枚举及终态处理，展示中断原因，允许明确发起新任务；同时校对 FINALIZING 的进行中展示。

复验：恢复 INTERRUPTED 时停止轮询且可新建任务。浏览器响应注入只能证明 UI 处理，实际重启恢复应另有集成证据。

### R8 · P1 · 取消离开确认仍会跳转并丢失编辑

位置：[StoryboardWorkspacePage.tsx:35](D:/desktop/screenplay-agent-backend/apps/storyboard-web/src/pages/StoryboardWorkspacePage.tsx:35)。

未保存保护挂在 document 的 click 冒泡监听上；React Router 的 Link 已在更早阶段执行导航，之后再 `preventDefault()` 不能撤销此次 SPA 跳转。

**真实浏览器复现：** 在工作台修改画面但不保存，点击“剧本版本”，对确认框执行取消。探针得到 `confirmationShown=true`、`navigatedDespiteCancel=true`，最终路径仍变为 `/projects/1/scripts`，本地编辑随工作台卸载丢失。

修复要求：在路由真正发生前阻止导航，统一处理站内 Link、后退和需要保护的切换行为；保留刷新/关页的 beforeunload 提醒。

复验：取消离开后 URL、页面和未保存内容均保留；确认离开才导航；浏览器后退也须覆盖。

## 4. SB 任务签收状态

| 任务 | 本轮判断 |
| --- | --- |
| SB-01 / SB-02 | 样本、验收口径、schema、正反例等交付物齐备；样例验证通过。跨层预算及模型可见 schema 的实现仍需修复。 |
| SB-03 / SB-04 | 主要实体、快照、API、任务及回调已实现；R1/R2 阻止工程签收。 |
| SB-05 | mock 工具链通过；R4/R5/R6 待修复，不能视为 live 能力已验收。 |
| SB-06 / SB-07 | 工作台框架与生成正常路径通过；恢复终态等边界仍需修复。 |
| SB-08 / SB-09 | 编辑、重做、比较正常路径通过；调序、并发及本地编辑保护未通过。 |
| SB-10 | 已保存版本的 Markdown 导出在真实 HTTP 浏览器路径中通过；没有真人使用产物的证据。 |
| SB-11 | 正常闭环通过，异常验收不完整；本轮探针已发现实际缺陷，不能继续标为整体验收完成。 |
| SB-12 | BLOCKED；缺少真实 provider 配置/凭据及真实质量、usage、时延记录。 |
| SB-13 | 准备文档完成，实际试用未发生，保持“待试用”。 |

## 5. 修复与再次签收顺序

1. **Java：R1/R2。** 先统一修改事务的并发协议，再修复调序持久化，补真实 PostgreSQL 并发与排序回归。
2. **前端：R3/R7/R8。** 与 Java/Node 并行处理编辑保护、导航拦截和任务恢复，补有时序控制的浏览器回归。
3. **Node：R4/R5/R6。** 完整暴露模型 schema、按语义确认回执，并与 Java 对齐上下文容量。此时无需付费模型即可完成针对性验证。
4. **重新验收 SB-11。** 原有正常闭环及本轮缺陷回归均通过后，才能签收工程闭环。保留失败/取消/断线等测试的真实覆盖边界，不把串行状态检查写成并发竞争验证。
5. **执行 SB-12。** 提供受控 provider 配置，按固定样本取得真实输出、每轮 usage、耗时和人工逐镜评价。
6. **执行 SB-13。** 使用上述通过版本邀请 3～5 位目标用户，至少 3 位独立完成输入到导出，并记录实际制作用途与前三项问题。

SB-12 交接文档还需澄清计数：当前写“最多 8 次 provider 调用”，但账本列的是 8 个完整生成 run。正常流程包含模型请求上下文、模型提交结果、模型确认保存回执，8 个 run 通常至少对应 24 轮逻辑模型请求，SDK 重试另计。应分开记录 run 数、provider 请求数和每轮费用，继续遵守既定费用上限。

补测建议：访问令牌续期后的请求重试和本地编辑保护。当前 `api-context.tsx` 的 `getTokens` 闭包与 `java-api.ts` 刷新后的递归重试存在使用旧会话的风险，更新 API 对象又会触发工作台重新加载。本轮尚未做令牌到期专项浏览器复现，不将它伪装成已动态验证的结果。

`PILOT_REPORT.md`、`RUNBOOK.md`、`SB12_REAL_MODEL_QUALITY_REPORT.md` 对 mock/live/真实用户证据的区分是正确的，应保留；原执行计划和契约中的初始“待执行/待联调”文字应在本轮修复签收后统一更新状态。

## 6. 本机复核证据位置

- 原正常流程日志：`C:\Users\tcf\AppData\Local\Temp\screenplay-sb11-13768\playwright.log`。
- 完整补充复核日志目录：`C:\Users\tcf\AppData\Local\Temp\screenplay-sb11-17376`，含 `api-probes.log`、`browser-probes.log`、`playwright.log`；上述具体结果已摘录到本报告，临时目录不是长期交付依赖。
- 临时探针目录：`C:\Users\tcf\AppData\Local\Temp\storyboard-final-review-probes`。探针只针对本次新建的临时数据库和进程运行；使用 PostgreSQL 25432、Java 28080、Gateway 23001、Web 25173，未使用现有业务数据库。
- 首次浏览器补充探针因 label 的精确匹配写法超时；修正临时测试定位器后重新运行，上述四项浏览器探针均完成。没有为使检查通过而修改业务代码。
- 本报告记录的工作区尚有大量未提交实现；结论针对本轮读取和执行的文件，不代表某个已发布版本的认证。下一轮在固定代码版本上复验并归档正式回归用例。
