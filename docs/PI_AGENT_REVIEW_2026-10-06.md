# Pi Agent 集成修复复审（2026-10-06）

## 最新复核：第四轮

**本轮明确提交的三项修复验收通过：取消覆盖完成态、对白换行边界/CRLF、真实 Java 场景 ID。** 已独立重跑测试，并通过临时 PostgreSQL、真实 Spring HTTP 与 Node mock provider 验证。本轮没有调用真实 LLM，没有修改业务代码。

### 三项修复的验收结果

| 修复 | 实际验证 | 结论 |
| --- | --- | --- |
| 取消首次读取即加锁 | 真实 HTTP cancel 与独立 terminal 事务竞争；完成先持锁最终 COMPLETED，取消先持锁最终 CANCELLED | 上轮旧对象覆盖终态的 P1 关闭 |
| 对白边界与换行规范 | 吞掉非末场分隔换行的补丁返回 HTTP 409；CRLF 补丁采纳后全部规范为 LF；重复场景只修改指定场景，重新分析仍为 3 场 | 上轮换行与 CRLF 缺陷关闭 |
| rewrite mock 实际场景 ID | 真实 Java list_scenes → get_scene → save_draft 完成；草稿目标为实际数字 ID，采纳成功，其他场景保持一致 | 原硬编码 ID 故障关闭 |

取消并发验证使用两个独立线程/事务和可控 latch，使两个事务真实竞争数据库行锁。HTTP 入口使用实际鉴权与 `AgentRunService.cancel()`，没有用模拟实体代替 JPA。本轮并发执行验证针对目标 run；子任务首次读取加锁也已静态核对，尚未将所有父子任务交错组合穷举。

真实联调输出：

```text
REVIEW_PROFILE_general=completed:null
REVIEW_PROFILE_rewrite_dialogue=completed:null
REVIEW_MOCK_REWRITE_ACCEPTED_REAL_SCENE=true
REVIEW_MOCK_REWRITE_PRESERVED_OTHER_SCENES=true
REVIEW_MOCK_REWRITE_SCENE_COUNT=3
REVIEW_BOUNDARY_PATCH_HTTP_STATUS=409
REVIEW_CRLF_AND_DUPLICATE_SCENE_EXACT=true
REVIEW_REANALYZED_SCENE_COUNT=3
REVIEW_TERMINAL_FIRST_FINAL=COMPLETED
REVIEW_CANCEL_FIRST_FINAL=CANCELLED
```

### 独立重跑的检查

- JDK 21，`mvnw.cmd -Dapi.version=1.44 -B test`：**101 项，0 failures，0 errors，0 skipped**，包含 Testcontainers PostgreSQL。
- Gateway `npm run check`：通过。
- Gateway `npm run smoke`：通过。
- `git diff --check`：通过。
- 临时真实 Java HTTP 联调：本轮受影响的 general、rewrite_dialogue 与对白采纳通过；没有把未重跑的其他专业任务记为本轮新增验证。

日志保留在 `%TEMP%\screenplay-agent-review4-maven.log`、`%TEMP%\pi-agent-review4-integration.log`、`%TEMP%\pi-agent-review4-node.log`。隔离应用、Node 子进程和测试容器已退出。

### 新发现的 P2：mock helper 没有限定最新工具结果

位置：[runtime.ts:145](D:/desktop/screenplay-agent-backend/examples/pi-agent-gateway/src/runtime.ts:145)。

`findSceneId()` / `findScene()` 从整个 Context 的开头递归查找首个符合形状的对象，没有限定来自最新成功的 `list_scenes` / `get_scene` 工具结果。临时 HTTP 适配器配合真实 Pi mock runtime 已复现：

```text
正常首次请求：list 返回101 → get_scene101 → 成功
同一Node session下一轮：list 返回202 → 仍请求旧101 → 失败
直接Gateway用户消息为JSON {sceneId:"999",content:"输入数据示例"}：
list 返回202 → 请求用户消息中的999 → 失败
```

