export type MutationOperator = "comparison" | "equality" | "arithmetic" | "logical" | "negation" | "condition" | "constant" | "call-removal";

export interface Mutant {
  /** Stable within a snapshot: path, offset and replacement. */
  id: string;
  path: string;
  line: number;
  column: number;
  operator: MutationOperator;
  /** Source text replaced, and what replaces it. */
  original: string;
  replacement: string;
  start: number;
  end: number;
  /** Key of the enclosing function or method, like the analyzer's symbol keys. */
  symbolKey: string | null;
}

export type MutantStatus = "KILLED" | "SURVIVED" | "TIMEOUT" | "ERROR";

export interface MutantResult extends Omit<Mutant, "start" | "end"> {
  status: MutantStatus;
  /** First baseline-passing test that failed, for killed mutants. */
  killedBy: string | null;
  durationMs: number;
}

export interface TestOutcome {
  file: string;
  name: string;
  passed: boolean;
}

export interface Baseline {
  tests: number;
  passed: number;
  failed: number;
  durationMs: number;
  /** Tests already failing without any mutation; they cannot kill a mutant. */
  failingTests: string[];
}

export interface MutationReport {
  schemaVersion: 1;
  engine: { id: string; version: string; operators: { id: MutationOperator; description: string }[]; limitations: string[] };
  config: { seed: number; maxMutants: number; testPatterns: string[]; concurrency: number };
  baseline: Baseline;
  /** Null when mutants were run; otherwise why they were not (e.g. no passing tests). */
  notRunReason: string | null;
  mutantsFound: number;
  mutants: MutantResult[];
  stats: { total: number; killed: number; survived: number; timedOut: number; errors: number; score: number | null; durationMs: number };
}
