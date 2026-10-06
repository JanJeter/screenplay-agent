# 单场景分镜工作台：任务级执行计划

日期：2026-10-06  
状态：规划已完成；下列 SB-01～SB-13 均待执行。  
目标代码目录：D:\desktop\screenplay-agent-backend  
适用角色：产品、Java、Node/Agent、前端、测试。角色可由同一个执行者承担。

## 1. 本轮交付目标

用户用自己的一场戏，生成一份分镜草稿，编辑镜头，重新生成其中一个镜头并确认采纳，最后保存、重新打开和导出。

完整验收路径：

> 登录 → 创建项目/选择已有项目 → 粘贴短剧本 → 解析 → 选择一场 → 生成分镜 → 编辑保存 → 单镜重做 → 比较并采纳 → 刷新 → 复制 Prompt / 导出 Markdown。

本轮完成的判断依据是用户完成上述流程，并认为结果可以用于下一步制作。

### 已有基础

- Java 已有登录鉴权、组织隔离、项目、剧本版本、场景解析与查询。
- 已有 Java → Node → Pi 执行链路、AgentRun、outbox、状态查询、取消、SSE 和草稿机制。
- 上轮独立检查为 101 项 Java 测试、Node check/smoke 通过；该记录不代表本计划的新功能已实现。
- 当前没有分镜/镜头业务模型；本仓库未发现分镜业务前端。
- 原产品定位见 [PRODUCT.md](D:/desktop/screenplay-agent-backend/docs/PRODUCT.md)。文档中“Pi 尚未接入”等阶段描述已经落后于代码，执行者以当前实现为准。

### 产品范围

| 本轮必须完成 | 后续迭代 |
| --- | --- |
| 带场景头的短剧本、单场景生成 | 长篇小说语义拆场、整部作品自动编排 |
| 用户选择 4～8 个镜头，默认 6 个 | 大规模镜头批处理 |
| 文字分镜、图像/视频 Prompt | 自动生成图片和视频 |
| 镜头字段编辑、上下移动顺序、保存 | 人工增加/删除镜头、复杂版本树 |
| 单镜重做、比较、采纳/放弃 | 多 Agent 专家协作 |
| Markdown 导出、Prompt 复制 | Excel/PDF 导出、多人协同编辑 |
| 已有项目/版本/分镜重新打开 | 计费系统、市场分析、完整人物资料库 |

首版输入约束建议：目标场景最多 8,000 字符，创作要求最多 1,000 字符。以规范化 LF 后的 Java/JS 字符串长度计数；超过上限明确提示缩小范围，不静默截断。这是本轮产品边界，不改变已有剧本上传接口的上限。

## 2. 任务总表与执行顺序

所有任务状态初始为“待开始”。表中依赖是正式联调/完成依赖；前端可以在契约冻结后使用 fixtures 并行开发。

