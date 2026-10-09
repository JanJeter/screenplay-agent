# HANDOFF: Screenplay Agent Backend Adaptation

> 2026-10-08 当前交接入口：[分镜工作台实际使用阶段交接](D:/desktop/screenplay-agent-backend/docs/WORKBENCH_NEXT_PHASE_HANDOFF.md)。PI 接入和 SB-12 工程收尾已完成，下一阶段推进独立开发环境与网页实际操作。用户已暂缓真人评价材料及供应商对账，不以它们阻挡本地开发。
>
> 下文保留早期 Java-only 阶段背景；其中“PI 待接入”、旧下一步任务和旧运行状态不再作为当前执行计划。

## Current Goal

Adapt the existing Java Spring Boot SaaS backend starter into an AI film/script production backend.

The target product is:

**AI 影视剧本制片 Agent 工作台**

Core idea:

- Java Spring Boot handles users, auth, permissions, project data, file upload, task status, audit logs, and exports.
- PI Agent remains a separate Agent runtime/service later.
- Java calls PI through an `AgentClient` abstraction instead of mixing TypeScript PI source into the Java project.

## Local Projects

Java backend:

```text
D:\desktop\screenplay-agent-backend
```

PI source reference:

```text
D:\desktop\pi-agent-src-zip\pi-agent-main
```

ScriptBreak local reference:

```text
C:\Users\tcf\Documents\Codex\2026-10-01\zai\outputs\scriptbreak-index.html
```

Sample screenplay:

```text
C:\Users\tcf\Documents\Codex\2026-10-01\zai\outputs\sample-screenplay.txt
```

## Current Backend Status

As of 2026-10-03, the Java-only `screenplay` MVP is implemented under
`src/main/java/com/urke/saasbackendstarter/screenplay`.

Implemented:

- Organization-scoped project create/list/detail and script version create/list/detail.
- JSON raw-text input with metadata validation and the 500,000-character limit.
- Rule-based parsing through `AgentClient` / `RuleBasedAgentClient`.
- Transactional replacement of scenes/elements, with separately committed failure status.
- PostgreSQL advisory locking for concurrent analysis and a pool-aware admission limit.
- Scene/element queries and UTF-8 Markdown export, including retained results after failure.
- All ten proposed API operations documented in Swagger.

See [SCREENPLAY_API.md](SCREENPLAY_API.md) for request examples, error behavior,
analysis concurrency settings and test commands. Existing base auth and PostgreSQL
configuration are reused. PI integration remains future work.

The active code directory is `D:\desktop\screenplay-agent-backend`.
`D:\desktop\ChatGPT\screenplay-agent-backend` is a separate empty repository;
do not confuse the two paths.

Verification on 2026-10-03:

- JDK 21, `mvn '-Dapi.version=1.44' verify`: 85 tests passed, 0 failed/error/skipped; executable JAR packaged successfully.
- Includes 10 real PostgreSQL integration checks with JWT authentication, organization isolation,
  input boundaries, reanalysis, failed replacement rollback, separately committed failure status,
  concurrent 409, and all ten Swagger operations.
- Integration tests use a disposable PostgreSQL container; they do not modify the local development database.
- The existing application on port 8080 must be restarted to load the new code. New Screenplay
  endpoints still need a manual Apifox import/sync; this task did not control Apifox.

Verified locally:

```text
Spring Boot starts on port 8080
PostgreSQL Docker container runs on host port 55432
Swagger opens
Apifox can login and call protected user APIs
JWT filter/debug flow was inspected in IDEA
```

Swagger:

```text
http://localhost:8080/swagger-ui/index.html
```

Apifox imported OpenAPI from:

```text
http://localhost:8080/v3/api-docs
```

Default admin:

```text
email: admin@demo.com
password: admin12345
```

Apifox environment:

```text
local
base URL: http://localhost:8080
variable: bearerToken = accessToken from login response
```

Login endpoint:

```text
POST /api/v1/auth/login
```