Java 当前每次 run 新建 Node session，正常 Java 主链路的原 R4 缺陷已经关闭；直接 Gateway 的多轮模拟使用仍受这个问题影响。建议从后向前选取 `role === "toolResult"`、正确 `toolName` 且 `isError !== true` 的消息，再解析该工具的返回内容。补充“旧结果存在”和“用户输入含 sceneId JSON”两项定向回归即可。

### 当前后续工作

1. 将本轮真实数据库两种取消时序、真实 Java 对白采纳链路纳入常规自动化回归，避免仅依赖 Mockito 调用顺序检查。
2. 修正上述 mock helper 的结果选择逻辑。
3. 之前的 R5（其他产物 schema）、R6（本地终态通知/关流）、R7（事件 envelope）、R8（目标场景幂等）、R9（当前请求截断）仍是待办；本轮没有声称或验收这些问题已关闭。
4. 完成这些待办后，继续前端创作闭环验收，再扩展多 Agent 协作。

---

## 第三轮复审记录（历史，状态以第四轮为准）

本节对应用户再次反馈“解决了”后的当前工作区。**修复已有进展，但仍存在一个已用真实数据库复现的 P1：取消请求可以把 COMPLETED 覆盖成 CANCELLING。** 以下状态表取代后面历史记录中的修复状态；历史发现保留用于追踪。

### 本轮验证

- Java 全套 **98 项通过，0 失败，0 错误，0 跳过**。比上一轮新增 6 项：对白采纳 1 项、SSE 回放 2 项、worker 状态 3 项。
- Node `npm run check`、`npm run smoke` 均通过。
- 使用独立 Testcontainers PostgreSQL、真实 Spring HTTP、Node 模拟 provider，再次验证受影响的 general 与 rewrite_dialogue 两条链路：前者完成，后者仍为 `failed / gateway_failed`。
- 通过真实 HTTP 草稿采纳验证，两个相同场景中只改第二场，其余字符完全保持，结果正确。
- 通过真实 HTTP 草稿采纳验证，包含场景末尾换行的补丁仍可破坏下一场标题；3 场重新解析后变成 2 场。
- 通过真实 HTTP cancel 接口、真实 JPA 与 PostgreSQL，控制两个事务交错，确认已提交的 COMPLETED 被覆盖成 CANCELLING。
- 没有调用真实模型，没有修改业务代码或业务数据库；只更新本复审文档。

本轮日志：`%TEMP%\screenplay-agent-review3-maven.log`、`%TEMP%\pi-agent-review3-integration.log`、`%TEMP%\pi-agent-review3-node.log`。隔离应用、Node 子进程与数据库容器均已结束。

### 上轮问题的当前状态

| 编号 | 当前状态 | 说明 |
| --- | --- | --- |
| R1 对白草稿应用 | **主要问题已修，换行边界仍需修** | 已改为局部 JSON patch，按场景分区长度定位，普通正文与重复场景用例通过；末尾换行和 CRLF 有残留问题，见下文 |
| R2 SSE 回放漏事件 | **已关闭原问题** | replaying 阶段缓冲实时事件，历史回放后排空；新增两个测试通过，符合当前单消费者按序提交的模型 |
| R3 取消竞态 | **部分修复，P1 未关闭** | 绑定前取消已保留 Gateway 映射并补发取消；终态仍可被持久化上下文中的旧对象覆盖 |
| R4 mock 场景 ID | **未修复** | runtime.ts 第 117、120 行仍硬编码 demo-scene-1；真实 Java 对白链路再次失败 |
| R5 草稿内容校验 | **部分修复** | 对白增加补丁校验；报告类非法正文仍能保存和采纳 |
| R6 本地终态通知及关流 | **未修复** | failure、排队取消等仍只改数据库；发送正常终态后仍不 complete |
| R7 终态事件 envelope | **未修复** | 只改事件名，payload.type / data.status / data.code 仍可能与数据库终态矛盾 |
| R8 targetSceneId 幂等 | **未修复** | 新合法补丁探针仍复现：改变目标场景但复用 key，返回第一次的 draftId |
| R9 当前请求被截断 | **未修复** | AgentTranscriptService 第 44 行仍将当前请求和历史消息一并截到 1,400 字符 |

