import type { TypeSpec } from "./types.ts";

/** Small, fast, seedable PRNG (mulberry32): campaigns are reproducible from their seed. */
export function prng(seed: number): () => number {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/**
 * Boundary values first: these find most defects. Magnitudes stay within ±1e9
 * so that plain floating-point overflow does not drown real findings.
 */
const NUMBER_EDGES = [0, 1, -1, 2, 10, 100, 1000, 0.5, 0.1, 0.2, 0.3, 1.005, 2.675, 0.125, -0.5, 99.99, 1e6, 1e9, -1e9, 3];
const STRING_EDGES = ["", " ", "a", "abc", "A-1", "ü", "日本", "😀", "a".repeat(64), "\n", "null", "0", "-1", "<b>", "' OR 1=1 --"];
const MAX_DEPTH = 4;

export function generate(spec: TypeSpec, random: () => number, depth = 0): unknown {
  const pick = <T>(items: readonly T[]): T => items[Math.floor(random() * items.length)]!;
  switch (spec.kind) {
    case "number": {
      const r = random();
      if (r < 0.5) return pick(NUMBER_EDGES);
      if (r < 0.75) return Math.floor(random() * 2001) - 1000;
      if (r < 0.9) return Math.round((random() * 2000 - 1000) * 100) / 100;
      return (random() * 2 - 1) * 1e6;
    }
    case "string": {
      if (random() < 0.5) return pick(STRING_EDGES);
      const length = Math.floor(random() * 12);
      const alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_ ";
      let s = "";
      for (let i = 0; i < length; i++) s += alphabet[Math.floor(random() * alphabet.length)];
      return s;
    }
    case "boolean": return random() < 0.5;
    case "null": return null;
    case "undefined": return undefined;
    case "literal": return spec.value;
    case "union": return generate(pick(spec.options), random, depth);
    case "tuple": return spec.elements.map((e) => generate(e, random, depth + 1));
    case "array": {
      if (depth >= MAX_DEPTH) return [];
      const r = random();
      const length = r < 0.25 ? 0 : r < 0.5 ? 1 : 2 + Math.floor(random() * 5);
      return Array.from({ length }, () => generate(spec.element, random, depth + 1));
    }
    case "object": {
      const out: Record<string, unknown> = {};
      for (const p of spec.properties) {
        if (p.optional && random() < 0.3) continue;
        out[p.name] = depth >= MAX_DEPTH ? undefined : generate(p.type, random, depth + 1);
      }
      return out;
    }
  }
}

/**
 * Candidate simplifications of a value, simplest first. Shrinking keeps any
 * candidate that still fails the same way, so the reported input is small and
 * readable.
 */
export function shrinkCandidates(spec: TypeSpec, value: unknown): unknown[] {
  switch (spec.kind) {
    case "number": {
      if (typeof value !== "number" || value === 0) return [];
      const out = new Set<number>([0, 1]);
      if (!Number.isInteger(value)) out.add(Math.trunc(value));
      if (Math.abs(value) > 1) out.add(Math.trunc(value / 2));
      if (value < 0) out.add(-value);
      const rounded = Math.round(value * 100) / 100;
      if (rounded !== value) out.add(rounded);
      out.delete(value);
      return [...out].filter((n) => Math.abs(n) < Math.abs(value) || (Number.isInteger(n) && !Number.isInteger(value)));
    }
    case "string": {
      if (typeof value !== "string" || value === "") return [];
      const out = [""];
      if (value.length > 1) out.push(value.slice(0, Math.ceil(value.length / 2)), value.slice(1));
      if (!/^[a-z]*$/.test(value)) out.push("a".repeat(Math.min(value.length, 3)));
      return out.filter((s) => s !== value);
    }
    case "boolean": return value === true ? [false] : [];
    case "array": {
      if (!Array.isArray(value) || value.length === 0) return [];
      const out: unknown[] = [[]];
      for (let i = 0; i < value.length; i++) out.push(value.filter((_, j) => j !== i));
      value.forEach((element, i) => {
        for (const c of shrinkCandidates(spec.element, element)) out.push(value.map((v, j) => (j === i ? c : v)));
      });
      return out;
    }
    case "tuple": {
      if (!Array.isArray(value)) return [];
      const out: unknown[] = [];
      spec.elements.forEach((e, i) => {
        for (const c of shrinkCandidates(e, value[i])) out.push(value.map((v, j) => (j === i ? c : v)));
      });
      return out;
    }
    case "object": {
      if (value === null || typeof value !== "object") return [];
      const record = value as Record<string, unknown>;
      const out: unknown[] = [];
      for (const p of spec.properties) {
        if (!(p.name in record)) continue;
        if (p.optional) {
          const { [p.name]: _removed, ...rest } = record;
          out.push(rest);
        }
        for (const c of shrinkCandidates(p.type, record[p.name])) out.push({ ...record, [p.name]: c });
      }
      return out;
    }
    case "union": {
      // Prefer the simplest member that the value does not already use.
      const simple = spec.options.filter((o) => o.kind === "null" || o.kind === "undefined" || o.kind === "literal");
      return simple.map((o) => generate(o, () => 0)).filter((v) => v !== value);
    }
    default: return [];
  }
}