Login endpoint must use **No Auth**. Other protected endpoints can inherit `bearerAuth`.

## Database Setup

The project was changed from MariaDB to PostgreSQL.

Important: local machine already had another PostgreSQL using port `5432`, so Docker PostgreSQL was remapped to host port `55432`.

Current database connection:

```text
host: localhost
port: 55432
database: saas_backend
user: saas_user
password: adminpassword
```

Changed files:

```text
D:\desktop\screenplay-agent-backend\docker-compose.yml
D:\desktop\screenplay-agent-backend\src\main\resources\application.yaml
D:\desktop\screenplay-agent-backend\pom.xml
```

Expected config:

```yaml
spring:
  datasource:
    url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:55432/saas_backend}
    username: ${SPRING_DATASOURCE_USERNAME:saas_user}
    password: ${SPRING_DATASOURCE_PASSWORD:adminpassword}
    driver-class-name: org.postgresql.Driver
```

Docker mapping:

```yaml
ports:
  - "55432:5432"
```

Useful commands:

```powershell
cd D:\desktop\screenplay-agent-backend
docker compose up -d db
docker ps
```

Run the app from IDEA with JDK 21, or from terminal after JDK 21 is configured:

```powershell
.\mvnw.cmd spring-boot:run
```

Development database schema currently uses:

```yaml
spring.jpa.hibernate.ddl-auto: update
```

This is acceptable only for the early local MVP. Introduce Flyway or Liquibase before the schema becomes shared, before deploying anywhere, or once the screenplay tables stabilize.

## Existing Backend Capabilities

The starter already provides:

- JWT authentication
- Access token + refresh token flow
- RBAC/PBAC style roles and permissions
- Organizations as tenant/team boundary
- User management
- File upload/download
- Audit logs
- Swagger/OpenAPI
- WebSocket support
- Docker Compose

Existing tables include:

```text
organizations
users
roles
permissions
user_roles
role_permissions
refresh_tokens
password_reset_tokens
user_files
audit_logs
```

## Debugging Notes

Already debugged:

### Login

Main path:

```text
AuthController.login()
  -> AuthServiceImpl.login()
  -> authenticationManager.authenticate(...)
  -> CustomUserDetailsService.loadUserByUsername(...)
  -> UserRepository
  -> JwtTokenProvider.generateToken(...)
  -> RefreshTokenRepository.save(...)
```

### Protected API Request

Main path:

```text
JwtAuthenticationFilter.doFilterInternal()
  -> request.getHeader("Authorization")
  -> jwtTokenProvider.getEmailFromToken(token)
  -> userDetailsService.loadUserByUsername(email)
  -> jwtTokenProvider.validateToken(token, userDetails)
  -> SecurityContextHolder.getContext().setAuthentication(auth)
  -> UserController
```

Debug keys:

- `F8`: step over, safest for app-level flow
- `F7`: step into, may enter Spring/JWT library source
- `Shift + F8`: step out
- `F9`: continue to next breakpoint

Avoid stepping deep into Spring framework classes like `OncePerRequestFilter` or `ResponseEntity`.

## Reference Product Thinking

ScriptBreak is not a backend project. It is a pure local browser app:

```text
single HTML file
local JavaScript parser
localStorage/project export
no server
no database
no API
no model calls
```

Useful concepts to borrow:

- Projects
- Drafts/script versions
- Scenes
- Elements
- Shot List
- Bibles
- Look
- Export center
- Prompt packs

Do not copy its architecture. Use it as product/reference workflow only.

## Recommended Target Architecture

Keep Java and PI separate:

```text
React frontend
  -> Spring Boot backend
       -> PostgreSQL
       -> file storage
       -> AgentClient abstraction
            -> RuleBasedAgentClient first
            -> PiAgentClient later
                 -> PI Agent service
```

Do not merge PI TypeScript source into the Spring Boot Maven project.

Use Java backend as business/data boundary:

