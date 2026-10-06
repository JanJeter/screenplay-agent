# 分镜工作台 Java 修复记录

日期：2026-10-06（Asia/Shanghai）  
范围：R1、R2、R6 的 Java 实现、真实 PostgreSQL 回归及共享契约。SB-12 与 SB-13 **没有完成，仍分别保持 BLOCKED / 待试用**。

## 已修复

- **R1 并发保存/采纳：** `StoryboardDraftRepository` 新增首次读取即 `PESSIMISTIC_WRITE` 锁定的查询。`save` 和 `acceptProposal` 都在取得这把锁后才读取和比较 `expectedRevision`，不会再在锁前保留旧实体。过期请求返回既有的 `409 STORYBOARD_REVISION_CONFLICT` 和当前 revision。
- **R2 调序：** 保存先对受锁保护的现有镜头写入唯一的负临时序号，显式 `flush`，再写最终序号；操作仍在同一事务中，镜头 ID/内容和顺序唯一约束保留，成功保存只递增一次 revision。
- **R6 上下文：** 以控制器实际返回的紧凑 JSON 为唯一预算对象。Java 在生成/重做创建 `AgentRun`、outbox 或派发前，用同一个 `wireJson` 序列化并对 UTF-8 bytes 预检。超过 256 KiB 返回 `400 STORYBOARD_CONTEXT_TOO_LARGE`，不截断也不创建任务。内部 context endpoint 返回该紧凑 JSON，避免全局 pretty-print 设置与预检不一致。

## 共享契约与交接

- `REPAIR_CONTRACT.md` 冻结上下文上限为 **262,144 bytes / 256 KiB**：完整 compact JSON，重做计入目标镜及前后相邻镜；保留 `sceneText` 最多 8,000 个 LF 规范化字符。它记录中文、JSON escape 的容量依据和 HTTP response 至少 256 KiB 的要求。
- `API_CONTRACT.md`、`internal-storyboard-context.schema.json`、fixtures 预算校验和 `error-context-too-large.json` 已同步。
- Gateway/Node 交接：把 `STORYBOARD_MAX_CONTEXT_BYTES` 改为 `256 * 1024`，对完整 `JSON.stringify(context)` 计数，并让 Java context HTTP response 的读取上限至少为 256 KiB；不得仅提高局部常量或截断字段。浏览器请求 16 KiB、模型结果 64 KiB、编辑详情 128 KiB 保持不变。

## 回归与结果

真实 PostgreSQL/Testcontainers 的 `StoryboardIntegrationTest`：**7 tests，0 failures，0 errors**。新增覆盖：

- 两份不同 PUT 使用同 revision、同步起跑且在独立 HTTP/事务线程中并发：恰好 `200` 与 `409` 各一；
- 保存与采纳并发：恰好一个成功，另一方 `409`，revision 只增加一次；
- 上移、下移、完整反转后重新读取：ID/内容稳定、顺序连续、每次只增加一次 revision；
- 长中文生成与重做在 256 KiB 内接受；超过上限的冻结快照返回 `STORYBOARD_CONTEXT_TOO_LARGE`，`AgentRun` 与 outbox 计数不变。

验证命令（JDK 21.0.9）：

- `node docs/storyboard-mvp/fixtures/validate-fixtures.mjs`：通过。
- `mvnw.cmd -Dapi.version=1.44 -Dtest=StoryboardIntegrationTest test`：通过。
- `mvnw.cmd -Dapi.version=1.44 test`：**108 tests，0 failures，0 errors，0 skipped**。
- `git diff --check`：通过。

## 未完成事项

- Node/Gateway 必须按上述冻结 R6 契约完成 256 KiB 全文上下文校验和 HTTP response 限制同步；本 Java 变更不修改 Gateway。
- SB-12 仍缺真实 provider 的质量、用量、时延证据；SB-13 仍缺真实用户试用记录，均未在本次标记完成。
