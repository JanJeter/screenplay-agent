# SB-03 / SB-04 handoff

Status: Java persistence, public generation/read/save API, structured AgentRun context and guarded result callback are implemented. The Node profile work is owned by SB-05 and must be integrated before a live generation can complete.

## Call sequence

1. Create/parse a script using the existing screenplay API, then select a returned scene ID.
2. Create a run:

```http
POST /api/v1/screenplay/projects/42/storyboards/generations
Authorization: Bearer <browser-jwt>
Content-Type: application/json

{"scriptId":"101","sceneId":"501","targetShotCount":6,"instructions":"保持信件未拆封，强调克制的夜景氛围。","clientRequestId":"sb-generate-20261006-001"}
```

The response is `202 {"runId":"<uuid>","status":"QUEUED"}`. Sending the exact body again returns the same run; reusing the key with a different body returns `409 IDEMPOTENCY_KEY_REUSED`.

3. The Gateway profile uses the execution credential to read the frozen context:

```http
GET /internal/agent/runs/<runId>/storyboard-context
Authorization: Bearer <execution-token>
```

4. After Node validates its output against the shared schema and semantic rules, it posts only editable content:

```http
POST /internal/agent/runs/<runId>/storyboard-result
Authorization: Bearer <execution-token>
Content-Type: application/json

{"result":{"mode":"generate","shots":[/* exactly targetShotCount EditableShot values */]}}
```

Java validates count, enum/length/duration and that every `sourceQuote` is an exact non-empty substring of the stored snapshot. Its response includes the persisted `artifactId`; the Gateway must then emit the normal completion event. `GET /api/v1/screenplay/agent/runs/<runId>` exposes the durable `resultRef` only after this save succeeds.

## Implemented Java endpoints

- `POST /projects/{projectId}/storyboards/generations`
- `GET /projects/{projectId}/storyboards?scriptId=&page=0&size=20`
- `GET /storyboards/{storyboardId}`
- `PUT /storyboards/{storyboardId}`
- `POST /storyboards/{storyboardId}/shots/{shotId}/regenerations`
- `GET|POST /internal/agent/runs/{runId}/storyboard-context|storyboard-result`

The SB-09 proposal read/accept/reject browser endpoints and SB-10 Markdown export remain intentionally unimplemented.

## Important integration rules

- Do not derive target scene text from a current scene row after receiving context. The frozen context/snapshot is authoritative because re-parsing replaces those rows.
- Profiles need `storyboard:read` plus exactly `storyboard:create` for generation or `shot-proposal:create` for rewrite. No generic screenplay/draft tool is issued to storyboard runs.
- A Gateway terminal `run.completed` succeeds for these task types only when Java has already stored an artifact. Cancellation and a late result callback are guarded by the same locked `AgentRun` state.
- The shared fixture validator is `node docs/storyboard-mvp/fixtures/validate-fixtures.mjs`.

## Verification performed

- `StoryboardIntegrationTest` uses real PostgreSQL/Testcontainers and verifies idempotent generation, an immutable source snapshot after reparse, cross-organization 404, and rejection of an invalid result without a new draft.
- `mvn test` passed under JDK 21 (full suite, including the new two-test integration class).
