import { mkdtempSync, mkdirSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { analyze } from "../src/analyze.ts";

/** Writes files into a fresh directory and analyses it. */
export function analyzeFiles(files: Record<string, string>) {
  const root = mkdtempSync(join(tmpdir(), "qf-ts-"));
  for (const [path, content] of Object.entries(files)) {
    const full = join(root, path);
    mkdirSync(dirname(full), { recursive: true });
    writeFileSync(full, content);
  }
  return analyze(root);
}

export function ruleHits(files: Record<string, string>, ruleId: string) {
  return analyzeFiles(files).findings.filter((f) => f.ruleId === ruleId);
}