| ID | 任务 | 主负责人 | 前置依赖 | 交付物 |
| --- | --- | --- | --- | --- |
| SB-01 | 确定参考样本与产品验收口径 | 产品 | 无 | 样本、理想分镜、功能边界 |
| SB-02 | 冻结 API、产物 schema 和测试 fixtures | 技术负责人，Java/Node/前端共同参与 | SB-01 | 契约文档、JSON Schema、正反例 |
| SB-03 | 分镜存储、来源快照、查询与编辑接口 | Java | SB-02 | 数据迁移、实体、查询/保存 API |
| SB-04 | 接入分镜任务、授权回调与结果引用 | Java | SB-03 | 生成 API、run 上下文、结果回调 |
| SB-05 | Pi 分镜与单镜重做能力 | Node/Agent | SB-02 | 两类 profile、工具、校验、mock |
| SB-06 | 前端工程与工作台框架 | 前端 | SB-01、SB-02 | 登录接入、路由、三栏页面、fixtures 模式 |
| SB-07 | 导入、选场景、生成与恢复任务 | 前端，Java/Node配合 | SB-04、SB-05、SB-06 | 首次生成闭环、状态/失败/取消体验 |
| SB-08 | 分镜编辑、排序与保存 | 前端，Java配合 | SB-03、SB-07 | 镜头编辑、未保存提醒、版本冲突处理 |
| SB-09 | 单镜提案、比较和采纳 | Java主责，Node/前端配合 | SB-04、SB-05、SB-08 | 提案 API、比较界面、定点替换 |
| SB-10 | Prompt 复制与 Markdown 导出 | Java/前端 | SB-03、SB-08 | 导出 API、下载与复制入口 |
| SB-11 | 自动化闭环与异常验收 | 测试，开发配合 | SB-07、SB-08、SB-09、SB-10 | 可重复的端到端测试与报告 |
| SB-12 | 真实模型质量验证 | 产品/Node | SB-04、SB-05 | 真实输出、人工评价、用量/时延记录 |
| SB-13 | 小范围试用与交接 | 产品，测试/开发配合 | SB-11、SB-12 | 试用记录、问题排序、运行说明 |

### 分批下发

1. **第一批：SB-01 → SB-02。** 先确定交付内容与接口，避免三端各自定义数据结构。
2. **第二批并行：Java 做 SB-03 → SB-04；Node 做 SB-05；前端做 SB-06。**
3. **第三批：SB-07 → SB-08。** Java+Node生成链路就绪后，产品/Node可同时开始 SB-12。
4. **第四批并行：SB-09 和 SB-10。** 两者完成后进入 SB-11。
5. **第五批：SB-13。** 使用已经通过技术与质量检查的同一版本试用。

工期由执行者结合团队人数和现有前端资源估算。本计划不假定角色都有独立人员，也不承诺固定日历交付日期。

## 3. 全部执行者必须共用的约定

### 3.1 分镜产物独立保存

新增业务产物为“分镜草稿”，它不修改 ScriptVersion.rawText，也不经过对白草稿的“采纳后创建剧本版本”逻辑。

建议新增三个对象：

| 对象 | 必须包含的信息 |
| --- | --- |
| StoryboardDraft | id、organizationId、projectId、createdBy、sourceRunId、来源快照、revision、创建/更新时间 |
| StoryboardShot | Java生成的稳定id、storyboardId、顺序、可编辑镜头字段 |
| StoryboardShotProposal | id、storyboardId、targetShotId、sourceRunId、baseStoryboardRevision、候选内容、PENDING/ACCEPTED/REJECTED状态 |

来源快照至少包含 scriptId、scriptRevision、sourceSceneId、sceneNo、heading、sceneText、sceneHash，文本统一 LF。

**重要实现事实：** 当前 AnalysisTransactions.replace() 会删除旧场景并重新插入。sourceSceneId 只能作为溯源值，不能作为唯一读取依据，也不能通过级联删除使旧分镜消失。生成、后续重做和导出均可使用创建时保存的快照。重新解析后，已有分镜仍须可用。

原剧本新建版本后，旧分镜继续明确显示原来源版本。迁移分镜到新版剧本属于后续功能。

### 3.2 镜头字段

| 字段 | 约定 |
| --- | --- |
| id | Java生成，模型不能指定；单镜采纳后不改变 |
| orderIndex | 保存后的0基顺序；页面镜号显示orderIndex + 1 |
| shotSize | ESTABLISHING / WIDE / MEDIUM / CLOSE_UP / EXTREME_CLOSE_UP |
| cameraMovement | STATIC / PAN / TILT / DOLLY_IN / DOLLY_OUT / TRACK / HANDHELD |
| visualDescription | 必填；描述画面、人物与动作 |
| dialogue | 可空；不能擅自改变原文关键对白 |
| sound | 可空；必要的环境声音/音效 |
| durationSeconds | 1～30的整数；页面标注“预计时长” |
| imagePrompt | 必填，可复制 |
| videoPrompt | 必填，可复制 |
| sourceQuote | 必填，必须是来源场景快照中真实存在的片段；用于核对，不向模型授予权限 |

