# Storyboard workbench

Standalone React + TypeScript client for the single-scene storyboard workbench.

The approved UI design was migrated to the React app on 2026-10-09. The real local application is **http://127.0.0.1:5174/**. **http://127.0.0.1:15175/** remains the independent sample design, documented in [design/workbench-v1](../../design/workbench-v1/README.md); its sample projects and costs are not application data.

The implementation includes shared navigation, searchable project cards and type filters, script version selection and pasted-text import, the scene/shot/editor workbench, account flows, and team administration. Desktop uses the narrow sidebar and three-column workbench; mobile retains scene and saved-storyboard access through an expandable section. The existing API, authentication, organization permissions, run lifecycle, revision conflicts, unsaved-edit protection, and proposal acceptance/rejection remain authoritative. Recent-workspace navigation is scoped by organization and user. Workspace state resets between scripts, while query navigation within a script retains its edit-protection behavior. If saving an imported script succeeds but parsing fails, retrying parses the same saved version.

Production deployment, domain configuration, and SMTP setup are subsequent work. This UI migration does not add image/video generation or file upload.

```powershell
Copy-Item .env.example .env.local
npm install
npm run dev
```

`VITE_STORYBOARD_DATA_SOURCE=fixtures` renders the frozen SB-02 fixtures. Use query parameter `fixture=loading`, `fixture=empty`, or `fixture=failed` to inspect those states. Set it to `java` for integration; only Java `/api/v1` endpoints are called.

The workbench supports project creation, pasted script import and parsing, scene selection, 4–8-shot generation, run status recovery, cancellation, revision-aware editing, ordering, and saving. SSE uses a `fetch` stream with the existing Authorization header; if it disconnects, run status is recovered through polling. A completed run loads its `resultRef` from Java rather than parsing model stream text.

Fixture mode is a UI/contract aid only. It does not validate Java persistence, the Java↔Node HTTP path, or real model quality.

Account routes are `/register`, `/verify-email?token=...`, `/resend-verification`, `/forgot-password`, and `/reset-password?token=...`. Email verification is an explicit form action so development React remounts cannot consume a one-time link twice. Resetting a password also removes the browser's previous session. Account and admin requests share the existing token refresh flow; concurrent expired requests use one refresh.

The account menu loads identity, workspace, and roles from `/api/v1/auth/me`. Only a server-reported `ADMIN` role exposes `/admin/users`, `/admin/roles`, `/admin/runs`, and `/admin/usage`; the server independently authorizes every admin request. The admin pages support user search/pagination, account status, role assignment, custom role permissions, task filters/details, and usage summaries. System roles remain read-only. Costs are labeled estimates, and mock/missing/partial records are distinguished from measured zero cost. Local development email files are documented in `scripts/WORKBENCH_USAGE.md`; the UI does not expose a mailbox or verification-token listing.

`e2e/accounts-admin.spec.ts` covers account forms, access control, admin workflows, concurrent token refresh, and desktop/mobile layout with intercepted HTTP. It never sends a real email or starts a model task.

For the persistent local environment, run `.\scripts\workbench.ps1 -Action Start` from the repository root. Use `-Action Status` or `-Action Stop` to inspect or stop it; see `scripts/WORKBENCH_USAGE.md`. The frontend must use `VITE_STORYBOARD_DATA_SOURCE=java` and the same `VITE_AGENT_MODE=mock` or `live` as its Gateway. Mock mode displays a workflow-test label on the login page and workbench. Never put provider credentials in a `VITE_*` variable.

When `VITE_JAVA_API_BASE` is blank, Vite proxies `/api` to `JAVA_API_PROXY_TARGET` (default `http://127.0.0.1:18084`). An explicit `VITE_JAVA_API_BASE` instead calls that Java service directly.

## Safe UI validation

Run these commands from the repository root after the Vite application is available at port 5174 with `VITE_STORYBOARD_DATA_SOURCE=java`. The six browser specs below intercept every `/api/v1` request; they do not reach Java, send email, or call a model. Coverage includes account/admin forms and authorization, unsaved edits and delayed responses, failure recovery, archived-environment protection, script import recovery, and workspace isolation across script paths. The archived-environment test requires Vite's development server because it intercepts the source environment module.

```powershell
npm.cmd --prefix apps/storyboard-web run test
npm.cmd --prefix apps/storyboard-web run build
$env:E2E_WEB_URL = 'http://127.0.0.1:5174'
$env:PLAYWRIGHT_CHROMIUM_EXECUTABLE = 'C:\Program Files\Google\Chrome\Application\chrome.exe'
npm.cmd --prefix apps/storyboard-web run test:e2e -- accounts-admin.spec.ts storyboard-protection.spec.ts storyboard-review-regressions.spec.ts storyboard-legacy-environment.spec.ts script-import-recovery.spec.ts workspace-route-isolation.spec.ts --workers=1 --output ../../.local/workbench-dev/ui-redesign-regressions
```

Do not run an unfiltered `test:e2e` against a live Gateway. `storyboard-http.spec.ts` is **mock mode only**: it makes one real HTTP generation request and two shot-redo requests. Before running it separately, verify that the Gateway and frontend both use `mock`, then set `E2E_WEB_URL`, `E2E_JAVA_URL`, and optionally `E2E_ARTIFACT_DIR`. It verifies the browser clipboard and downloaded UTF-8 Markdown against Java persistence. `E2E_LOGIN_EMAIL` and `E2E_LOGIN_PASSWORD` override the local demo credentials. It never saves a login token.

For read-only screenshots of saved application data, run from the repository root:

```powershell
node scripts/capture-workbench-ui.cjs
```

This script captures desktop/mobile account, project, script, workbench, and admin views into `.local/workbench-dev/ui-redesign/`, with `visual-verification.json` recording browser errors and overflow checks. It permits GET/HEAD plus login and token refresh, blocks business writes including generation, and compares the existing activity's model-budget ledger before and after capture. It requires the local app/API, an admin login, and at least one saved storyboard; it does not create content to obtain screenshots. Local email remains file-based until SMTP is explicitly configured.