- users
- organization/tenant
- permissions
- screenplay projects
- scripts
- scenes
- elements
- storyboard shots
- agent run status
- audit logs
- exports

Use PI later for intelligent tasks:

- scene summary
- element extraction enhancement
- storyboard generation
- risk analysis
- continuity check
- prompt pack generation

## First Adaptation Scope

Do not start with PI integration.

First implement a Java-only MVP.

MVP includes:

```text
1. Create screenplay project
2. List screenplay projects
3. Get screenplay project detail
4. Upload or save script text
5. Rule-based scene parsing
6. Store scenes
7. Extract basic elements
8. Query scenes/elements
9. Export simple Markdown
```

MVP does not include:

```text
project update/delete
script version update/delete
storyboard generation
PI Agent integration
async agent_run execution
Excel/PDF export
full production scheduling
advanced Chinese NLP
```

This gives a stable backend before Agent work.

## Tenant and Security Rules

Tenant isolation is priority one.

All screenplay business data must be scoped by the current user's organization. Do not trust organization IDs from request bodies.

Use the logged-in user context, preferably existing project utilities such as `CurrentUserProvider`, to derive:

```text
current user
current organization
current roles/authorities
```

Rules:

```text
1. screenplay_projects.organization_id is always set from the logged-in user's organization.
2. screenplay_projects.created_by is always set from the logged-in user.
3. Access to project, script version, scene, element, and export data must verify organization ownership.
4. Cross-organization reads must return 404 or 403 consistently.
5. Cross-organization writes must be rejected.
6. Admin privileges are still organization-scoped unless explicitly designed otherwise.
```

Do not implement screenplay APIs as global data access.

## Analyze Behavior

Define `/analyze` behavior before coding to avoid duplicate or half-written parse results.

MVP behavior:

```text
POST /api/v1/screenplay/scripts/{scriptId}/analyze
```

Rules:

```text
1. Analyze is synchronous in the Java-only MVP.
2. Analyze reparses the script version and replaces previous parsed results for that script version.
3. Replacement must be transactional.
4. On success: delete old scene_elements first, then delete old script_scenes, or use database cascade delete; then insert new scenes/elements.
5. On success: set script_versions.status = ANALYZED in the same transaction as the replacement.
6. On failure: keep previous parsed results unchanged.
7. On failure: set script_versions.status = ANALYZE_FAILED in a separate status update so it is not rolled back with the failed replacement transaction.
8. If status is ANALYZE_FAILED but older scenes/elements exist from a previous successful analyze, those old results remain readable and exportable.
9. Concurrent analyze requests for the same script version should be rejected with 409 CONFLICT or serialized with a lock. Prefer 409 for MVP.
10. Re-running analyze on unchanged raw text should not create duplicate scenes.
```

Later, PI/LLM analyze should move to the async `agent_runs` design.

## Rule-Based Script Parsing

MVP parser should be deliberately small and testable.

Supported scene heading patterns:

```text
INT. OLD STUDIO - NIGHT
EXT. ALLEY - DAY
INT./EXT. CAR - NIGHT
内景 旧摄影棚 夜
外景 摄影棚后巷 夜
第1场 旧摄影棚 夜 内
第 1 场 旧摄影棚 夜 内景
```

Normalize fields:

```text
interiorExterior: INT, EXT, INT_EXT, UNKNOWN
timeOfDay: DAY, NIGHT, UNKNOWN
location: parsed text if available
heading: original heading line
rawText: text until next scene heading
sortOrder: scene order in script
sceneNo: detected scene number or generated sequence
```

Character extraction for MVP:

```text
1. English-style uppercase character cues can be detected.
2. Chinese dialogue names can be detected when a short standalone line is followed by dialogue text.
3. Do not attempt advanced named-entity recognition in MVP.
```

Element extraction for MVP should support only:

```text
CHARACTER
LOCATION
PROP
SOUND
RISK
```

Other enum values may exist later, but should not be claimed as supported until implemented.

Minimum rule hints for MVP acceptance:

```text
CHARACTER: detected dialogue cue names, for example 林夏, 周远
LOCATION: parsed scene heading locations, for example 旧摄影棚, 摄影棚后巷
PROP: keyword examples 收音机, 手电筒, 车, 手机
SOUND: keyword examples 雨声, 雷声, 电话铃, 脚步声
RISK: keyword examples 夜戏, 雨, 雨声, 爆炸, 打斗, 火; also map timeOfDay = NIGHT to RISK: 夜戏
```

These are simple deterministic rule hints, not NLP guarantees. Add or change keywords through a small parser configuration/list rather than scattering string checks across services.

Fallback behavior:

```text
If no scene heading is detected, create one scene:
sceneNo = 1
heading = "UNSEGMENTED SCRIPT"
interiorExterior = UNKNOWN
timeOfDay = UNKNOWN
location = null
rawText = full script text
```

## Proposed Package Structure

Add new package area under:

```text
src/main/java/com/urke/saasbackendstarter/screenplay
```

Suggested subpackages:

```text
screenplay
  controller
  service
  service.impl
  domain
  dto
  repository
  mapper
  parser
  agent
  export
```

Keep it isolated from the existing SaaS starter modules where possible.

## Proposed Business Tables

Start small:

```text
screenplay_projects
script_versions
script_scenes
scene_elements
```

Later add:

```text
storyboard_shots
agent_runs
agent_run_logs
export_files
```

### screenplay_projects

Purpose: film/script project.

Fields:

```text
id
organization_id
name
description
genre
status
created_by
created_at
updated_at
```

Constraints/indexes:

```text
organization_id not null
name not blank, max 120
status enum: ACTIVE, ARCHIVED
index: organization_id
index: organization_id, created_at
optional unique later: organization_id, name
```

### script_versions

Purpose: uploaded/imported script draft.

Fields:

```text
id
project_id
version_name
original_filename
file_path
raw_text
status
created_at
```

Constraints/indexes:

```text
project_id not null
version_name not blank, max 80
original_filename max 255
raw_text not blank after trim
raw_text max length for MVP: 500_000 characters
status enum: UPLOADED, ANALYZED, ANALYZE_FAILED
index: project_id
index: project_id, created_at
```

Input handling:

```text
Reject blank rawText with 400 BAD_REQUEST.
Reject rawText over 500_000 characters with 400 BAD_REQUEST before parsing.
Normalize line endings to \n before parsing.
Trim versionName, but do not trim rawText except for blank validation.
```

### script_scenes

Purpose: parsed screenplay scenes.

Fields:

```text
id
script_version_id
scene_no
heading
interior_exterior
location
time_of_day
summary
raw_text
page_estimate
sort_order
```

Constraints/indexes:

```text
script_version_id not null
heading max 255
location max 120
interior_exterior enum/string max 20
time_of_day enum/string max 20
raw_text not null
sort_order not null
unique: script_version_id, sort_order
index: script_version_id
```

Parser-to-DB boundary handling:

```text
If parsed heading is longer than 255 characters, store a 255-character prefix in heading.
If parsed location is longer than 120 characters, store a 120-character prefix in location.
Keep the full scene body in raw_text, subject to the script-level 500_000 character input limit.
Do truncation in parser/service code before persistence so DB writes do not fail on valid uploaded scripts.
```

### scene_elements

Purpose: extracted production elements.

Fields:

```text
id
scene_id
type
name
description
confidence
```

Types:

```text
CHARACTER
LOCATION
PROP
SOUND
RISK
```

Constraints/indexes:

```text
scene_id not null
type not null
name not blank, max 120
confidence optional numeric 0.0 - 1.0
index: scene_id
index: scene_id, type
```

## Proposed First APIs

Use `/api/v1/screenplay/...` prefix.

