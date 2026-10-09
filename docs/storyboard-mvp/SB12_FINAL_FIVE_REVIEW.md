# SB-12 后五项续跑复验单

> **已归档：** 此复验单的授权已于 2026-10-08 使用，FinalFive 五项均已终态。现在不得再将本文件的 `APPROVED` 解释为新的提交许可；8 个业务 run 的上限已经耗尽。请改阅 [运行后技术复验](SB12_FINAL_FIVE_TECHNICAL_REVIEW_2026-10-08.md) 和 [总质量报告](SB12_REAL_MODEL_QUALITY_REPORT.md)。

复验范围：仅为 `01-8-B → 02-A → 02-B → 03-A → 03-B` 的受控真实运行入口。此文件不是人工质量评价，也不改变 `01-8-A` 的 failed 历史状态。

## D 复验清单

- [ ] 承接预算源是 `quality-runs/20261007/01-8-A/01-8-A.provider-ledger.json`，其中预算为 3 个业务 run、USD 0.007482360 已计、USD 0.992517640 余额、USD 0 未结预留；其 3 个本 run 请求与 `remaining-batch/batch-summary.json` 中跨进程累计 9 个 provider request、0 retry 一致。
- [ ] `01-8-A` 保持 failed；其已保存的八镜产物和费用没有被删除、覆盖或当作成功重跑。
- [ ] Gateway 的严格单一 JSON 代码块回执兼容修复已在当前源码通过离线 provider contract；续跑会启动含该源码的新 Gateway，而非复用未部署修复的旧 Gateway。
- [ ] 五项入口只含 `01-8-B、02-A、02-B、03-A、03-B`；新实例端口为 `15435/18083/13004`，与保留的旧环境隔离。
- [ ] 固定批次身份为 `SB12-FIXED-EIGHT-20261007`，其 [批次身份](quality-runs/SB12_FIXED_EIGHT_BATCH/batch-identity.json)和[持久状态](quality-runs/SB12_FIXED_EIGHT_BATCH/batch-execution-state.json)不按执行日期创建。启动器扫描整个 `quality-runs/` 历史、检查持久状态，并持有同目录的排他锁；任何后五项的 submitted/completed/failed/unknown 标记或历史提交文件都禁止重放完整五项，改变当日输出目录不能绕过。
- [ ] 历史 `run-sb12-01a-live.ps1` 与 `run-sb12-remaining-live.ps1` 的默认 `RemainingSeven` 已明确退役：前者不能创建新的 01-A 路径，后者不能从旧 01-A 账本重放 01-B/01-8-A。唯一可提交入口是本复验单授权后的 `run-sb12-final-five-live.ps1`；它先取得固定锁，再读取固定状态和完整历史。
- [ ] 若真实入口仅在 `fresh-startup` 准备阶段失败，`-ResumePreparedBatch -ResumeCheckOnly` 只能在固定锁、状态和全历史检查通过，且现场没有任何提交/accepted/Java run/provider ledger/后五项目录时通过；实际 `-ResumePreparedBatch` 保留原失败记录并重新做预检，绝不自动重发已提交项目。
- [ ] 预算仍为全批最多 8 个业务 run / USD 1.00，SDK 自动重试 0；每项先导出最终 provider ledger，再导出其余证据，任一失败、超时、预算或导出异常即停止。
- [ ] 真人逐镜评价、DeepSeek 实际账单/单请求对账仍待完成，不能因此宣布 SB-12 整体验收通过。

## 授权

在完成以上复验且仅在允许支付后，D 将下一行中的占位内容改为 `APPROVED`。启动器精确匹配该独立授权；未授权时不会创建服务或提交请求。

后五项继续执行授权：APPROVED