第一版不单独增加机位、焦段等更多字段；必要信息先体现在画面或 Prompt 中。

Node 生成结果的 shots 数组表示镜头顺序，Java分配稳定ID和orderIndex。生成数量必须与本次请求的 targetShotCount 一致；编辑保存接口则必须携带已有镜头ID，且ID集合保持不变。

SB-02需要把字段长度、枚举、空值、JSON额外字段规则写入正式 schema，Java与Node共用同一套正反例。

### 3.3 保存、重做与版本

- 一份分镜共用一个 revision，初始为1；每次成功编辑保存或采纳提案后递增。
- 编辑请求带 expectedRevision。过期返回409和明确错误码；前端保留本地未保存内容，提示重新加载/比较，不能默默覆盖。
- 单镜重做先保存提案。用户点击采纳前，已保存镜头保持不变。
- 提案固定目标shotId和baseStoryboardRevision。生成期间用户修改了分镜，旧提案采纳返回409。
- 采纳只替换目标镜头字段；其他镜头、目标镜头ID、位置和原剧本文本均保持不变。
- 同一提案重复采纳不能再应用一次；重复放弃幂等；已采纳提案不能再放弃。
- 用户需要重新生成整场时，创建另一份分镜草稿，保留此前已保存版本。

### 3.4 复用现有任务机制

- 增加两个taskType：generate_storyboard、rewrite_storyboard_shot。
- 复用现有AgentRun/outbox/Gateway/取消/SSE，不另起任务执行框架。
- Java保存本次结构化输入：目标场景快照、目标数量、创作要求；单镜重做另带目标镜头、相邻镜头快照和baseStoryboardRevision。
- 结构化输入通过专用上下文读取接口传递，不拼进会被历史消息裁剪的文本来决定业务目标。
- 模型输出只有可编辑内容；组织、项目、run、来源版本、目标镜头由Java执行上下文绑定。
- 新profile只开放get_storyboard_context和save_storyboard_result；无通用文件、shell或子任务委派工具。
- Java和Node均校验产物；Java持久化返回artifactId后，才能将同一产物作为任务完成结果。
- 页面最终内容读取Java保存的产物。SSE用于进度和过程文字，不从流式文本拼装正式分镜。
- 取消与回调提交共用权威run状态/锁。取消生效后拒绝新结果提交；已保存的审核/审计数据不得导致页面把取消任务显示为成功。

### 3.5 接口合同草案

以下均为待实现接口。SB-02负责冻结字段，后续变更必须同时更新schema、fixtures和三端适配。

公共接口前缀：/api/v1/screenplay。新增接口中的业务ID均使用JSON字符串；Java实体可以保持现有Long/UUID类型。现有旧API返回值通过前端适配层统一，不要求全库改造。

| 方法与路径 | 输入/输出重点 |
| --- | --- |
| POST /projects/{projectId}/storyboards/generations | 输入scriptId、sceneId、targetShotCount、instructions、clientRequestId；202返回runId |
| GET /projects/{projectId}/storyboards | 分镜摘要列表；支持按scriptId筛选；分页 |
| GET /storyboards/{storyboardId} | 来源快照、revision、按序镜头、待审核提案摘要 |
| PUT /storyboards/{storyboardId} | expectedRevision、完整镜头数组；保存字段和顺序，返回新revision |
| POST /storyboards/{storyboardId}/shots/{shotId}/regenerations | instruction、expectedRevision、clientRequestId；202返回runId |
| GET /storyboards/{storyboardId}/shot-proposals/{proposalId} | 候选镜头、基准版本、状态 |
| POST /storyboards/{storyboardId}/shot-proposals/{proposalId}/accept | expectedRevision；返回当前分镜和新revision |
| POST /storyboards/{storyboardId}/shot-proposals/{proposalId}/reject | 返回提案状态 |
| GET /storyboards/{storyboardId}/export?format=markdown | 从已保存数据导出UTF-8 Markdown |

