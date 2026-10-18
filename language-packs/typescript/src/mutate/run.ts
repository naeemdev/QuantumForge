import { spawn } from "node:child_process";
import { cpSync, existsSync, mkdirSync, readFileSync, readdirSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { availableParallelism } from "node:os";
import { join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { applyMutant, findMutants, OPERATORS } from "./mutants.ts";
import type { Baseline, Mutant, MutantResult, MutationReport, TestOutcome } from "./types.ts";

export const MUTATION_ENGINE_VERSION = "0.1.0";
const REPORTER = fileURLToPath(new URL("./reporter.mjs", import.meta.url));

export interface MutationOptions {
  testPatterns: string[];
  seed: number;
  maxMutants: number;
  concurrency: number;
  /** Writable scratch directory for the work copies. */
  workDir: string;
  /** Overall budget; mutants not started in time are reported as not run. */
  budgetMs: number;
}

export const DEFAULT_PATTERNS = ["**/*.test.ts", "**/*.test.js", "**/*.test.mjs"];

export const LIMITATIONS = [
  "Every mutant runs the whole test suite; there is no coverage-based test selection yet.",
  "A mutant is killed when a test that passed on the unmutated code fails. Tests that already fail cannot kill anything, so the score only reflects the passing tests.",
  "Some mutants are equivalent (they do not change behaviour) and can never be killed; a surviving mutant is a prompt to look, not proof of a missing test.",
  "A mutant that makes the suite exceed its time limit counts as detected (TIMEOUT) but is reported separately.",
];

const delay = (ms: number) => new Promise((r) => setTimeout(r, ms));

/** Runs the project's tests in dir and returns per-test outcomes. */
async function runTests(dir: string, patterns: string[], timeoutMs: number): Promise<{ outcomes: TestOutcome[]; timedOut: boolean; exitCode: number | null; durationMs: number }> {
  const started = Date.now();
  const out = join(dir, ".qf-mutation-results.jsonl");
  rmSync(out, { force: true });
  const child = spawn(process.execPath, [
    "--experimental-strip-types", "--test",
    `--test-reporter=${REPORTER}`, `--test-reporter-destination=${out}`,
    ...patterns,
  ], { cwd: dir, stdio: "ignore", detached: true, env: { ...process.env, NODE_TEST_CONTEXT: undefined } });
  let timedOut = false;
  const timer = setTimeout(() => {
    timedOut = true;
    try { process.kill(-child.pid!, "SIGKILL"); } catch { /* already gone */ }
  }, timeoutMs);
  const exitCode = await new Promise<number | null>((done) => child.on("exit", (code) => done(code)));
  clearTimeout(timer);
  // Test files may still be writing when the runner exits on a kill; give the file a moment.
  if (timedOut) await delay(50);
  const outcomes = existsSync(out)
    ? readFileSync(out, "utf8").split("\n").filter(Boolean).map((l) => JSON.parse(l) as TestOutcome)
      .map((t) => ({ ...t, file: t.file ? relative(dir, t.file.replace(/^file:\/\//, "")) : "" }))
    : [];
  return { outcomes, timedOut, exitCode, durationMs: Date.now() - started };
}

const testKey = (t: TestOutcome) => `${t.file} › ${t.name}`;

function makeWorkCopy(root: string, target: string): void {
  rmSync(target, { recursive: true, force: true });
  mkdirSync(target, { recursive: true });
  for (const entry of readdirSync(root, { withFileTypes: true })) {
    if (entry.name === ".git") continue;
    const from = join(root, entry.name);
    // Dependencies are read, never mutated: link instead of copying.
    if (entry.name === "node_modules") symlinkSync(from, join(target, entry.name));
    else cpSync(from, join(target, entry.name), { recursive: true, verbatimSymlinks: true });
  }
}

/** Deterministic sample when there are more mutants than the limit. */
function sample<T extends { id: string }>(items: T[], max: number, seed: number): T[] {
  if (items.length <= max) return items;
  const score = (id: string) => {
    let h = seed >>> 0;
    for (let i = 0; i < id.length; i++) h = Math.imul(h ^ id.charCodeAt(i), 2654435761) >>> 0;
    return h;
  };
  const chosen = new Set([...items].sort((a, b) => score(a.id) - score(b.id)).slice(0, max).map((m) => m.id));
  return items.filter((m) => chosen.has(m.id));
}

export async function runMutation(rootDir: string, options: MutationOptions): Promise<MutationReport> {
  const started = Date.now();
  const root = resolve(rootDir);
  const patterns = options.testPatterns.length ? options.testPatterns : DEFAULT_PATTERNS;
  const found = findMutants(root);
  const mutants = sample(found, options.maxMutants, options.seed);
  const workers = Math.max(1, Math.min(options.concurrency, mutants.length || 1));
  for (let i = 0; i < workers; i++) makeWorkCopy(root, join(options.workDir, `w${i}`));

  const base = await runTests(join(options.workDir, "w0"), patterns, Math.min(options.budgetMs, 10 * 60_000));
  const failing = base.outcomes.filter((t) => !t.passed).map(testKey);
  const passing = new Set(base.outcomes.filter((t) => t.passed).map(testKey));
  const baseline: Baseline = { tests: base.outcomes.length, passed: passing.size, failed: failing.length, durationMs: base.durationMs, failingTests: failing };

  let notRunReason: string | null = null;
  if (base.timedOut) notRunReason = "The unmutated test suite did not finish within the time budget.";
  else if (base.outcomes.length === 0) notRunReason = "No tests ran for the configured patterns.";
  else if (passing.size === 0) notRunReason = "No test passes on the unmutated code, so no mutant could be detected.";

  const results: MutantResult[] = [];
  if (!notRunReason) {
    const perMutantTimeout = Math.max(5_000, base.durationMs * 3 + 2_000);
    const queue = [...mutants];
    const runOne = async (dir: string, m: Mutant): Promise<MutantResult> => {
      const file = join(dir, m.path);
      const original = readFileSync(file, "utf8");
      writeFileSync(file, applyMutant(original, m));
      try {
        const r = await runTests(dir, patterns, perMutantTimeout);
        const { start: _s, end: _e, ...rest } = m;
        if (r.timedOut) return { ...rest, status: "TIMEOUT", killedBy: null, durationMs: r.durationMs };
        const results = new Map(r.outcomes.map((t) => [testKey(t), t.passed]));
        const killer = [...passing].find((k) => results.get(k) !== true);
        return { ...rest, status: killer ? "KILLED" : "SURVIVED", killedBy: killer ?? null, durationMs: r.durationMs };
      } finally {
        writeFileSync(file, original);
      }
    };
    await Promise.all(Array.from({ length: workers }, async (_, i) => {
      const dir = join(options.workDir, `w${i}`);
      for (let m = queue.shift(); m; m = queue.shift()) {
        if (Date.now() - started > options.budgetMs) {
          const { start: _s, end: _e, ...rest } = m;
          results.push({ ...rest, status: "ERROR", killedBy: "not run: time budget exhausted", durationMs: 0 });
          continue;
        }
        try {
          results.push(await runOne(dir, m));
        } catch (error) {
          const { start: _s, end: _e, ...rest } = m;
          results.push({ ...rest, status: "ERROR", killedBy: `runner error: ${error instanceof Error ? error.message : String(error)}`.slice(0, 300), durationMs: 0 });
        }
      }
    }));
  }
  rmSync(options.workDir, { recursive: true, force: true });

  results.sort((a, b) => a.path.localeCompare(b.path) || a.line - b.line || a.column - b.column || a.replacement.localeCompare(b.replacement));
  const count = (s: MutantResult["status"]) => results.filter((r) => r.status === s).length;
  const killed = count("KILLED");
  const timedOut = count("TIMEOUT");
  const errors = count("ERROR");
  const scored = results.length - errors;
  return {
    schemaVersion: 1,
    engine: { id: "typescript-mutation", version: MUTATION_ENGINE_VERSION, operators: OPERATORS, limitations: LIMITATIONS },
    config: { seed: options.seed, maxMutants: options.maxMutants, testPatterns: patterns, concurrency: workers },
    baseline,
    notRunReason,
    mutantsFound: found.length,
    mutants: results,
    stats: {
      total: results.length, killed, survived: count("SURVIVED"), timedOut, errors,
      score: scored > 0 ? Math.round(((killed + timedOut) / scored) * 1000) / 10 : null,
      durationMs: Date.now() - started,
    },
  };
}

export const defaultConcurrency = () => Math.max(1, Math.min(4, availableParallelism() - 1));
