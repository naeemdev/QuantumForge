/**
 * Serializable description of a parameter's type, derived from the TypeScript
 * checker during discovery. Generation and shrinking work from this alone, so
 * the fuzzing loop does not need the compiler.
 */
export type TypeSpec =
  | { kind: "number" }
  | { kind: "string" }
  | { kind: "boolean" }
  | { kind: "null" }
  | { kind: "undefined" }
  | { kind: "literal"; value: string | number | boolean }
  | { kind: "union"; options: TypeSpec[] }
  | { kind: "array"; element: TypeSpec }
  | { kind: "tuple"; elements: TypeSpec[] }
  | { kind: "object"; properties: { name: string; optional: boolean; type: TypeSpec }[] };

export interface FuzzTarget {
  /** Snapshot-relative module path. */
  path: string;
  exportName: string;
  signature: string;
  line: number;
  parameters: { name: string; optional: boolean; type: TypeSpec }[];
  async: boolean;
}

export interface SkippedTarget {
  path: string;
  exportName: string;
  line: number;
  reason: string;
}

/** Which generic oracle a failing call violated. */
export type OracleKind = "runtime-error" | "non-finite-result" | "timeout" | "crash";

export interface CallOutcome {
  kind: "ok" | "rejected" | OracleKind;
  /** For failures and rejections: error name and message, or a description of the bad result. */
  message?: string;
  errorName?: string;
  stack?: string;
  /** Encoded return value (for display). */
  result?: unknown;
}

export interface FailureReport {
  oracle: OracleKind;
  message: string;
  errorName: string | null;
  stack: string | null;
  /** Minimal failing input after shrinking, encoded (see codec.ts). */
  input: unknown[];
  /** The input originally found, before shrinking. */
  originalInput: unknown[];
  shrinkSteps: number;
  reproductions: { attempts: number; reproduced: number };
  /** A node:test file that reproduces the failure on its own. */
  reproductionTest: string;
  /** Stable identity: target + oracle + error signature (not the input). */
  fingerprint: string;
}

/** A previously failing input, replayed before random generation. */
export interface CorpusEntry {
  path: string;
  exportName: string;
  /** Encoded arguments (see codec.ts). */
  input: unknown[];
}

export interface TargetReport {
  path: string;
  exportName: string;
  signature: string;
  line: number;
  iterations: number;
  /** Corpus inputs replayed for this target before generating new ones. */
  replayed: number;
  rejectedInputs: number;
  durationMs: number;
  failures: FailureReport[];
}

export interface CampaignReport {
  schemaVersion: 1;
  engine: { id: string; version: string; oracles: { id: OracleKind; description: string }[]; limitations: string[] };
  config: { seed: number; iterationsPerTarget: number; callTimeoutMs: number; corpusEntries: number };
  targets: TargetReport[];
  skipped: SkippedTarget[];
  stats: { targetsFound: number; targetsTested: number; totalCalls: number; failures: number; durationMs: number };
}