复用 GET /agent/runs/{runId}，增加可选 resultRef：{type: "storyboard" 或 "shot_proposal", id: "...", storyboardId: "..."}，旧任务仍可返回null。已有events和cancel路由继续使用。

新增内部回调：

| 方法与路径 | 用途 |
| --- | --- |
| GET /internal/agent/runs/{runId}/storyboard-context | 读取该run冻结的合法输入，含mode=generate/rewrite |
| POST /internal/agent/runs/{runId}/storyboard-result | 提交该run的分镜或单镜候选；类型、目标和归属由run确定 |

内部接口继续校验Java签发的执行令牌，并新增精确scope：storyboard:read、storyboard:create或shot-proposal:create。外部浏览器只调用Java。

新增生成/重做命令的clientRequestId幂等作用域固定为(organizationId, userId, clientRequestId)。taskType、目标ID、来源/分镜版本、数量和创作要求全部进入请求指纹，不进入幂等键的作用域。同一键与相同指纹返回原run；同一键的任一业务参数变化返回409；用户主动发起另一任务时使用新键。内部结果按run绑定幂等键，内容变化返回409。编辑保存用revision，提案采纳用proposalId和revision。

### 3.6 初始预算与界面状态

- 建议每个run最多6次工具调用，执行截止时间先沿用当前120秒；输出token与结果字节上限由SB-02/SB-05使用8镜头样例校准并配置。
- 现有2048输出token、文本草稿12000字符及HTTP请求体大小不能直接假定足够；必须核对模型、Node请求体、Java DTO、令牌有效期、租约和流式超时的兼容性。
- 输出截断、超时、非法结构不能保存成半份有效分镜，也不能显示完成。
- 页面只展示“排队中 / 正在读取场景 / 正在生成分镜 / 正在保存 / 已完成 / 失败 / 已取消”等用户可理解状态。
- 前端以Java run状态和resultRef为最终依据；SSE断开时可用受限轮询恢复状态，终态后停止订阅与轮询。支持刷新恢复当前run；不得永久转圈或把断线直接当作取消。
- 真正影响本轮用户操作的既有问题并入相应任务修复；本轮不安排一次全面架构重构。

## 4. 各任务工作单

### SB-01：确定参考样本与验收口径

**负责人：** 产品。**依赖：** 无。

工作内容：

- 准备3个短场景：双人对白、连续动作、无对白氛围；每个输入在本轮长度限制内。
- 选其中一个人工写出6镜头参考结果，明确剧情事实、动作顺序、人物关系及道具状态。
- 确认用户可编辑字段、核心按钮文案和本轮范围。

交付物：

- docs/storyboard-mvp/PRODUCT_ACCEPTANCE.md
- docs/storyboard-mvp/samples/ 下的3份输入、参考分镜和评价表。

验收：

- 执行者能够从样本直接判断“有用分镜”和“严重错误”，不只检查JSON。
- 固定公共样本包括“信未拆封；林舟藏信→放信→许晴收信”的状态变化，禁止擅自出现拆信读信或新增结局。
- 参考分镜作为质量参考，不要求模型逐字复现。

### SB-02：冻结三端契约

**负责人：** 技术负责人；Java、Node、前端共同核对。**依赖：** SB-01。

工作内容：

- 将第3节草案落实为具体DTO、JSON Schema、错误码和状态说明。
- 区分模型生成输出、Java持久化产物、编辑请求、单镜提案四种结构。
- 给出成功、运行中、失败、取消、冲突、越权的完整请求/响应样例。
- 明确预算和限制，验证最大8镜头正例能装入整个传输链路。

交付物：

