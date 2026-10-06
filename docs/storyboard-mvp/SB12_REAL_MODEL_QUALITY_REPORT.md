# SB-12 真实模型质量与预算校准记录

状态：**BLOCKED — 未执行任何真实 provider 调用**  
记录日期：2026-10-06（Asia/Shanghai）  
执行目录：`D:\desktop\screenplay-agent-backend`

本记录只接受真实 provider 的调用结果作为质量证据。faux/mock、fixture 和契约检查不得填入下方的质量结果表。

## 本批次执行边界

- 最多 **8 个业务生成 run**；达到 run 数或费用上限立即停止。一个 run 不是一个 provider 请求：一次 agent 执行可产生零个或多个 provider 请求，SDK/transport 重试也必须单独记录，不能把 8 个 run 写成 8 次 provider 调用。
- 费用上限：**USD 1.00**（调用前确认所选模型的计费信息；每个 provider 响应/重试轮次后按 provider 返回的原始 usage 和该模型费率累计）。当前 Gateway 没有按美元自动截停功能，因此真实执行时由执行者在每轮后核对并人工停止；不得以 mock usage 代替。本文不自行增加 provider 请求额度或费用额度。
- 计划业务 run：样本 01 的 6 镜头两次、8 镜头两次；样本 02 的 6 镜头两次；样本 03 的 4 镜头两次。这样既满足三个 SB-01 样本各至少两次，也单独验证 8 镜头输出完整性。
- 每个业务 run 必须经 `generate_storyboard` profile、`get_storyboard_context` 和 `save_storyboard_result` 的真实保存路径完成；结果页以 Java 保存的 `resultRef`/`artifactId` 为准。不能用自由文本或 mock 保存作为替代。

## 已保存的输入与提示词快照

| 用途 | 固定来源 | SHA-256 |
| --- | --- | --- |
| 样本 01：未拆的信 | `samples/01-unopened-letter-dialogue.md` | `BE76B378C65FFA98AAFC064A65257ACD99D3BDE149C8FD55CB4F790B1462A435` |
| 样本 02：停电楼梯 | `samples/02-blackout-stairwell-action.md` | `EB3027446E97216393490A9AE6794F3F190497B8A7CF5257437F65F6E0DF612F` |
| 样本 03：清晨站台 | `samples/03-dawn-platform-atmosphere.md` | `9AB678B1F4930AAB6DC83C13E269B428F933B87808C9CD43EB013660F697A622` |
| 系统提示词实现 | `examples/pi-agent-gateway/src/runtime.ts` | `2EEFE4100C4EEFF18A9BC99D3257BC02D18B0AAAAF4985D1C3B2ECA935085D87` |
| 分镜 profile 与输出约束 | `examples/pi-agent-gateway/src/profiles.ts` | `BA063F85FB517AA990770AD3E999B641E138BB245EC62F23E55502DA1AE57DB6` |

运行时必须在下表同时写入 provider、确切 `PI_MODEL`、`AGENT_MODE=live`、上述两个代码快照（或新的 hash）、输入快照、完整保存产物、每一轮原始 usage 事件、provider 请求数、重试数、开始/结束时间和 wall-clock 耗时。密钥、执行令牌和 Authorization 头不得写入仓库。

## Provider 预检（实际结果）

2026-10-06 在执行 Gateway 的进程环境中进行仅存在性检查，结果如下：

| 配置 | 结果 | 影响 |
| --- | --- | --- |
| `AGENT_MODE` | absent | Gateway 默认使用 faux/mock，不能作为真实质量运行。 |
| `ANTHROPIC_API_KEY` / `ANTHROPIC_AUTH_TOKEN` | absent | `anthropicProvider()` 没有可调用的真实凭据。 |
| `PI_MODEL` | absent | 虽有代码默认模型名，但没有可审计的本批确切模型配置。 |
| `ANTHROPIC_BASE_URL` / `ANTHROPIC_API_URL` | absent | 未配置受控 endpoint。 |
| `OPENAI_API_KEY` / `OPENAI_BASE_URL` | absent | 当前 Gateway runtime 也未注册 OpenAI provider。 |
| 本地 `.env*` | 只发现 `apps/storyboard-web/.env.example` | 未发现 Gateway live 凭据文件。 |

