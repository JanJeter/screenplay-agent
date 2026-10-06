# 分镜工作台修复契约（R6）

状态：**冻结，待 Java 与 Gateway 同步实现**。日期：2026-10-06（Asia/Shanghai）。本文件补充并优先于旧版 `API_CONTRACT.md` 第 5 节中 `internal-storyboard-context` 的 16 KiB 表述；该旧值不再有效。

## R6：冻结上下文容量

1. `sceneText` 的产品限制保持不变：先把 CRLF/CR 规范化为 LF，再按 JavaScript/Java UTF-16 字符计数，长度为 1～8,000。
2. 预算对象是 **完整的内部上下文 JSON body**，不是单独的 `sceneText`。它是 `GET /internal/agent/runs/{runId}/storyboard-context` 的紧凑 UTF-8 JSON：生成模式含 `mode`、`targetShotCount`、`instructions`、`sourceSnapshot`；重做模式还含 `instruction`、`storyboardId`、`targetShotId`、`baseStoryboardRevision`、`targetShot`、`previousShot`、`nextShot`。字段顺序与 `internal-storyboard-context.schema.json`/现有样例一致，空相邻镜显式写为 `null`。
3. 统一上限为 **262,144 bytes（256 KiB）**。计算方式是上述精确 wire JSON 的 UTF-8 编码长度；实现不得用字符串 `length`、估算值或仅 `sceneText` 字节数代替。Java 必须生成将要返回的同一紧凑 JSON 后计数；Gateway 必须在解析前/后对同一 body 计数，且其结构化校验使用相同 256 KiB 值。
4. 容量依据：8,000 个中文字符的生成上下文约 28.3 KB；重做上下文还包含目标镜和两个相邻镜，三份都取各字段中文最大值时约 113.8 KB。JSON 控制字符转义可由每个字符 3 UTF-8 bytes 扩大至 6 个 ASCII escape bytes；按全部合同字段的最大长度计算仍低于 256 KiB。因此该值保留 8,000 LF 规范化字符的产品承诺，并覆盖中文、JSON 转义及三镜重做快照。
5. 传输要求：Java internal endpoint 必须输出不超过 256 KiB 的 `application/json; charset=utf-8` body（`Content-Length` 若提供，不得超过 262144）；Gateway 读取该 HTTP response 的最大 body/response 限制必须至少为 256 KiB。此值只适用于 Java→Gateway 冻结上下文，不改变浏览器生成/重做请求 16 KiB、Node→Java 结果 64 KiB 或编辑详情 128 KiB 限制。
6. 失败规则：若完整上下文超过 256 KiB，生成/重做端点在创建 `AgentRun`、outbox 项或派发动作前返回 `400 STORYBOARD_CONTEXT_TOO_LARGE`（标准错误体，不含 `currentRevision`）。不得截断、删减相邻镜或修改快照以凑入预算。相同冻结输入必须在 Java 和 Gateway 得到相同接受/拒绝结果。

## R1/R2 共同写入规则

- 保存与采纳在首次读取草稿时即取得同一草稿行的悲观写锁，然后才验证 `expectedRevision`；锁前读取的实体不能用于后续写入。
- 调序在同一事务及该锁内先写入不冲突的临时 `orderIndex` 并显式 flush，之后再写最终顺序；镜头 ID、内容和 `(storyboard_id, order_index)` 唯一性保持不变，成功操作只增加一次 revision。
