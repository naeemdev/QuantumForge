/**
 * JSON-safe encoding for generated values. JSON cannot carry undefined, NaN,
 * Infinity or -0, which are exactly the values fuzzing cares about, so they are
 * tagged: {"$qf": "undefined" | "NaN" | "Infinity" | "-Infinity" | "-0"}.
 */
export function encode(value: unknown, depth = 0): unknown {
  if (depth > 8) return { $qf: "truncated" };
  if (value === undefined) return { $qf: "undefined" };
  if (typeof value === "number") {
    if (Number.isNaN(value)) return { $qf: "NaN" };
    if (value === Infinity) return { $qf: "Infinity" };
    if (value === -Infinity) return { $qf: "-Infinity" };
    if (Object.is(value, -0)) return { $qf: "-0" };
    return value;
  }
  if (typeof value === "bigint") return { $qf: "bigint", value: value.toString() };
  if (typeof value === "function") return { $qf: "function" };
  if (typeof value === "symbol") return { $qf: "symbol" };
  if (Array.isArray(value)) return value.slice(0, 50).map((v) => encode(v, depth + 1));
  if (value instanceof Date) return { $qf: "date", value: value.toISOString() };
  if (value !== null && typeof value === "object") {
    const out: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(value).slice(0, 50)) out[k] = encode(v, depth + 1);
    return out;
  }
  return value;
}

export function decode(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(decode);
  if (value !== null && typeof value === "object") {
    const tag = (value as { $qf?: string }).$qf;
    switch (tag) {
      case "undefined": return undefined;
      case "NaN": return NaN;
      case "Infinity": return Infinity;
      case "-Infinity": return -Infinity;
      case "-0": return -0;
      case undefined: break;
      default: throw new Error(`cannot decode tagged value ${tag}`);
    }
    const out: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(value)) {
      const decoded = decode(v);
      if (decoded !== undefined || (v as { $qf?: string })?.$qf === "undefined") out[k] = decoded;
    }
    return out;
  }
  return value;
}

/** Source-code literal for a decoded value, used in generated reproduction tests. */
export function toSource(value: unknown): string {
  if (value === undefined) return "undefined";
  if (typeof value === "number") {
    if (Number.isNaN(value)) return "NaN";
    if (value === Infinity) return "Infinity";
    if (value === -Infinity) return "-Infinity";
    if (Object.is(value, -0)) return "-0";
    return String(value);
  }
  if (typeof value === "string" || typeof value === "boolean" || value === null) return JSON.stringify(value);
  if (Array.isArray(value)) return `[${value.map(toSource).join(", ")}]`;
  if (typeof value === "object") {
    const entries = Object.entries(value as Record<string, unknown>).map(([k, v]) => `${/^[A-Za-z_$][\w$]*$/.test(k) ? k : JSON.stringify(k)}: ${toSource(v)}`);
    return entries.length ? `{ ${entries.join(", ")} }` : "{}";
  }
  return "undefined";
}
