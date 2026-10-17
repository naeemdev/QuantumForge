import { test } from "node:test";
import assert from "node:assert/strict";
import { cpSync, mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { execFileSync } from "node:child_process";
import { analyze } from "../src/analyze.ts";
import { analyzeFiles } from "./helpers.ts";

const corpus = resolve(import.meta.dirname, "../../../benchmarks/bug-corpus/sample-commerce-api");

test("indexes symbols with kinds, containers, export flags and complexity", () => {
  const report = analyzeFiles({
    "src/shapes.ts": `
      export interface Shape { area(): number }
      export type Id = string;
      export class Circle implements Shape {
        constructor(private r: number) {}
        area() { return this.r > 0 ? Math.PI * this.r ** 2 : 0; }
      }
      export const scale = (s: Shape, k: number) => (k > 0 && k < 10 ? s.area() * k : 0);
      function internal(x: number) { if (x) { for (const y of [1]) { return y; } } return x ?? 0; }
    `,
  });
  const byKey = new Map(report.symbols.map((s) => [s.key, s]));
  assert.equal(byKey.get("src/shapes.ts#Shape")?.kind, "interface");
  assert.equal(byKey.get("src/shapes.ts#Id")?.kind, "type");
  assert.equal(byKey.get("src/shapes.ts#Circle")?.kind, "class");
  assert.equal(byKey.get("src/shapes.ts#Circle.area")?.complexity, 2);
  assert.equal(byKey.get("src/shapes.ts#scale")?.complexity, 3);
  assert.equal(byKey.get("src/shapes.ts#internal")?.complexity, 4);
  assert.equal(byKey.get("src/shapes.ts#internal")?.exported, false);
  assert.equal(byKey.get("src/shapes.ts#scale")?.exported, true);
});

test("resolves internal imports, external packages and cross-file calls", () => {
  const report = analyzeFiles({
    "src/a.ts": `import { b } from "./b.ts"; import express from "express"; export function a() { return b() + 1; }`,
    "src/b.ts": `export function b() { return 1; }`,
    "src/c.js": `const { b } = require("./b.ts"); module.exports = () => b();`,
  });
  const fromA = report.imports.filter((i) => i.from === "src/a.ts");
  assert.deepEqual(fromA.map((i) => [i.specifier, i.to, i.external, i.packageName]), [
    ["./b.ts", "src/b.ts", false, null],
    ["express", null, true, "express"],
  ]);
  assert.ok(report.calls.some((c) => c.from === "src/a.ts#a" && c.to === "src/b.ts#b"));
  assert.ok(report.imports.some((i) => i.from === "src/c.js" && i.to === "src/b.ts"));
});

test("reports syntax errors without failing the run", () => {
  const report = analyzeFiles({ "src/broken.ts": "export function (" });
  const file = report.files.find((f) => f.path === "src/broken.ts");
  assert.ok(file && file.diagnostics.length > 0);
});

test("fingerprints survive line shifts and change when the code changes", () => {
  const code = `export async function f(s: { w(): Promise<void> }) { s.w(); }`;
  const before = analyzeFiles({ "src/x.ts": code }).findings[0]!;
  const shifted = analyzeFiles({ "src/x.ts": "\n\n// header\n" + code }).findings[0]!;
  const changed = analyzeFiles({ "src/x.ts": code.replace("s.w()", "s.w( )").replace("w()", "write()").replace("s.w( )", "s.write()") }).findings[0]!;
  assert.equal(before.fingerprint, shifted.fingerprint);
  assert.notEqual(before.range.startLine, shifted.range.startLine);
  assert.notEqual(before.fingerprint, changed.fingerprint);
});

test("corpus: flags the statically detectable seeded defects and nothing else", () => {
  const report = analyze(corpus);
  const found = report.findings.map((f) => `${f.ruleId} ${f.path}:${f.symbolKey}`).sort();
  assert.deepEqual(found, [
    "QF-TS-002 src/orders.ts:src/orders.ts#placeOrder", // D3 unhandled async
    "QF-TS-004 src/retry.ts:src/retry.ts#withRetry", // D4 retry
    "QF-TS-006 src/inventory.ts:src/inventory.ts#InventoryService.reserve", // D5 concurrency
    "QF-TS-007 src/cart.ts:src/cart.ts#averageUnitPrice", // D6 edge case
    "QF-TS-008 src/money.ts:src/money.ts#roundCurrency", // D1 rounding
  ]);
});

test("corpus: the known fixes clear every finding", () => {
  const dir = mkdtempSync(join(tmpdir(), "qf-fixed-"));
  cpSync(corpus, dir, { recursive: true });
  execFileSync("git", ["apply", ...["D1", "D2", "D3", "D4", "D5", "D6"].map((d) => `fixes/${d}.patch`)], { cwd: dir });
  assert.deepEqual(analyze(dir).findings, []);
});