### 仍需优先修复：取消接口先加载旧对象，再加锁

**P1**，位置：[AgentRunService.java:192](D:/desktop/screenplay-agent-backend/src/main/java/com/urke/saasbackendstarter/screenplay/service/AgentRunService.java:192)、[AgentRunWorker.java:94](D:/desktop/screenplay-agent-backend/src/main/java/com/urke/saasbackendstarter/screenplay/service/AgentRunWorker.java:94)。

本轮给 worker 和 terminal 加上悲观锁是正确方向，但 `cancel()` 在调用 worker 前，已经通过普通查询加载了 parent 和 children。worker 使用 REQUIRED 加入同一个事务；之后的锁查询没有刷新已经管理的旧对象。

本轮复现直接调用真实 HTTP `/agent/runs/{id}/cancel`。临时测试在归属查询完成、worker 锁查询开始之前，用独立事务提交 `recordTerminal()`，固定触发以下顺序：

```text
取消事务：ownedRun() 读取 RUNNING
完成事务：recordTerminal() 提交 COMPLETED
取消事务：findLockedById() 取得行锁，但实体仍保留旧 RUNNING
取消事务：写入 CANCELLING 并提交
```

实际输出：

```text
REVIEW_DB_STATE_AFTER_TERMINAL=COMPLETED
REVIEW_CANCEL_API_RESULT=cancelling
REVIEW_DB_STATE_AFTER_CANCEL=CANCELLING
```

因此新增三个基于 Mockito 同一对象、顺序调用的状态测试，不能证明真实 JPA 的并发安全。任务可能停在 CANCELLING，继续阻止同一 session 提交新请求。

修复建议：在首次加载受保护 run 时就取得锁并校验归属；children 也需要一致策略。另一种选择是取得锁后显式刷新对象，或用前置状态约束的原子更新，并正确处理更新失败。仅在第二次查询上加 `@Lock` 不足以关闭本问题。

验收必须使用真实 PostgreSQL 和不同事务，复现“先读取、另一事务提交终态、再请求取消”，确认终态不能退回运行态，同时验证父任务和子任务。

### 对白修复后的两个边界

**P2**，位置：[AgentDraftAcceptanceService.java:154](D:/desktop/screenplay-agent-backend/src/main/java/com/urke/saasbackendstarter/screenplay/service/AgentDraftAcceptanceService.java:154)。

普通局部替换与重复场景定位已经通过，旧“直接用对白替换整场”的报告不再适用于当前实现。但当前允许：

```json
{"original":"Old line.\n","proposed":"New line.","sourceOffset":38}
```

若目标场景后还有 `EXT. STREET - DAY`，合并结果出现 `New line.EXT. STREET - DAY`。该输入满足当前的非空、偏移、原文匹配校验。真实 HTTP 保存后的新版本重新解析，得到：

```text
REVIEW_DUPLICATE_SCENE_PATCH_EXACT=true
REVIEW_SCENE_BOUNDARY_LOST=true
REVIEW_SCENE_COUNT_BEFORE_AFTER=3/2
```

应保护非末场的分隔换行，或者将其排除在对白补丁可修改范围之外；应用后验证非目标场景的结构边界保持不变。

另外，`proposed` 含 CRLF 时，第 114 行直接保存，但 parser 下次分析会规范成 LF。隔离真实 parser 探针确认，保存原文与重新生成的 scene.rawText 将不一致，后续采纳在第 104–105 行匹配检查处失败。应和普通剧本创建路径一致，统一存储为 LF，并确保偏移基于同一份规范化文本。

### 下一步验收顺序

1. 先修上述真实 JPA 旧对象回写问题，补真实事务测试。
2. 补对白末尾换行、CRLF 用例；修复真实 Java 场景 ID 适配，使对白链路完成读取、保存和采纳。
3. 逐项关闭 R5–R9，不能用 smoke 总体通过替代这些明确的负面用例。
4. 完成后再做前端完整创作闭环和多 Agent 扩展。本轮已经通过的 R2 和普通对白补丁用例不需要重新设计。

