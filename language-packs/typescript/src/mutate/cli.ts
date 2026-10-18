#!/usr/bin/env node
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { defaultConcurrency, runMutation } from "./run.ts";

/**
 * qf-ts-mutate --root <dir> --out <file> [--work <dir>] [--seed N] [--max-mutants N]
 *              [--concurrency N] [--budget-ms N] [--tests <glob>]...
 * Runs the project's tests against every mutant: run it only inside a sandbox.
 * Exit codes: 0 report written, 2 usage error, 3 run failed.
 */
async function main(argv: string[]): Promise<number> {
  const args = new Map<string, string>();
  const tests: string[] = [];
  for (let i = 0; i < argv.length; i += 2) {
    const key = argv[i];
    const value = argv[i + 1];
    if (!key?.startsWith("--") || value === undefined) {
      process.stderr.write("usage: qf-ts-mutate --root <dir> --out <file> [--work <dir>] [--seed N] [--max-mutants N] [--concurrency N] [--budget-ms N] [--tests <glob>]...\n");
      return 2;
    }
    if (key === "--tests") tests.push(value);
    else args.set(key.slice(2), value);
  }
  const root = args.get("root");
  const out = args.get("out");
  if (!root || !out) {
    process.stderr.write("--root and --out are required\n");
    return 2;
  }
  const number = (name: string, fallback: number, max: number) => {
    const value = Number(args.get(name) ?? fallback);
    if (!Number.isInteger(value) || value < 1 || value > max) throw new RangeError(`--${name} must be an integer between 1 and ${max}`);
    return value;
  };
  try {
    const report = await runMutation(root, {
      testPatterns: tests,
      seed: number("seed", 1, 2 ** 31),
      maxMutants: number("max-mutants", 500, 10_000),
      concurrency: number("concurrency", defaultConcurrency(), 32),
      budgetMs: number("budget-ms", 20 * 60_000, 24 * 3_600_000),
      workDir: args.get("work") ?? mkdtempSync(join(tmpdir(), "qf-mutate-")),
    });
    writeFileSync(out, JSON.stringify(report));
    const s = report.stats;
    process.stdout.write(report.notRunReason
      ? `mutants not run: ${report.notRunReason}\n`
      : `${s.total} mutants: ${s.killed} killed, ${s.survived} survived, ${s.timedOut} timed out, ${s.errors} errors; score ${s.score}% in ${s.durationMs} ms\n`);
    return 0;
  } catch (error) {
    process.stderr.write(`mutation run failed: ${error instanceof Error ? error.stack : String(error)}\n`);
    return 3;
  }
}

process.exitCode = await main(process.argv.slice(2));