- docs/storyboard-mvp/API_CONTRACT.md
- docs/storyboard-mvp/schemas/ 下的schema与fixtures/下的正反例。

验收：

- Java/Node/前端使用同一套fixtures，不各自发明字段。
- 至少覆盖4、6、8镜头、空对白、多行中文、非法枚举、错误镜头数、缺Prompt、越界引用、过期revision。
- 约定完成态能定位到已保存artifact；前端不需要解析模型自由文本。

### SB-03：分镜存储与基础接口

**负责人：** Java。**依赖：** SB-02。

工作内容：

- 新增StoryboardDraft、StoryboardShot、StoryboardShotProposal及对应repository/service。
- 保存不可变来源快照；来源场景ID不承担长期存活保证。
- 实现分镜列表、详情、编辑与排序保存；所有读写检查组织和项目归属。
- 增加可重复执行的数据迁移；兼容旧库和新库。沿用项目可用迁移机制，不仅依赖Hibernate给旧表直接加非空列。

建议文件位置：

- src/main/java/com/urke/saasbackendstarter/screenplay/domain/Storyboard*.java
- 同模块的repository、dto/storyboard、service、controller目录。
- src/test/java/com/urke/saasbackendstarter/screenplay/integration/ 下的分镜持久化测试。

验收：

- 保存、刷新、查询结果一致；过期revision返回409。
- 跨组织访问拒绝，其他组织的管理员也不能读取。
- 编辑和排序不会修改原剧本；已有shotId保持稳定。
- 重新解析来源剧本后，旧分镜仍可查询与导出所需数据。

### SB-04：Java分镜生成任务与回调

**负责人：** Java。**依赖：** SB-03。

工作内容：

- 实现生成命令，验证script/scene属于项目，冻结来源快照与创作参数。
- 扩展AgentTaskType、run结构化输入、GatewayDispatch和能力令牌。
- 实现内部context/result接口及结果幂等、双端schema对应校验。
- 复用outbox/worker，扩展resultRef；分镜任务完成条件是正确类型的产物已保存。
- 返回清晰失败/取消状态，供SB-07显示与恢复。

重点关联现有文件：

- AgentRunService、AgentRunWorker、AgentGatewayClient、AgentGatewayEventProjector、AgentEventService。
- AgentExecutionTokenService及内部授权组件。

验收：

- 相同生成请求重试只有一个run、一份分镜；不同载荷复用键返回409。
- 缺scope、跨run或跨项目回调拒绝。
- 非法结果不产生可用分镜；保存失败不能标记completed。
- 取消后回调拒绝新增产物；新任务没有结果时，前端可获得明确失败终态。
- 结构化目标与当前要求完整传递，不受历史消息裁剪影响。

### SB-05：Node/Pi两类分镜能力

**负责人：** Node/Agent。**依赖：** SB-02；真实联调依赖SB-04。

工作内容：

- 注册generate_storyboard和rewrite_storyboard_shot。
- 增加get_storyboard_context、save_storyboard_result专用工具，profile只开放必需工具。
- 新增独立schema模块；提交前校验、Java落库确认后返回artifactId。
- 根据真实读取内容制作mock；从最新成功的对应toolResult读取上下文，避免扫描用户消息/旧轮次对象。
- 添加profile预算配置；检查provider输出截断与任务超时。

建议文件：

- examples/pi-agent-gateway/src/profiles.ts、runtime.ts、tools.ts、server.ts
- 可新增storyboard-schema.ts、storyboard-tools.ts，避免继续把所有逻辑堆入同一个文件。
- examples/pi-agent-gateway/scripts/ 下的定向契约检查。

验收：

- check和原smoke继续通过；两个新profile的正反例通过。
- mock与live共用权限、schema、保存路径；mock能消费真实Java ID与场景。
- 8镜头输出不会因旧预算被截断；非法/截断输出不被确认完成。
- 最终回复引用实际artifact，不再生成另一份与保存内容不一致的“最终分镜”。
- 单镜输出没有修改目标ID或其他镜头的能力。

