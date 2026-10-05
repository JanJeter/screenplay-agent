import { randomUUID, timingSafeEqual } from "node:crypto";
import { createServer, type IncomingMessage, type ServerResponse } from "node:http";
import { createRuntime } from "./runtime.ts";
import { parseTaskType, type TaskType } from "./profiles.ts";

type Runtime = ReturnType<typeof createRuntime>;
type Status = "running" | "completed" | "failed" | "cancelled";
type Session = {
  id: string; userId: string; projectId: string; screenplayId: string;
  taskType: TaskType;
  runtime: Runtime; activeRun?: string; touchedAt: number; needsNewSession?: boolean;
};
type Run = {
  id: string; sessionId: string; userId: string; requestId: string; message: string;
  status: Status; events: string[]; bytes: number; listeners: Set<ServerResponse>;
  createdAt: number; endedAt?: number; stopReason?: string;
};
class HttpError extends Error {
  status: number;
  constructor(status: number, message: string) { super(message); this.status = status; }
}

const token = process.env.AGENT_GATEWAY_TOKEN;
if (!token || token.length < 16) throw new Error("Set AGENT_GATEWAY_TOKEN (at least 16 characters)");
if (!["mock", "live"].includes(process.env.AGENT_MODE ?? "mock")) throw new Error("Invalid AGENT_MODE");
if (!["mock", "http"].includes(process.env.JAVA_MODE ?? "mock")) throw new Error("Invalid JAVA_MODE");
const expectedAuth = Buffer.from(`Bearer ${token}`);
const sessions = new Map<string, Session>();
const runs = new Map<string, Run>();
const ttlMs = 30 * 60 * 1000;
const subscriptions = new Map<string, number>();
let totalSubscriptions = 0;

