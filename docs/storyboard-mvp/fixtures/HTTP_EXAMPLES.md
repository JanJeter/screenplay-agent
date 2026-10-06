# 冻结 HTTP 交互样例

所有路径在 `/api/v1/screenplay` 下，除内部路径外均须现有 Bearer token。文件内容是完整 JSON body；HTTP 状态和请求/响应配对如下。

| 情形 | 请求 | 响应 |
| --- | --- | --- |
| 生成成功受理 | `POST /projects/project_10/storyboards/generations` body `generation-request-6-shots.json` | 202 `run-accepted-generation.json`；再 GET `run-queued.json`，最终 `run-completed-storyboard.json`，并 GET `storyboard-detail-6-shots.json`。 |
| 查询列表 | `GET /projects/project_10/storyboards?scriptId=script_101&page=0&size=20` | 200 `storyboard-list-response.json`。 |
| 编辑保存 | `PUT /storyboards/sb_001` body `storyboard-save-request-6-shots.json` | 200 详情，revision 为 2（示例详情是保存前 revision 1）。 |
| 单镜重做受理 | `POST /storyboards/sb_001/shots/shot_005/regenerations` body 应符合 `shot-regeneration-request.schema.json` | 202 `run-accepted-rewrite.json`；Node 先 GET `internal-context-rewrite.json`，再 POST `model-result-rewrite.json`，最终 `run-completed-proposal.json` 与 `shot-proposal-pending.json`。 |
| 采纳 | `POST /storyboards/sb_001/shot-proposals/proposal_001/accept` body `{"expectedRevision":1}` | 200 新 revision 2 的分镜详情；只可替换 `shot_005` 的可编辑字段。 |
| 运行失败 | 轮询 `GET /agent/runs/run_generate_002` | 200 `run-failed.json`；没有 resultRef。 |
| 取消 | `POST /agent/runs/run_generate_003/cancel` 后查询 | 200 `run-cancelled.json`；迟到 result 回调为 409 `error-run-not-accepting-result.json`。 |
| revision 冲突 | `PUT /storyboards/sb_001` 携带旧 expectedRevision | 409 `error-stale-revision.json`。 |
| 幂等键冲突 | 同 `clientRequestId` 改变任一业务字段再次 POST | 409 `error-idempotency-reused.json`。 |
| 内部越权 | 缺 `storyboard:create`/`shot-proposal:create` scope 的结果回调 | 403 `error-insufficient-scope.json`。 |
| 外部未认证 / 跨组织 | 缺 Bearer 为 401；其他组织的有效 Bearer 查资源为 404 | `error-unauthenticated.json` / `error-resource-not-found.json`。 |

内部回调使用 `/internal/agent/runs/{runId}/storyboard-context` 与 `/internal/agent/runs/{runId}/storyboard-result`，并分别携带 Java 执行令牌及第 3 节冻结 scope。结果保存成功体必须符合 `internal-storyboard-result-response.schema.json`，返回的 artifactId 与最终 resultRef.id 相同。