### SB-06：前端工程与工作台框架

**负责人：** 前端。**依赖：** SB-01、SB-02。

目录决策：如没有可复用的既有业务前端，本计划默认新建 apps/storyboard-web，使用React + TypeScript；与pi-agent上游包分开，使用独立依赖锁文件。如接入已有前端，先在任务记录里写明实际路径。

工作内容：

- 接入现有登录、退出和过期处理，建立只调用Java的API适配层。
- 路由：项目列表、项目剧本/版本入口、分镜工作台。
- 工作台左侧场景导航，中间镜头列表，右侧当前镜头编辑；原文可随时展开核对。
- 使用SB-02 fixtures开发加载、空白、失败、已有结果等页面状态。
- 窄屏改为场景/镜头/详情分步查看，主要操作支持键盘访问。

验收：

- 页面无需输入Gateway地址、模型API密钥、runId或内部JSON。
- 登录后可进入工作台；无数据时提供明确的创建/导入入口。
- fixtures模式与真实API适配可切换，不把模拟成功写死进生产流程。
- 不展示不存在的图片/视频生成功能；分镜卡片以文字信息为主。

### SB-07：导入到首次生成的前端闭环

**负责人：** 前端。**依赖：** SB-04、SB-05、SB-06。

工作内容：

- 复用已有项目、文本版本、解析和场景查询API。
- 支持粘贴文本、选择场景、选择4～8镜头、填写创作要求、点击生成。
- 展示任务状态、必要的工具进度和结果入口；支持停止、失败重试、刷新恢复。
- 使用能携带现有JWT的流式请求方式，不把Bearer token放入URL。
- 生成完成后通过resultRef加载分镜详情。

验收：

- 从空项目到看到一份保存成功的分镜，全程可在页面完成。
- 重复点击不会创建重复任务；重试语义区分“查询原任务”和“发起新任务”。
- 断网重连/刷新能恢复状态，完成/失败/取消后停止等待。
- 错误信息给用户下一步操作；已有分镜不会因新任务失败而消失。

### SB-08：编辑、排序与保存

**负责人：** 前端；Java配合。**依赖：** SB-03、SB-07。

工作内容：

- 编辑景别、运镜、画面、对白、声音、时长和两个Prompt。
- 上下移动镜头顺序；展示总预计时长。
- 显式保存、保存成功反馈、未保存离开提醒。
- 处理revision冲突；用户能区分本地未保存内容与服务端内容。

验收：

- 修改保存后刷新仍存在；顺序和镜号一致。
- 未保存内容不会被任务进度或新响应覆盖。
- 两个页面编辑同一分镜时，旧页面保存收到可理解的冲突提示。
- 原文和sourceQuote可查看；业务ID、来源绑定与归属不可编辑。

### SB-09：单镜重做与提案采纳

**负责人：** Java主责，Node/前端配合。**依赖：** SB-04、SB-05、SB-08。

工作内容：

- 实现单镜regenerations和proposal详情/accept/reject接口。
- 提交时冻结目标、分镜revision、相邻镜头及来源快照。
- Node生成单镜候选，Java只保存提案。
- 前端提供“原镜头/候选镜头”比较及采纳、放弃按钮。
- 有未保存编辑时，要求先保存或明确放弃，再提交重做。

验收：

- 生成成功后原镜头仍未改变。
- 采纳只改变指定镜头；其他镜头逐字段不变，原shotId和顺序不变。
- 过期提案不能覆盖新编辑；重复采纳幂等。
- 放弃候选、生成失败或取消均保留原分镜。
- 重解析来源剧本后仍能基于来源快照重做。

### SB-10：复制与导出

**负责人：** Java/前端。**依赖：** SB-03、SB-08；可与SB-09并行。

工作内容：

