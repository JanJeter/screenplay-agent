# Gateway R4 / R5 / R6 修复记录

日期：2026-10-06（Asia/Shanghai）  
范围：`examples/pi-agent-gateway`；未修改 Java、前端、冻结 schema 或 fixtures。

## R4：模型实际可见的结果 schema

- `save_storyboard_result.result` 不再是 `Type.Unknown()`。Gateway 以
  `modelStoryboardResultSchema` 注册正式
  `model-storyboard-result.schema.json` 的 generate/rewrite 判别联合：
  `mode`、4～8 个镜头或单个 `proposalShot`、全部镜头字段、镜头/运动枚举、
  字符串和时长上下限，以及 result 分支和镜头对象的
  `additionalProperties: false` 都在工具参数中。
- 运行时的 `validateStoryboardResult` 保留；它继续负责 JSON Schema 无法表达的
  “生成镜头数必须等于冻结目标数”、`sourceQuote` 必须属于冻结场景、总时长和
  rewrite 目标隔离。
- 证据不是内部 TypeBox 对象：`storyboard-contract.ts` 启动本地
  Anthropic-compatible HTTP endpoint，以实际 provider transport 发送
  `createStoryboardTools()` 注册的工具，并断言捕获请求中
  `save_storyboard_result.input_schema.properties.result.anyOf` 的两分支、所有
  required 字段、枚举分支、长度/时长范围和额外字段限制。未调用付费模型。

## R5：保存回执语义比较

- `hasValidStructuredResult()` 不再使用 `JSON.stringify()` 比较最终助手文本与
  成功保存工具的回执。
- 两边先按严格回执结构校验（外层、`resultRef`、ID、type、
  `artifactId === resultRef.id`、无额外字段），再比较
  `artifactId`、`resultRef.type`、`resultRef.id`、`resultRef.storyboardId`。
- 仍要求最新一条成功的 `save_storyboard_result` 工具结果；仅出现保存工具调用
  或任意 JSON 都不会把 run 标为成功。
- 回归覆盖外层和 `resultRef` 内部键重排成功，以及错误 artifact ID、type、
  storyboard ID、缺字段和额外字段被拒绝。

## R6：冻结上下文容量

- 已按 Java 提供的 `REPAIR_CONTRACT.md` 将
  `STORYBOARD_MAX_CONTEXT_BYTES` 统一为 262,144（256 KiB），针对完整紧凑
  Java context JSON 的 UTF-8 字节数，而非 `sceneText` 字符数。
- `get_storyboard_context` 对实际 Java HTTP response 以相同上限读取；读取前
  拒绝超限/非法 `Content-Length`，读取中累积原始字节数并在解析前拒绝超限。
  通用 Java adapter 的默认 64 KiB 上限保持给其他响应，避免成为分镜上下文的
  更小中间缓冲。
- 结构化 context 校验也使用 256 KiB；8,000 UTF-16 字符的产品限制未改变。
- 回归经生产 HTTP reader 覆盖：超过旧 16 KiB 的长中文 generate、含 NUL JSON
  转义的 generate，以及包含目标/前/后镜、超过旧 64 KiB 读取上限的 rewrite。
  三者均低于 256 KiB 并通过 context 语义校验与 HTTP 读取。

## 验证结果

在 `examples/pi-agent-gateway` 执行：

```text
npm run check             PASS
npm run storyboard:check  PASS
npm run smoke             PASS
git diff --check          PASS
```

`storyboard:check` 输出：`PASS: provider-visible result schema, semantic save
receipts, R6 Chinese/escaped/rewrite capacity, ...`。这些是 mock、schema 和本地
HTTP transport 回归，不代表真实模型质量通过。

## 未完成 / 联调边界

- 未调用付费 provider；SB-12 的真实模型质量、用量和时延验收仍待执行。
- Java 负责的入队前 256 KiB 拒绝、真实 internal endpoint 与错误码
  `STORYBOARD_CONTEXT_TOO_LARGE` 需在合并后的 Java HTTP 集成回归中复验。
- 本次没有修改 Java 独占的共享契约、schemas 或 fixtures。