阻塞依据：`examples/pi-agent-gateway/src/runtime.ts` 的 live 分支只注册 `anthropicProvider()`；`examples/pi-agent-gateway/README.md` 明确其只读取当前进程环境变量且不读取 Pi 的用户 OAuth 配置。因此启动当前环境只能走 mock，违反 SB-12 的真实模型要求。为避免费用意外和错误证据，本次没有启动 Gateway live 模式，也没有发出 provider 请求。

## 真实运行账本（待配置后填写）

| 业务 run | 输入/目标镜头数 | provider / model / prompt snapshot | `resultRef` / artifact | provider 请求数 / 重试数 / 每轮 usage | 耗时 | 8 镜完整性 | 评价与镜头分级 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 01-A | 样本 01 / 6 | BLOCKED | — | — | — | N/A | — |
| 01-B | 样本 01 / 6 | BLOCKED | — | — | — | N/A | — |
| 01-8-A | 样本 01 / 8 | BLOCKED | — | — | — | BLOCKED | — |
| 01-8-B | 样本 01 / 8 | BLOCKED | — | — | — | BLOCKED | — |
| 02-A | 样本 02 / 6 | BLOCKED | — | — | — | N/A | — |
| 02-B | 样本 02 / 6 | BLOCKED | — | — | — | N/A | — |
| 03-A | 样本 03 / 4 | BLOCKED | — | — | — | N/A | — |
| 03-B | 样本 03 / 4 | BLOCKED | — | — | — | N/A | — |

每一行的输出附件应保存为 UTF-8 JSON，包含 Java 持久化的分镜对象、每镜 `sourceQuote`、图像/视频 Prompt，以及按轮次记录的 provider 请求标识（可脱敏）、重试原因和原始 usage 事件；文件名建议为 `quality-runs/YYYYMMDD/<业务-run-编号>.json`。人工评价必须逐镜标为“直接用 / 小改可用 / 需要重做”，并明确写出剧情忠实度、镜头可用性、动作/道具连续性、Prompt 一致性，以及任何严重剧情错误。

特别核对：样本 01 的信始终未拆，动作必须为林舟藏信→放信→许晴收信；样本 02 的门始终锁着、陈默不碰门且文件最终回到蓝色文件夹；样本 03 不得出现对白、旁白或进站列车。8 镜头两次均须恰为 8 个保存镜头，所有镜头含两个 Prompt，不能截断或擅自改写这些事实。

## 已执行的不付费验证

```text
examples/pi-agent-gateway> npm run storyboard:check
PASS: storyboard 4/6/8, invalid/truncated output, Java numeric IDs, save failure, and rewrite target isolation
```

该结果证明 fixture/契约对 4、6、8 镜头和截断等负例有效；它**不**证明真实 provider 的输出质量、用量或时延，也没有填补上述真实运行账本。

## 解除阻塞与交接操作

1. 在运行 Gateway 的受控环境提供 `AGENT_MODE=live`、`ANTHROPIC_API_KEY`（或先实现并审核另一 provider）、固定的 `PI_MODEL`，不要将密钥写入 `.env` 或本报告。
2. 启动与该 Java 服务联调的 Gateway，确保 Java 的 SB-04 内部 context/result 回调已可用，并为每次运行保留来源快照、revision 和 `resultRef`。
3. 按上方 8 个业务 run 执行；超过 USD 1.00 或第 8 个业务 run 后停止。逐轮记录实际 provider 请求与重试，不预设或另增 provider 请求额度。若模型输出产生严重剧情错误，先调整任务约束、上下文或提示词，再从同一输入快照重新比较，并将新提示词 hash 另列。
4. 将真实输出、逐轮 usage、provider 请求/重试计数、耗时和逐镜人工量表写入 `quality-runs/`，更新本报告账本和通过结论。未完成前，SB-12 验收不得标记通过。