---

## 第二轮复审记录（历史，状态以最新复核为准）

结论：本轮修复有效，但尚不能判定上一轮问题全部关闭。旧库迁移、SSE 异步鉴权、线程池隔离、会话提交串行化、草稿审核锁等已有修复；对白应用、SSE 回放、取消状态转换仍存在需要优先修复的缺陷。

审核对象为 `D:\desktop\screenplay-agent-backend` 当前工作区，相对 `df8de0a` 的修复及相关调用链。没有修改业务代码，没有访问业务数据库，没有调用真实收费模型。本文件是复审结果，不是上线验收证明。

## 1. 实际验证结果

| 验证 | 结果 | 证明范围 |
| --- | --- | --- |
| Java 全套 `mvnw -Dapi.version=1.44 -B test`，JDK 21 | **92 passed，0 failed，0 error，0 skipped** | 包含新增旧表迁移测试；不代表 Agent 全链路及所有竞态已覆盖 |
| Gateway `npm run check` | 通过 | TypeScript 类型检查 |
| Gateway `npm run smoke` | 通过 | 内置模拟 Java 适配器下的真实 Pi 循环、专业任务、SSE、鉴权等 |
| 临时 PostgreSQL 16 + 真实 Spring HTTP + Node `AGENT_MODE=mock / JAVA_MODE=http` | 6 类任务中 5 类完成 | 用户鉴权、任务派发、真实 Java 回调、草稿落库；无真实 LLM |
| 带旧数据的数据库重启升级 | 通过 | 删除临时库 `content_revision` 后，应用以 `ddl-auto=update` 启动，脚本成功回填并恢复该列 |
| SSE 真实 HTTP | HTTP 200；显式完成后正常 EOF | 上轮异步重新分派鉴权故障未再出现；但应用没有在正常终态后自动关闭流 |
| SSE 回放竞争探针 | **复现漏事件** | 先广播序号 3，再执行历史 1、2 的回放，客户端仅获得 3 |
| 对白应用探针 | **复现场景正文丢失和边界损坏** | 使用当前真实 parser 与当前合并逻辑，不依赖 LLM |

真实 Java 联调的任务结果：

| taskType | 结果 |
| --- | --- |
| general | completed |
| analyze_scene | completed |
| rewrite_dialogue | **failed / gateway_failed** |
| extract_characters | completed |
| build_outline | completed |
| check_plot_logic | completed，未写草稿 |

临时数据库共保存 4 个草稿。这里的 completed 证明执行与接口链路成功，不能证明模拟内容的业务质量或引用真实性。

本轮日志保留于临时目录：`screenplay-agent-rereview-maven.log`、`pi-agent-rereview-integration.log`、`pi-agent-rereview-node.log`。真实联调使用独立随机端口、独立 Testcontainers 数据库；测试进程及容器已结束。

## 2. 已确认的修复

| 上轮问题 | 当前结果 |
| --- | --- |
| 老表直接新增 NOT NULL content_revision 失败 | `schema.sql` 先加列、回填，再加默认值和非空约束；实际应用重启升级通过 |
| SSE 完成时 ASYNC 再鉴权失败 | 限定 ASYNC + events 路径放行；初始请求仍鉴权并检查 run 归属；实际 HTTP 完成通过 |
| 浏览器长连接与取消争用同一线程池 | Gateway 消费、浏览器回放、控制请求已拆分执行器，浏览器改本地广播 |
| 同 session 不同 requestId 可以同时提交 | 改为 session 级事务 advisory lock；代码路径已核对 |
| 恢复时旧租约遗留、lease-busy 任务搁置 | Recovery 释放租约，暂时获取不到租约时 outbox 保持可重试；代码路径已核对 |
| 数据库终态、事件、最终消息分离提交 | `recordTerminal()` 统一事务；仍有并发覆盖与事件内容问题，见后文 |
| 同一草稿重复采纳或采纳/拒绝竞争 | 两条路径共用悲观写锁，并在事务内判断状态；代码路径已核对 |
| 报告/大纲/人物提案被当作完整剧本保存 | 此类草稿采纳已不再创建 ScriptVersion，返回 artifactType |
| check_plot_logic 意外写草稿 | Node mock 去掉保存步骤，Java 保存端也明确拒绝该任务写草稿 |
| 专业任务 mock 最终回复必然 schema 失败 | 已按任务输出 JSON，并增加基础类型检查；真实 Java 对白分支仍失败 |