```text
POST /api/v1/screenplay/projects
GET  /api/v1/screenplay/projects
GET  /api/v1/screenplay/projects/{projectId}

POST /api/v1/screenplay/projects/{projectId}/scripts
GET  /api/v1/screenplay/projects/{projectId}/scripts
GET  /api/v1/screenplay/scripts/{scriptId}

POST /api/v1/screenplay/scripts/{scriptId}/analyze
GET  /api/v1/screenplay/scripts/{scriptId}/scenes
GET  /api/v1/screenplay/scripts/{scriptId}/elements

GET  /api/v1/screenplay/scripts/{scriptId}/export/markdown
```

Project detail may also return a lightweight script version summary list, but do not rely on that as the only history entry point. Keep `GET /api/v1/screenplay/projects/{projectId}/scripts` as the explicit version-list API.

Do not implement update/delete in the first pass unless the MVP above is already complete.

First version can accept raw text JSON instead of multipart upload if faster:

```json
{
  "versionName": "Draft 1",
  "originalFilename": "sample-screenplay.txt",
  "rawText": "INT. OLD STUDIO - NIGHT..."
}
```

Markdown export response can be either:

```text
text/markdown response body
```

or a downloadable file response. Pick the simpler implementation first, but document the response type in Swagger.

## Agent Integration Plan

Add Java abstraction first:

```java
public interface AgentClient {
    ScriptAnalysisResult analyzeScript(String scriptText);
}
```

Implement first:

```text
RuleBasedAgentClient
```

Later:

```text
PiAgentClient
StoryboardAgentClient or an expanded AgentClient when storyboard generation enters scope
```

PI should be exposed as a separate HTTP service:

```text
POST /agent/script/analyze
POST /agent/scene/storyboard
POST /agent/script/risk
POST /agent/script/continuity
```

Java stores results and manages status.

## Future Async Design

For slow PI/LLM tasks:

```text
POST /api/v1/screenplay/scripts/{scriptId}/analyze
  -> creates agent_run
  -> returns runId immediately
  -> background job calls PI
  -> results saved to DB
```

Status API:

```text
GET /api/v1/agent-runs/{runId}
```

Do not make the frontend wait synchronously for long LLM tasks.

## MVP Acceptance Criteria

Before moving to PI integration, verify:

```text
1. Authenticated user can create a screenplay project.
2. Authenticated user can list only projects in their organization.
3. Cross-organization project/script access is denied.
4. User can create a script version under their project.
5. Sample script parses into two scenes.
6. Re-running analyze on the same script version does not create duplicate scenes.
7. If analyze fails, script_versions.status becomes ANALYZE_FAILED, but previous scenes/elements remain queryable and exportable.
8. Basic elements are extracted for fixed sample rules: 收音机 -> PROP, 雨声 -> SOUND, timeOfDay = NIGHT -> RISK: 夜戏.
9. Blank rawText and rawText over 500_000 characters are rejected with 400 before parsing.
10. Markdown export returns a readable scene breakdown.
11. Swagger exposes all new endpoints.
12. Apifox can import/sync and call endpoints with inherited Bearer token.
```

Recommended sample:

```text
TITLE: 雨夜试镜

INT. 旧摄影棚 - NIGHT
...

EXT. 摄影棚后巷 - NIGHT
...
```

Expected:

```text
2 scenes
locations include 旧摄影棚 and 摄影棚后巷
characters include 林夏 and 周远 if dialogue cues are detected
props include 收音机 when present in sample text
sounds include 雨声 when present in sample text
risks include 夜戏 when a scene heading resolves to timeOfDay = NIGHT
```

## Next Task Recommendation

The first MVP is implemented. Next:

1. Restart the application from the actual code directory using JDK 21.
2. Sync Apifox from `http://localhost:8080/v3/api-docs` and follow
   [SCREENPLAY_API.md](SCREENPLAY_API.md) with inherited Bearer auth.
3. Validate the real sample screenplay through the UI/API workflow.
4. Introduce migrations before sharing or deploying this schema.
5. Scope a separate PI HTTP service and async agent runs when moving beyond
   deterministic synchronous parsing; keep PI source outside this Java project.

