# Screenplay MVP API

Java-only screenplay workflow under `/api/v1/screenplay`. All endpoints require the existing Bearer access token. Organization and creator IDs are derived from the authenticated user; an administrator is scoped to their own organization too. Unknown and other-organization project/script IDs return 404.

## Quick start (Swagger / Apifox)

1. Run PostgreSQL with `docker compose up -d db`.
2. Use JDK 21 and start the backend with `.\mvnw.cmd spring-boot:run`.
3. Open `http://localhost:8080/swagger-ui/index.html`, or sync Apifox from `http://localhost:8080/v3/api-docs`.
4. Login using `POST /api/v1/auth/login` with No Auth, then use its access token as Bearer auth for Screenplay endpoints.
5. Create a project, save a script version, analyze it, then query or export the breakdown.

### Create a project

`POST /api/v1/screenplay/projects` → 201 with a Location header:

```json
{"name":"雨夜试镜","description":"规则解析示例","genre":"悬疑"}
```

### Save a script version

`POST /api/v1/screenplay/projects/{projectId}/scripts` → 201:

```json
{
  "versionName": "Draft 1",
  "originalFilename": "sample-screenplay.txt",
  "rawText": "TITLE: 雨夜试镜\n\nINT. 旧摄影棚 - NIGHT\n\n雨声砸在屋顶上，收音机突然响起。\n\n周远\n你迟到了。\n\n林夏\n我跑过来的。\n\nEXT. 摄影棚后巷 - NIGHT\n\n一辆车停在雨里，有人拿起手机。"
}
```

This version accepts text as JSON; it does not store or read a path supplied by the caller. `originalFilename` is display metadata. `rawText` must be nonblank and at most 500,000 Java characters before line-ending normalization; source whitespace is preserved. `versionName` is trimmed and limited to 80 characters; `originalFilename` is limited to 255. Project names are trimmed, nonblank, at most 120 characters; genre is at most 120 and description 10,000.

### Analyze and inspect

`POST /api/v1/screenplay/scripts/{scriptId}/analyze` → 200:

```json
{"scriptId":1,"status":"ANALYZED","sceneCount":2,"elementCount":13}
```

IDs and element counts vary with the text. For the example, verify two scenes; locations 旧摄影棚 and 摄影棚后巷; characters 周远 and 林夏; PROP 收音机; SOUND 雨声; and RISK 夜戏.

| Method | Path after /api/v1/screenplay | Response |
| --- | --- | --- |
| POST | /projects | Project (201) |
| GET | /projects | Current organization's projects, newest first |
| GET | /projects/{projectId} | Project |
| POST | /projects/{projectId}/scripts | Saved version with rawText (201) |
| GET | /projects/{projectId}/scripts | Version history without rawText, newest first |
| GET | /scripts/{scriptId} | Version with rawText |
| POST | /scripts/{scriptId}/analyze | Status and scene/element counts |
| GET | /scripts/{scriptId}/scenes | Scenes in script order |
| GET | /scripts/{scriptId}/elements | Elements ordered by scene, then ID |
| GET | /scripts/{scriptId}/export/markdown | UTF-8 text/markdown body |

## Analysis guarantees

Analysis is synchronous. The new results replace all old elements/scenes for that version in one transaction together with the ANALYZED status. Reanalysis does not append duplicates; scene/element IDs can change.

A parse or persistence failure rolls back the replacement, then commits ANALYZE_FAILED in a separate transaction. Earlier results remain queryable and exportable. On first-attempt failure there are no old results. A failure to connect to the database can also prevent the failure status from being recorded.

A PostgreSQL advisory transaction lock rejects another concurrent analysis of the same script with 409, including across backend instances. Its namespace is `screenplay:analyze:{scriptId}`. The outer lock transaction spans both replacement and failure recording; inner transactions each use another database connection.

Admission is limited before taking a connection, to prevent competing analyses from exhausting the pool while each waits for its second connection. The limit is `screenplay.analysis.max-concurrent` (default 4), capped at half the Hikari pool size. Saturated capacity returns 503; retry later. Hikari needs at least two connections. With another pool implementation, configure at least twice the analysis concurrency as its connection capacity.

Markdown export uses a repeatable-read snapshot so reanalysis cannot mix different generations of scenes/elements in one export. It exports stored results, including older successful results when the latest status is ANALYZE_FAILED.

## Rules and limits

The parser supports English `INT.`, `EXT.`, `INT./EXT.` and the Chinese headings described in HANDOFF.md. It identifies simple English uppercase and Chinese dialogue cues; it is deterministic and does not perform NLP or make model calls. Element types implemented are CHARACTER, LOCATION, PROP, SOUND and RISK. Keyword rules are centralized in `screenplay/parser/ParserRules.java`.

Scripts without headings produce one UNSEGMENTED SCRIPT scene. Heading and location summaries are truncated to 255 and 120 characters while raw text is preserved. Unavailable summaries/page estimates stay null; no model-generated fields are fabricated.

PI, multipart upload, update/delete, storyboard, async runs, Excel/PDF export and production scheduling are outside this MVP. Schema creation still uses the existing local `ddl-auto: update`; introduce migrations before sharing/deploying the schema.

## Verification

Use JDK 21. Unit and PostgreSQL integration tests run with:

```powershell
$env:JAVA_HOME = 'C:\Users\tcf\.jdks\ms-21.0.9'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
mvn '-Dapi.version=1.44' test
```

Docker is required for the PostgreSQL integration suite. The API version override is needed for the installed Docker 29 daemon with the repository's current Testcontainers/docker-java dependencies; it applies only to the test process.