- Java从已保存数据生成Markdown分镜表，包含项目、来源版本/场景、镜号、景别、运镜、画面、对白/声音、时长和Prompt。
- 前端支持下载文件、复制单镜图像/视频Prompt。
- 导出前若有本地未保存内容，提供“保存后导出”或明确提示导出当前已保存版本。
- 处理中文、换行、竖线和Markdown特殊字符。

验收：

- 导出与当前已保存分镜一致，包含已采纳的局部重做。
- 导出不会调用模型；浏览器刷新和来源重解析后仍能导出。
- 可直接打开下载的UTF-8文件，无乱码和错误的表格列。

### SB-11：自动化闭环与异常验收

**负责人：** 测试；Java/Node/前端配合。**依赖：** SB-07～SB-10。

交付物：

- 仓库内可重复运行的Java/PostgreSQL、Java↔Node HTTP、浏览器E2E用例。
- docs/storyboard-mvp/ACCEPTANCE_REPORT.md，记录版本、命令、结果、未覆盖范围。

必须覆盖：

- 完整正常路径与刷新恢复。
- 原剧本、非目标镜头不被修改。
- 不同组织隔离；回调scope与run绑定。
- 重复提交、重复回调、重复采纳。
- 编辑与采纳冲突；取消与完成的真实数据库竞争。
- Node不可用、模型失败、超时、无效JSON、镜头数不符、Prompt缺失。
- 来源剧本重新解析后旧分镜仍可查看、重做、导出。
- 中文/空对白/换行/特殊字符。

验收：

- 有真实HTTP和真实PostgreSQL证据；不能仅使用Mockito证明数据库竞争正确。
- 固定mock负责流程与异常测试，真实模型质量归SB-12。
- 旧有测试继续通过；新增检查能发现对应负面用例。

### SB-12：真实模型质量与预算校准

**负责人：** 产品/Node。**依赖：** SB-04、SB-05；可在前端开发期间并行。

工作内容：

- 使用SB-01的3个固定场景，每个至少生成2次，记录模型和提示词版本。
- 使用执行环境已配置的真实provider；执行前明确本批调用数量和费用上限，达到上限停止。
- 保存输入快照、请求数量、实际输出、用量、总耗时和人工评价。
- 验证镜头数、剧情忠实度、动作/道具连续性、镜头可拍性、Prompt与画面一致性。

建议人工量表：

| 维度 | 检查点 |
| --- | --- |
| 剧情忠实度 | 是否擅自改变关系、结局、关键对白或未拆封等事实 |
| 镜头可用性 | 用户能否明确知道这一镜拍什么 |
| 连续性 | 人物位置、道具持有者、动作前后是否连贯 |
| Prompt一致性 | Prompt是否与这一镜的角色、动作、场景一致 |
| 修改负担 | 直接用 / 小改可用 / 需要重做 |

验收：

- 结构和保存路径全部有效；严重剧情错误有记录并优先修正。
- 三个样本中，多数镜头经人工判断为“直接用/小改可用”；该标准是试用门槛，不代表统计意义上的市场验证。
- 8镜头请求可在约定预算内完整产生；时延与用量有真实基线。
- 质量不足时优先调整任务约束、上下文和提示词，重新跑同一批样本作比较。

### SB-13：用户试用与交接

**负责人：** 产品；测试/开发配合。**依赖：** SB-11、SB-12。

工作内容：

- 由产品负责人邀请3～5位目标用户，各用自己的一个场景完成流程。
- 观察使用过程，不代替用户操作；单独记录模型等待时间和用户操作时间。
- 记录直接用/小改/重做的镜头比例、实际复制/导出、再次使用意愿及原因。
- 汇总最影响完成任务的前三个问题，确定下一轮优先级。
- 补运行说明：启动、配置项名称、mock/live切换、测试命令、演示步骤；文档不写真实密钥。

交付物：

- docs/storyboard-mvp/PILOT_REPORT.md
- docs/storyboard-mvp/RUNBOOK.md
- 更新PRODUCT.md中已经完成的阶段描述。

