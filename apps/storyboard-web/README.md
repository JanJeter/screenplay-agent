# Storyboard workbench

Standalone React + TypeScript client for the single-scene storyboard workbench.

```powershell
Copy-Item .env.example .env.local
npm install
npm run dev
```

`VITE_STORYBOARD_DATA_SOURCE=fixtures` renders the frozen SB-02 fixtures. Use query parameter `fixture=loading`, `fixture=empty`, or `fixture=failed` to inspect those states. Set it to `java` for integration; only Java `/api/v1` endpoints are called.

The workbench supports project creation, pasted script import and parsing, scene selection, 4–8-shot generation, run status recovery, cancellation, revision-aware editing, ordering, and saving. SSE uses a `fetch` stream with the existing Authorization header; if it disconnects, run status is recovered through polling. A completed run loads its `resultRef` from Java rather than parsing model stream text.

Fixture mode is a UI/contract aid only. It does not validate Java persistence, the Java↔Node HTTP path, or real model quality.
