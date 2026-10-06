# SB-02 fixture 清单

所有 JSON 使用 UTF-8、LF 和冻结 schema。`validate-fixtures.mjs` 的成功检查与负例拒绝检查是 SB-02 的可重复合同检查；它不连接 Java、Gateway 或模型。

| 文件 | 使用端 | 目的 |
| --- | --- | --- |
| `generation-request-6-shots.json` | Java / 前端 | 公共生成请求（6 镜头）。 |
| `internal-context-generate.json` | Java / Node | Java 冻结的生成上下文。 |
| `internal-context-rewrite.json` | Java / Node | 含目标镜、相邻镜和基准 revision 的重做上下文。 |
| `model-result-4-shots.json`、`model-result-6-shots.json` | Node / Java | 4、6 镜头模型正例。 |
| `model-result-8-shots.json` | Node / Java | 8 镜头最大数量和输出预算正例。 |
| `model-result-rewrite.json` | Node / Java | 单镜候选模型正例。 |
| `storyboard-detail-6-shots.json` | Java / 前端 | 保存后的独立分镜详情。 |
| `storyboard-list-response.json` | Java / 前端 | 分镜列表、分页及脚本筛选正例。 |
| `storyboard-save-request-6-shots.json` | Java / 前端 | 完整编辑保存请求；ID 集合必须与详情一致。 |
| `shot-proposal-pending.json` | Java / 前端 | 未采纳单镜提案。 |
| `internal-result-response-*.json` | Java / Node | Java 持久化确认后的 artifactId/resultRef 成功体。 |
| `run-accepted-*.json`、`run-*.json` | Java / 前端 | 创建任务 202、运行中、成功 resultRef、失败和取消状态。 |
| `error-*.json` | Java / Node / 前端 | revision、幂等、scope、取消后回调、上下文超限、未认证、跨组织隐藏资源的标准错误体。 |
| `invalid-*.json` | Java / Node | 必须拒绝：非法枚举、错误镜头数、缺 Prompt、越界引用。 |

前端：用 `run-queued`、`run-failed`、`run-cancelled`、`run-completed-*` 覆盖状态，不从模型文本构造详情。Java：测试 `sourceQuote`、ID 集合、revision、run 终态与 resultRef 约束。Node：把 `model-result-*` 作为工具保存前的合法/非法输入。
