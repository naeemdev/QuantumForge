import type { OracleKind } from "./types.ts";

/**
 * Error messages the JavaScript engine itself produces when code misuses a
 * value. A function that receives inputs matching its declared types and dies
 * with one of these has a defect. Errors the code throws on purpose, including
 * `throw new TypeError("sku is required")`, are treated as input rejection.
 */
const ENGINE_ERRORS: RegExp[] = [
  /^Cannot read propert(y|ies) of (undefined|null)/,
  /^Cannot set propert(y|ies) of (undefined|null)/,
  /^Cannot destructure /,
  /^Cannot convert undefined or null to object$/,
  /^Cannot convert a BigInt value to a number$/,
  /^Cannot mix BigInt and other types/,
  /^Cannot access '.+' before initialization$/,
  / is not a function$/,
  / is not a constructor$/,
  / is not iterable/,
  / is not defined$/,
  /^Assignment to constant variable\.$/,
  /^Reduce of empty array with no initial value$/,
  /^Maximum call stack size exceeded$/,
  /^Invalid array length$/,
  /^Invalid string length$/,
  /^Invalid time value$/,
  /^Invalid count value/,
  /^toFixed\(\) digits argument must be between/,
  /^toPrecision\(\) argument must be between/,
  /^Invalid regular expression/,
];

export const ORACLES: { id: OracleKind; description: string }[] = [
  { id: "runtime-error", description: "The function threw an engine error (for example reading a property of undefined) for inputs that match its declared parameter types." },
  { id: "non-finite-result", description: "The function returned NaN or Infinity (directly or in a field of the result) although every numeric input was finite." },
  { id: "timeout", description: "A single call did not return within the per-call time limit." },
  { id: "crash", description: "The function terminated its isolate: it ran out of memory or exited the process." },
];

export function isEngineError(error: unknown): error is Error {
  return error instanceof Error && ENGINE_ERRORS.some((pattern) => pattern.test(error.message));
}

/** Path of the first NaN/±Infinity in a result, looking two levels deep, or null. */
export function nonFinitePath(value: unknown, path = "result", depth = 0): string | null {
  if (typeof value === "number") return Number.isFinite(value) ? null : path;
  if (depth >= 2 || value === null || typeof value !== "object") return null;
  if (Array.isArray(value)) {
    for (let i = 0; i < Math.min(value.length, 50); i++) {
      const found = nonFinitePath(value[i], `${path}[]`, depth + 1);
      if (found) return found;
    }
    return null;
  }
  for (const [key, field] of Object.entries(value).slice(0, 50)) {
    const found = nonFinitePath(field, `${path}.${key}`, depth + 1);
    if (found) return found;
  }
  return null;
}
