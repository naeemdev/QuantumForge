#!/usr/bin/env node
import { writeFileSync } from "node:fs";
import { analyze, DEFAULT_OPTIONS } from "./analyze.ts";

/**
 * qf-ts-analyze --root <dir> --out <file> [--max-files N]
 * Exit codes: 0 report written, 2 usage error, 3 analysis failed.
 */
function main(argv: string[]): number {
  const args = new Map<string, string>();
  for (let i = 0; i < argv.length; i += 2) {
    const key = argv[i];
    const value = argv[i + 1];
    if (!key?.startsWith("--") || value === undefined) {
      process.stderr.write("usage: qf-ts-analyze --root <dir> --out <file> [--max-files N]\n");
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
  const maxFiles = Number(args.get("max-files") ?? DEFAULT_OPTIONS.maxFiles);
  try {
    const report = analyze(root, { ...DEFAULT_OPTIONS, maxFiles });
    writeFileSync(out, JSON.stringify(report));
    process.stdout.write(
      `analysed ${report.stats.filesAnalysed} of ${report.stats.filesSeen} files: ` +
        `${report.symbols.length} symbols, ${report.imports.length} imports, ${report.calls.length} calls, ` +
        `${report.findings.length} findings in ${report.stats.durationMs} ms\n`,
    );
    return 0;
  } catch (error) {
    process.stderr.write(`analysis failed: ${error instanceof Error ? error.stack : String(error)}\n`);
    return 3;
  }
}

process.exitCode = main(process.argv.slice(2));