验收：

- 至少3位用户可无需开发者协助完成输入到导出。
- 用户有实际复制/导出行为，并能说明用于哪一步制作。
- 无须Swagger、手改数据库或重启服务才能完成正常流程。
- 下一轮需求来自记录到的问题；本轮没有证据的能力扩展不自动升级为必做。

## 5. 工作目录建议

下列为执行期间新增文件的位置建议，当前计划不创建这些实现：

~~~
D:\desktop\screenplay-agent-backend\
  apps\
    storyboard-web\
      src\
        api\                  # Java API、鉴权、run状态与SSE
        features\
          projects\
          screenplay\
          storyboard\
            components\
            pages\
        test\
  examples\
    pi-agent-gateway\
      src\
        profiles.ts
        runtime.ts
        storyboard-schema.ts
        storyboard-tools.ts
  src\main\java\com\urke\saasbackendstarter\screenplay\
    domain\
    dto\storyboard\
    repository\
    service\
    controller\
  docs\
    storyboard-mvp\
      PRODUCT_ACCEPTANCE.md
      API_CONTRACT.md
      schemas\
      fixtures\
      samples\
      ACCEPTANCE_REPORT.md
      PILOT_REPORT.md
      RUNBOOK.md
~~~

## 6. 下发给执行者的通用任务提示词

复制下文，将任务ID替换为实际编号：

~~~text
项目目录：D:\desktop\screenplay-agent-backend。
请先阅读 docs/STORYBOARD_MVP_EXECUTION_PLAN.md 和本目录适用的 AGENTS.md。
本次只执行 SB-XX，并读取它依赖任务的交付物。

目标是完成该任务列出的代码/文档/测试及验收，不只输出建议。
遵守计划中的统一schema、来源快照、revision、结果引用和权限约定。
当前工作区已有未提交修改，保留这些修改，以实际代码为基础增量实现。
不要重新实现已有AgentRun/鉴权/项目/解析基础设施，不扩展到本轮范围外。

依赖未完成时，可以完成fixture、接口或独立模块，明确标记待联调；
不要把“mock可运行”写成“真实模型质量已验收”，也不要把整个任务提前标为完成。
如发现必须变更公共契约，记录具体差异和受影响任务，先与契约负责人统一。

完成后提供：
1. 交付了什么以及修改文件；
2. 实际运行的检查与结果；
3. 本任务验收项逐条状态；
4. 尚未完成或未验证的部分；
5. 下一任务所需的接口、fixtures和使用方式。
不要输出真实密钥。
~~~

### 并行协作规则

- 一名负责人维护SB-02的公共契约；每个共享文件同一时间由一条任务线负责修改。
- Java负责领域/DTO/API/持久化，Node负责Gateway执行，前端负责apps/storyboard-web。
- SB-09是跨端任务：按已冻结契约分别提交提案API、profile适配、比较界面，再合并验收。
- 对同一工作区并行修改时，先约定文件边界；集成负责人负责解决接口差异，不能通过放宽校验掩盖不一致。

## 7. 本轮产品交付清单

- [ ] 新用户能在页面中创建项目、导入和选场景。
- [ ] 能使用真实模型生成一份具有来源信息的分镜。
- [ ] 能编辑、排序、保存、刷新和重新打开。
- [ ] 能对单镜生成候选、比较、采纳或放弃。
- [ ] 原剧本和其他镜头保持不变。
- [ ] 来源重新解析不破坏已有分镜。
- [ ] 能复制Prompt和导出已保存的Markdown分镜表。
- [ ] 失败、取消、断线、版本冲突都有明确处理。
- [ ] 固定mock集成检查与真实模型人工质量报告分别齐全。
- [ ] 3～5位目标用户试用完成，并形成下一轮问题排序。

第一个交付关口是SB-01、SB-02；随后按第二批开始三端并行。
