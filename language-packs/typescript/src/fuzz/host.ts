import { decode, encode } from "./codec.ts";
import { isEngineError, nonFinitePath } from "./oracles.ts";
import type { CallOutcome } from "./types.ts";

/**
 * Runs as a child process: loads one target module and calls one export on
 * request over IPC. The parent enforces time and memory limits by killing this
 * process, so nothing here needs to guard against hangs.
 *
 * usage: host.ts <module file URL> <export name> <function|property>
 */
const [moduleUrl, exportName, kind] = process.argv.slice(2) as [string, string, "function" | "property"];
const send = (message: unknown) => process.send!(message);

// Target code must not write to the campaign's output.
for (const method of ["log", "info", "warn", "error", "debug", "trace", "dir", "table"] as const) {
  console[method] = () => {};
}
// A promise the target forgets about must not take the process down.
process.on("unhandledRejection", () => {});

function describe(error: unknown): Pick<CallOutcome, "message" | "errorName" | "stack"> {
  if (error instanceof Error) {
    return { errorName: error.name, message: String(error.message).slice(0, 1000), stack: error.stack?.split("\n").slice(0, 12).join("\n") };
  }
  return { errorName: typeof error, message: String(error).slice(0, 1000) };
}

let target: (...args: unknown[]) => unknown;
try {
  const module = await import(moduleUrl);
  target = module[exportName];
  if (typeof target !== "function") throw new Error(`export '${exportName}' is not a function at runtime`);
  send({ type: "loaded" });
} catch (error) {
  send({ type: "load-error", ...describe(error) });
  process.disconnect();
  process.exit(0);
}

process.on("message", async ({ id, args }: { id: number; args: unknown[] }) => {
  let outcome: CallOutcome;
  try {
    let result = target(...(decode(args) as unknown[]));
    if (result !== null && typeof (result as Promise<unknown>)?.then === "function") result = await result;
    if (kind === "property") {
      // false violates the property; undefined means the input does not apply.
      outcome = result === false ? { kind: "property-violated", message: "returned false" }
        : result === undefined ? { kind: "rejected", message: "input discarded by the property" }
        : { kind: "ok" };
    } else {
      const bad = nonFinitePath(result);
      outcome = bad
        ? { kind: "non-finite-result", message: bad === "result" ? `returned ${String(result)}` : `${bad} is not a finite number`, result: encode(result) }
        : { kind: "ok", result: encode(result) };
    }
  } catch (error) {
    // In a property every exception is a violation, assertion errors included.
    outcome = { kind: kind === "property" ? "property-violated" : isEngineError(error) ? "runtime-error" : "rejected", ...describe(error) };
  }
  send({ type: "result", id, outcome });
});