function json(res: ServerResponse, status: number, value: unknown): void {
  res.writeHead(status, { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" });
  res.end(JSON.stringify(value));
}

function authenticate(req: IncomingMessage): string {
  const actual = Buffer.from(req.headers.authorization ?? "");
  if (actual.length !== expectedAuth.length || !timingSafeEqual(actual, expectedAuth)) {
    throw new HttpError(401, "Unauthorized");
  }
  const userId = req.headers["x-agent-user"];
  if (typeof userId !== "string" || !/^[\w@.+-]{1,128}$/.test(userId)) {
    throw new HttpError(400, "X-Agent-User must be supplied by trusted Java backend");
  }
  return userId;
}

async function body(req: IncomingMessage): Promise<Record<string, unknown>> {
  if (!req.headers["content-type"]?.startsWith("application/json")) throw new HttpError(415, "Expected JSON");
  const chunks: Buffer[] = [];
  let size = 0;
  for await (const chunk of req) {
    const part = Buffer.from(chunk);
    size += part.length;
    if (size > 32_768) throw new HttpError(413, "Request too large");
    chunks.push(part);
  }
  let parsed: unknown;
  try { parsed = JSON.parse(Buffer.concat(chunks).toString("utf8")); }
  catch { throw new HttpError(400, "Invalid JSON"); }
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) throw new HttpError(400, "Expected object");
  return parsed as Record<string, unknown>;
}

function field(input: Record<string, unknown>, key: string, max = 128): string {
  const value = input[key];
  if (typeof value !== "string" || !value.trim() || value.length > max) throw new HttpError(400, `Invalid ${key}`);
  return value;
}

function optionalField(input: Record<string, unknown>, key: string, max = 12_000): string | undefined {
  const value = input[key];
  if (value === undefined) return undefined;
  if (typeof value !== "string" || !value.trim() || value.length > max) throw new HttpError(400, `Invalid ${key}`);
  return value;
}

function getRun(id: string, userId: string): Run {
  const run = runs.get(id);
  if (!run || run.userId !== userId) throw new HttpError(404, "Run not found");
  return run;
}

function emit(run: Run, type: string, data: Record<string, unknown>): void {
  const terminal = type.startsWith("run.") && type !== "run.started";
  if (!terminal && (run.events.length >= 5000 || run.bytes >= 2_000_000)) {
    run.stopReason ??= "event_limit";
    sessions.get(run.sessionId)?.runtime.agent.abort();
    return;
  }
  const seq = run.events.length + 1;
  const event = { version: 1, runId: run.id, sessionId: run.sessionId,
    agentId: sessions.get(run.sessionId)?.taskType ?? "general", seq, type, data };
  const wire = `id: ${seq}\nevent: ${type}\ndata: ${JSON.stringify(event)}\n\n`;
  run.events.push(wire);
  run.bytes += Buffer.byteLength(wire);
  for (const res of run.listeners) {
    // Disconnect slow subscribers; their next GET replays from Last-Event-ID.
    if (res.destroyed || !res.write(wire)) { run.listeners.delete(res); res.destroy(); }
  }
}

async function executeRun(session: Session, run: Run): Promise<void> {
  const timer = setTimeout(() => {
    run.stopReason ??= "timeout";
    session.runtime.agent.abort();
  }, 120_000);
  let failure: string | undefined;
  try {
    if (!run.stopReason) {
      session.runtime.prepareRun();
      emit(run, "run.started", {});
      await session.runtime.agent.prompt(run.message);
      // Pi records provider errors in state; prompt() can resolve on failure.
      const last = session.runtime.agent.state.messages.at(-1);
      if (session.runtime.agent.state.errorMessage || last?.role !== "assistant" || last.stopReason !== "stop") {
        failure = "generation_failed";
      } else if (!session.runtime.hasValidStructuredResult()) {
        failure = "invalid_structured_output";
      }
    }
  } catch { failure = "generation_failed"; }
  finally {
    clearTimeout(timer);
    run.status = run.stopReason === "user_cancelled" ? "cancelled" : run.stopReason || failure ? "failed" : "completed";
    run.endedAt = Date.now();
    // Cancelled tool batches may have incomplete call/result pairs. The MVP
    // requires a fresh session; production recovery must repair the transcript.
    if (run.status !== "completed") session.needsNewSession = true;
    session.activeRun = undefined;
    session.touchedAt = Date.now();
    emit(run, `run.${run.status}`, { status: run.status, code: run.stopReason ?? failure ?? "ok" });
    for (const res of run.listeners) res.end();
    run.listeners.clear();
  }
}

function streamEvents(req: IncomingMessage, res: ServerResponse, run: Run, url: URL): void {
  const cursor = req.headers["last-event-id"] ?? url.searchParams.get("after") ?? "0";
  if (typeof cursor !== "string" || !/^\d+$/.test(cursor) || Number(cursor) > run.events.length) {
    throw new HttpError(400, "Invalid event cursor");
  }
  if (totalSubscriptions >= 100 || (subscriptions.get(run.id) ?? 0) >= 4) {
    throw new HttpError(429, "Too many SSE subscribers");
  }
  totalSubscriptions++;
  subscriptions.set(run.id, (subscriptions.get(run.id) ?? 0) + 1);
  res.writeHead(200, {
    "Content-Type": "text/event-stream; charset=utf-8", "Cache-Control": "no-cache, no-transform",
    "Connection": "keep-alive", "X-Accel-Buffering": "no",
  });
  res.flushHeaders();
  res.on("error", () => res.destroy());
  res.write(": connected\n\n");
  const heartbeat = setInterval(() => { if (!res.write(": heartbeat\n\n")) res.destroy(); }, 15_000);
  const lifetime = setTimeout(() => res.destroy(), 150_000);
  res.on("close", () => {
    clearInterval(heartbeat); clearTimeout(lifetime); run.listeners.delete(res);
    totalSubscriptions--;
    const remaining = (subscriptions.get(run.id) ?? 1) - 1;
    if (remaining) subscriptions.set(run.id, remaining); else subscriptions.delete(run.id);
  });
  // Replay asynchronously with drain handling, then subscribe in the same JS turn
  // as the final cursor check so no event falls between replay and subscription.
  void (async () => {
    let index = Number(cursor);
    while (index < run.events.length && !res.destroyed) {
      if (!res.write(run.events[index++])) {
        await new Promise<void>((resolve) => {
          const done = () => { res.off("drain", done); res.off("close", done); resolve(); };
          res.once("drain", done); res.once("close", done);
        });
      }
    }
    if (res.destroyed) return;
    if (run.status !== "running") res.end();
    else run.listeners.add(res);
  })().catch(() => res.destroy());
}

const server = createServer((req, res) => {
  void (async () => {
    const url = new URL(req.url ?? "/", "http://localhost");
    if (req.method === "GET" && url.pathname === "/health") { json(res, 200, { ok: true }); return; }
    const userId = authenticate(req);
    if (req.method === "POST" && url.pathname === "/agent/sessions") {
      const input = await body(req);
      if (sessions.size >= 100) throw new HttpError(429, "MVP session capacity reached");
      const id = randomUUID();
      const executionToken = optionalField(input, "executionToken", 12_000);
      const applicationRunId = optionalField(input, "applicationRunId");
      const applicationSessionId = optionalField(input, "applicationSessionId");
      let taskType: TaskType;
      try { taskType = parseTaskType(input.taskType ?? "general"); }
      catch { throw new HttpError(400, "Unsupported taskType"); }
      if ((executionToken === undefined) !== (applicationRunId === undefined)
          || (executionToken === undefined) !== (applicationSessionId === undefined)) {
        throw new HttpError(400, "executionToken, applicationRunId and applicationSessionId must be supplied together");
      }
      if (process.env.JAVA_MODE === "http" && !executionToken) {
        throw new HttpError(400, "executionToken is required in JAVA_MODE=http");
      }
      const scope = {
        userId, projectId: field(input, "projectId"), screenplayId: field(input, "screenplayId"), sessionId: id,
        executionToken, applicationRunId, applicationSessionId, taskType,
      };
      const runtime = createRuntime(scope, (type, data) => {
        const active = sessions.get(id)?.activeRun;
        const run = active ? runs.get(active) : undefined;
        if (run) emit(run, type, data);
      });
      sessions.set(id, { ...scope, id, runtime, touchedAt: Date.now() });
      json(res, 201, { sessionId: id }); return;
    }
    if (req.method === "POST" && url.pathname === "/agent/chat") {
      const input = await body(req);
      const session = sessions.get(field(input, "sessionId"));
      if (!session || session.userId !== userId) throw new HttpError(404, "Session not found");
      const message = field(input, "message", 12_000);
      const requestId = field(input, "clientRequestId");
      const existing = [...runs.values()].find(r => r.sessionId === session.id && r.requestId === requestId);
      if (existing) {
        if (existing.message !== message) throw new HttpError(409, "Idempotency key reused with different message");
        json(res, 200, { runId: existing.id }); return;
      }
      if (session.activeRun) throw new HttpError(409, "Session already running");
      if (session.needsNewSession) throw new HttpError(409, "Previous run interrupted; start a new session");
      if (runs.size >= 300) throw new HttpError(429, "MVP run capacity reached");
      if (JSON.stringify(session.runtime.agent.state.messages).length > 100_000) {
        throw new HttpError(413, "MVP history limit reached; start a new session");
      }
      const run: Run = {
        id: randomUUID(), sessionId: session.id, userId, requestId, message, status: "running",
        events: [], bytes: 0, listeners: new Set(), createdAt: Date.now(),
      };
      runs.set(run.id, run); session.activeRun = run.id;
      json(res, 202, { runId: run.id });
      setImmediate(() => { void executeRun(session, run); }); return;
    }
    const match = /^\/agent\/runs\/([\w-]+)(?:\/(events|cancel))?$/.exec(url.pathname);
    if (match) {
      const run = getRun(match[1], userId);
      if (req.method === "GET" && match[2] === "events") { streamEvents(req, res, run, url); return; }
      if (req.method === "GET" && !match[2]) { json(res, 200, { runId: run.id, status: run.status }); return; }
      if (req.method === "POST" && match[2] === "cancel") {
        if (run.status === "running") { run.stopReason ??= "user_cancelled"; sessions.get(run.sessionId)?.runtime.agent.abort(); }
        json(res, 202, { runId: run.id, status: run.status }); return;
      }
    }
    throw new HttpError(404, "Not found");
  })().catch((error: unknown) => {
    if (res.headersSent) { res.destroy(); return; }
    json(res, error instanceof HttpError ? error.status : 500,
      { error: error instanceof HttpError ? error.message : "Internal gateway error" });
  });
});
server.requestTimeout = 30_000;
server.headersTimeout = 15_000;
const cleanup = setInterval(() => {
  for (const [id, run] of runs) if (run.endedAt && Date.now() - run.endedAt > ttlMs) runs.delete(id);
  for (const [id, session] of sessions) if (!session.activeRun && Date.now() - session.touchedAt > ttlMs) sessions.delete(id);
}, 60_000);
cleanup.unref();
server.listen(Number(process.env.PORT ?? 3001), "127.0.0.1", () => {
  console.log(`Agent Gateway: http://127.0.0.1:${process.env.PORT ?? 3001} (${process.env.AGENT_MODE ?? "mock"})`);
});