“代码路径已核对”不等于新增并发回归测试已经覆盖。当前新增 Java 测试主要验证迁移；下面缺陷需要专门的回归场景。

## 3. 剩余缺陷与修复建议

### R1 · P1：对白草稿被当作完整场景替换，破坏新版本正文

位置：[AgentDraftAcceptanceService.java:104](../src/main/java/com/urke/saasbackendstarter/screenplay/service/AgentDraftAcceptanceService.java#L104)、[profiles.ts:22](../examples/pi-agent-gateway/src/profiles.ts#L22)。

profile 要求保存 `proposed` 对白，采纳却将整个 `scene.rawText` 替换成该文本。以下原文第一场的草稿为 `ALICE: Keep the letter.`：

```text
INT. STATION - NIGHT
He hides a letter.
ALICE
Give it back.
EXT. STREET - DAY
A bus arrives.
```

当前合并后变成：

```text
ALICE: Keep the letter.EXT. STREET - DAY
A bus arrives.
```

第一场的标题与动作被删除，第二场标题黏连在对白后；当前真实 parser 将其识别为 `UNSEGMENTED SCRIPT`。原版本仍保留，但用户采纳得到的新版本内容不正确。

同一方法第 100 行用 `original.indexOf(scene.rawText)` 定位。如果两个场景文本相同，选择第二场也会修改第一次出现的位置；探针中正确位置为 60，实际返回 0。

修复：统一产物契约。若业务是对白改写，保存带源位置和原文校验的对白 patch，保留场景标题、动作与分隔符；若业务是完整场景替换，应明确修改 profile、工具说明、mock 和服务端校验。使用解析时的 sourceStart/sourceEnd 或可靠的场景位置映射，不能以第一次字符串匹配替代场景身份。

验收：对白采纳前后标题、动作、非目标场景保持一致；两个完全相同的场景中，只改变选中的一个；重复采纳仍返回同一版本。

### R2 · P1：SSE 实时广播可能抢先历史回放，造成永久漏事件

位置：[AgentEventBroadcaster.java:38](../src/main/java/com/urke/saasbackendstarter/screenplay/service/AgentEventBroadcaster.java#L38)、第 75 行。

订阅注册后才异步执行 `replay(events.after(...))`。实时 `send()` 可以先执行并将 `lastSent` 更新为 3；随后历史事件 1、2 都会被 `sequence <= lastSent` 丢弃。`synchronized` 只能使两个方法互斥，不能保证回放先执行。

已用可控执行器确定性复现：暂停 replay，广播 3，再执行历史 1、2 的回放，输出只有 3。客户端若以 3 重连，也无法自动补回 1、2。

修复：订阅进入 replaying 状态时缓冲实时事件，完成历史回放后，按序号去重并排空缓冲，再切换实时发送。不能只在异步方法上加锁。

验收：在注册、查询历史、发送历史三个边界注入新事件，客户端仍得到完整有序序列；同时覆盖带 Last-Event-ID 的重连。

### R3 · P1：取消与派发、终态更新之间仍有状态竞争

位置：[AgentRunWorker.java:48](../src/main/java/com/urke/saasbackendstarter/screenplay/service/AgentRunWorker.java#L48)、第 85 行，以及 [AgentEventService.java:52](../src/main/java/com/urke/saasbackendstarter/screenplay/service/AgentEventService.java#L52)。本项是代码时序分析，未进行压力测试。

存在两条具体路径：

1. Node submit 返回前，Java 已接到 cancel 并进入 CANCELLING，但还没有 gatewayRunId 可发送取消。随后 `bindGateway()` 因状态不是 RUNNING 而直接返回，丢掉映射；Node 继续运行，仍可能调用写工具。
2. cancel 和 terminal 都通过普通查询读取 run，没有共享 run 锁或实体版本。取消先读 RUNNING、终态先提交 COMPLETED、取消随后提交时，可以把状态覆盖回 CANCELLING。之后没有新的 Node 终态，会话的 active-run 检查会持续拒绝下一轮。

修复：所有 run 状态转换使用同一行锁或带前置状态的原子 UPDATE/CAS。绑定 Gateway ID 时保留取消意图；绑定后发现 CANCELLING，应立即补发取消。终态不能退回运行态；取消后的新副作用调用应有明确限制。

验收：用 latch 固定“submit 未返回时取消”和“terminal/cancel 交错提交”顺序，验证 Node 收到取消、数据库最终状态稳定、会话可以继续提交。

### R4 · P2：对白 mock 使用演示场景 ID，真实 Java 联调失败

位置：[runtime.ts:115](../examples/pi-agent-gateway/src/runtime.ts#L115)。

`get_scene` 和 `save_draft.targetSceneId` 硬编码 `demo-scene-1`。Java 场景主键及路由参数是 Long，保存端也执行数字解析。现有 smoke 使用模拟 Java 适配器，因此无法发现此契约错误；真实 HTTP 联调已复现 `rewrite_dialogue=failed:gateway_failed`。

修复：先 list_scenes，从实际工具结果取得场景 ID，再 get_scene 和 save_draft。模拟 provider 应复用真实适配接口契约，不能依赖仅模拟数据库接受的 ID。

验收：六种任务均用 `JAVA_MODE=http` 和临时真实数据库执行；对白任务必须完成读取、草稿保存、采纳和正文校验。

### R5 · P2：最终回复通过 schema，不代表保存的草稿有效

位置：[tools.ts:232](../examples/pi-agent-gateway/src/tools.ts#L232)、[AgentDraftService.java:69](../src/main/java/com/urke/saasbackendstarter/screenplay/service/AgentDraftService.java#L69)。

`save_draft` 验证了来源版本和目标场景，却没有按 artifact 校验 content；专业 schema 只检查最后一条 assistant 回复。

隔离真实 Pi runtime 探针已确认：analyze_scene 保存 `NOT_JSON: invalid analysis artifact` 后返回 pending_review。随后输出合法 JSON，最终校验仍可通过；输出非法 JSON 时，虽然最终校验失败，之前保存的非法草稿也已经存在。Java 采纳端亦没有产物内容校验。

修复：保存前按 artifact 类型验证内容，服务端保留权威校验；将 run 判定完成时的结果绑定到实际保存的 draftId/内容版本。不能只依靠最后一句回复，也不能仅以拒绝 failed run 草稿替代内容校验。

验收：非法报告 JSON 不能进入可采纳状态；最终回复有效但草稿无效、最终回复与草稿内容不一致时，均不能宣称该产物已验证完成。

### R6 · P2：本地产生的终态没有事件，正常终态后流也不自动关闭

位置：[AgentRunWorker.java:75](../src/main/java/com/urke/saasbackendstarter/screenplay/service/AgentRunWorker.java#L75)、第 85 行及 [AgentEventBroadcaster.java:74](../src/main/java/com/urke/saasbackendstarter/screenplay/service/AgentEventBroadcaster.java#L74)。

Gateway 连接失败的 `failure()`、QUEUED 状态取消、恢复中断等路径只更新数据库，不保存/广播终态。只依赖 SSE 的前端无法获知结束。正常终态发送后也没有 `complete()`，只能等 150 秒超时或客户端主动关闭。

真实 HTTP 验证中，已完成任务的事件流在回放后仍保持打开；手动 complete 后可正常 EOF，进一步区分了生命周期问题和已经修好的鉴权问题。

修复：所有终态统一通过持久化事件和提交后通知路径，包括 Java 自己产生的失败、取消与中断；终态发送后关闭流。Java 自产事件与 Node 事件需要统一、无冲突的公共序号策略。

验收：Node 未启动、QUEUED 取消、执行超时、正常完成、重连已完成任务，都能收到且回放同一权威终态，并结束连接。

### R7 · P2：归一了 SSE event，却保留相矛盾的 JSON payload

位置：[AgentEventService.java:74](../src/main/java/com/urke/saasbackendstarter/screenplay/service/AgentEventService.java#L74)。

缺草稿时，Java 正确将状态和事件名改为 FAILED / run.failed，但复制的 Gateway envelope 仍可能包含 `type: run.completed`、`data.status: completed`、`data.code: ok`。按 SSE event 和按 JSON type 分发的客户端会得出不同结论。

修复：以 Java 权威结果重建公共事件 envelope，统一事件名、JSON type、data.status 和 code。验收必须覆盖“Gateway completed，但 Java 判定 draft_missing”的情况。

### R8 · P2：目标场景没有纳入 Node 草稿幂等缓存比较

位置：[tools.ts:246](../examples/pi-agent-gateway/src/tools.ts#L246)、第 265 行。

同一 toolCallId、同正文、同来源版本，先保存到场景 101，再读取场景 102 并请求保存到 102，Node 缓存直接返回场景 101 的 draftId。Java 已将 targetSceneId 加入哈希，但缓存命中绕过了 Java；隔离工具探针已复现。

修复：缓存并比较规范化后的 targetSceneId；重试时任何业务字段改变都必须冲突。验收分别覆盖缓存命中和实际 HTTP 两条路径。

### R9 · P2：本轮未修复当前用户请求被截到 1,400 字符的问题

位置：[AgentTranscriptService.java:44](../src/main/java/com/urke/saasbackendstarter/screenplay/service/AgentTranscriptService.java#L44)。

最近消息统一截取前 1,400 字符，其中包括本次用户输入。接口允许更长输入，但后面的约束或正文不会送给模型，也没有提示调用方发生截断。

修复：完整保留当前请求，预算优先用于当前输入与必要系统信息；历史消息才使用摘要或裁剪。验收用超过 1,400 字符、末尾带关键要求的请求，确认 Gateway 收到该要求。

## 4. 下一步执行顺序

| 顺序 | 工作 | 完成条件 |
| --- | --- | --- |
| 1 | 修 R1 的对白产物和应用契约 | 合法对白采纳不丢正文、不改错场景；重复采纳仍幂等 |
| 2 | 修 R2、R3、R6、R7 的事件与状态生命周期 | 固定时序回放/取消测试通过，所有结束路径有一致终态，连接正确关闭 |
| 3 | 修 R4、R5、R8、R9 的跨服务契约和输入保真 | 六类任务真实 Java 联调通过，草稿校验与幂等一致，当前请求不静默丢失 |
| 4 | 将本轮发现转成可重复回归测试 | 测试使用真实 PostgreSQL、真实 HTTP、安全的模拟 provider；每个已修缺陷有对应失败场景 |
| 5 | 做单 Agent 产品闭环验收 | 上传/解析→任务→流式展示→草稿审核→采纳新版本→再次提问，包含失败、取消、重连 |
| 6 | 再进行真实模型小规模验证与多 Agent 扩展 | 先确认配置、成本限额和输出质量，再接父子任务汇总与共享预算 |

全局建议：保持当前 Java 管业务数据与权限、Node 管 Pi 执行的架构。下一步工作重点是把单 Agent 的数据应用、状态、事件三者做一致，而不是增加更多 Agent 类型。

多 Agent 验收仍需单独补齐父任务状态约束、父子任务并发幂等与数量上限、结果等待/汇总、整体取消和共享预算。生产配置还应避免执行令牌密钥漏配时使用固定默认值。本轮没有验证多实例运行、真实模型输出质量或生产发布配置。
