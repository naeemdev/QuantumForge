import { fork, type ChildProcess } from "node:child_process";
import { fileURLToPath, pathToFileURL } from "node:url";
import type { CallOutcome, TargetKind } from "./types.ts";

const HOST = fileURLToPath(new URL("./host.ts", import.meta.url));
export const HEAP_LIMIT_MB = 256;

/**
 * One child process with one target module loaded. A call that exceeds its
 * time limit kills the process; the next call starts a fresh one, so a hang or
 * a crash never stalls the campaign. Child processes rather than worker
 * threads, because V8 enforces a heap limit reliably only per process.
 */
export class TargetSession {
  private child: ChildProcess | null = null;
  private ready: Promise<string | null> | null = null;
  private pending: { id: number; resolve: (o: CallOutcome) => void } | null = null;
  private nextId = 1;
  private readonly modulePath: string;
  private readonly exportName: string;
  private readonly kind: TargetKind;
  private readonly loadTimeoutMs: number;

  constructor(modulePath: string, exportName: string, kind: TargetKind = "function", loadTimeoutMs = 10_000) {
    this.modulePath = modulePath;
    this.exportName = exportName;
    this.kind = kind;
    this.loadTimeoutMs = loadTimeoutMs;
  }

  /** Resolves to null once loaded, or to the reason the module could not be loaded. */
  load(): Promise<string | null> {
    if (this.ready) return this.ready;
    const child = fork(HOST, [pathToFileURL(this.modulePath).href, this.exportName, this.kind], {
      execArgv: [...process.execArgv.filter((a) => !a.startsWith("--max-old-space-size")), `--max-old-space-size=${HEAP_LIMIT_MB}`],
      stdio: ["ignore", "ignore", "pipe", "ipc"],
    });
    let stderr = "";
    child.stderr!.on("data", (chunk: Buffer) => { if (stderr.length < 4096) stderr += chunk.toString(); });
    this.child = child;
    this.ready = new Promise((resolve) => {
      const timer = setTimeout(() => {
        resolve(`module did not finish loading within ${this.loadTimeoutMs} ms`);
        this.reset();
      }, this.loadTimeoutMs);
      child.on("message", (message: { type: string; id?: number; outcome?: CallOutcome; message?: string }) => {
        if (message.type === "loaded") { clearTimeout(timer); resolve(null); }
        else if (message.type === "load-error") { clearTimeout(timer); resolve(`module failed to load: ${message.message}`); }
        else if (message.type === "result" && this.pending && this.pending.id === message.id) {
          const { resolve: done } = this.pending;
          this.pending = null;
          done(message.outcome!);
        }
      });
      child.on("exit", (code, signal) => {
        const reason = /heap limit|out of memory/i.test(stderr)
          ? `ran out of memory (${HEAP_LIMIT_MB} MB heap limit)`
          : `the process ended during the call (${signal ?? `exit code ${code}`})`;
        clearTimeout(timer);
        resolve(`module failed to load: ${reason}`);
        if (this.child === child) {
          const pending = this.pending;
          this.child = null;
          this.ready = null;
          this.pending = null;
          pending?.resolve({ kind: "crash", errorName: "Crash", message: reason });
        }
      });
    });
    return this.ready;
  }

  async call(args: unknown[], timeoutMs: number): Promise<CallOutcome> {
    const loadError = await this.load();
    if (loadError) return { kind: "crash", errorName: "LoadError", message: loadError };
    const child = this.child!;
    const id = this.nextId++;
    return new Promise((resolve) => {
      const timer = setTimeout(() => {
        if (this.pending?.id !== id) return;
        this.pending = null;
        resolve({ kind: "timeout", errorName: "Timeout", message: `did not return within ${timeoutMs} ms` });
        this.reset();
      }, timeoutMs);
      this.pending = { id, resolve: (outcome) => { clearTimeout(timer); resolve(outcome); } };
      child.send({ id, args });
    });
  }

  reset(): void {
    const child = this.child;
    this.child = null;
    this.ready = null;
    this.pending = null;
    if (child && child.exitCode === null && child.signalCode === null) child.kill("SIGKILL");
  }
}
