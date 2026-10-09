import { randomUUID } from "node:crypto";
import { closeSync, existsSync, fsyncSync, mkdirSync, openSync, readFileSync, renameSync, unlinkSync, writeFileSync } from "node:fs";
import { dirname } from "node:path";

/** One writer per activity. Every successful save is flushed before a paid request can start. */
export class PersistentBudgetLedger {
  private readonly lockPath: string;
  private readonly owner = randomUUID();
  private closed = false;

  constructor(private readonly path: string) {
    mkdirSync(dirname(path), { recursive: true });
    this.lockPath = `${path}.lock`;
    this.acquire();
  }

  read(): unknown | undefined {
    return existsSync(this.path) ? JSON.parse(readFileSync(this.path, "utf8")) as unknown : undefined;
  }

  save(value: unknown): void {
    if (this.closed) throw new Error("Budget ledger is closed");
    const temporaryPath = `${this.path}.${this.owner}.tmp`;
    let descriptor: number | undefined;
    try {
      descriptor = openSync(temporaryPath, "wx", 0o600);
      writeFileSync(descriptor, `${JSON.stringify(value, null, 2)}\n`, "utf8");
      fsyncSync(descriptor);
      closeSync(descriptor);
      descriptor = undefined;
      renameSync(temporaryPath, this.path);
    } finally {
      if (descriptor !== undefined) closeSync(descriptor);
      if (existsSync(temporaryPath)) unlinkSync(temporaryPath);
    }
  }

  close(): void {
    if (this.closed) return;
    this.closed = true;
    if (this.readOwner()?.owner === this.owner) unlinkSync(this.lockPath);
  }

  private acquire(): void {
    // A crashed process may leave a lock; only a confirmed dead PID is recoverable.
    if (existsSync(this.lockPath)) {
      // Serialize stale-lock removal, so two restarting processes cannot both
      // remove a dead lock and accidentally unlink the other's new lock.
      const recoveryPath = `${this.lockPath}.recover`;
      const recovery = openSync(recoveryPath, "wx", 0o600);
      try {
        const existing = this.readOwner();
        if (!existing || !Number.isSafeInteger(existing.pid) || existing.pid <= 0) {
          throw new Error("Budget ledger lock is invalid; inspect it before restarting");
        }
        let dead = false;
        try { process.kill(existing.pid, 0); }
        catch (error) { dead = (error as NodeJS.ErrnoException).code === "ESRCH"; }
        if (!dead) throw new Error("Budget ledger already has an active writer");
        if (this.readOwner()?.owner !== existing.owner) throw new Error("Budget ledger lock changed during recovery");
        unlinkSync(this.lockPath);
      } finally {
        closeSync(recovery);
        unlinkSync(recoveryPath);
      }
    }
    const descriptor = openSync(this.lockPath, "wx", 0o600);
    try {
      writeFileSync(descriptor, JSON.stringify({ pid: process.pid, owner: this.owner }), "utf8");
      fsyncSync(descriptor);
    } finally { closeSync(descriptor); }
  }

  private readOwner(): { pid: number; owner: string } | undefined {
    try {
      const value = JSON.parse(readFileSync(this.lockPath, "utf8")) as { pid: number; owner: string };
      return typeof value.owner === "string" && value.owner ? value : undefined;
    } catch { return undefined; }
  }
}
