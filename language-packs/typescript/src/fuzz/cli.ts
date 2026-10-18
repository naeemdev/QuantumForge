#!/usr/bin/env node
import { readFileSync, writeFileSync } from "node:fs";
import { DEFAULT_CAMPAIGN, runCampaign } from "./campaign.ts";

/**
 * qf-ts-fuzz --root <dir> --out <file> [--seed N] [--iterations N] [--call-timeout-ms N] [--budget-ms N] [--corpus <file>]
 * Executes project code: run it only inside a sandbox.
 * Exit codes: 0 report written (whether or not failures were found), 2 usage error, 3 campaign failed.
 */
async function main(argv: string[]): Promise<number> {
  const args = new Map<string, string>();
  for (let i = 0; i < argv.length; i += 2) {
    const key = argv[i];
    const value = argv[i + 1];
    if (!key?.startsWith("--") || value === undefined) {
      process.stderr.write("usage: qf-ts-fuzz --root <dir> --out <file> [--seed N] [--iterations N] [--call-timeout-ms N] [--budget-ms N] [--corpus <file>]\n");
      return 2;
    }
    args.set(key.slice(2), value);
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
    const corpusFile = args.get("corpus");
    const corpus = corpusFile ? JSON.parse(readFileSync(corpusFile, "utf8")) : [];
    if (!Array.isArray(corpus)) throw new TypeError("--corpus must contain a JSON array of { path, exportName, input }");
    const report = await runCampaign(root, {
      ...DEFAULT_CAMPAIGN,
      seed: number("seed", DEFAULT_CAMPAIGN.seed, 2 ** 31),
      iterationsPerTarget: number("iterations", DEFAULT_CAMPAIGN.iterationsPerTarget, 100_000),
      callTimeoutMs: number("call-timeout-ms", DEFAULT_CAMPAIGN.callTimeoutMs, 60_000),
      campaignBudgetMs: number("budget-ms", DEFAULT_CAMPAIGN.campaignBudgetMs, 24 * 3_600_000),
      corpus,
    });
    writeFileSync(out, JSON.stringify(report));
    process.stdout.write(
      `fuzzed ${report.stats.targetsTested} functions (${report.skipped.length} skipped) with ${report.stats.totalCalls} calls: ` +
        `${report.stats.failures} failures in ${report.stats.durationMs} ms\n`,
    );
    return 0;
  } catch (error) {
    process.stderr.write(`campaign failed: ${error instanceof Error ? error.stack : String(error)}\n`);
    return 3;
  }
}

process.exitCode = await main(process.argv.slice(2));
