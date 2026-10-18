import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, mkdirSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { findMutants } from "../src/mutate/mutants.ts";
import { runMutation, type MutationOptions } from "../src/mutate/run.ts";

const corpus = resolve(import.meta.dirname, "../../../benchmarks/bug-corpus/sample-commerce-api");

function project(files: Record<string, string>): string {
  const root = mkdtempSync(join(tmpdir(), "qf-mutate-"));
  for (const [path, content] of Object.entries(files)) {
    mkdirSync(dirname(join(root, path)), { recursive: true });
    writeFileSync(join(root, path), content);
  }
  return root;
}

const options = (): MutationOptions => ({
  testPatterns: [], seed: 1, maxMutants: 500, concurrency: 2, budgetMs: 120_000,
  workDir: mkdtempSync(join(tmpdir(), "qf-mutate-work-")),
});

test("generates mutants for expressions only, and never for string concatenation, types or tests", () => {
  const root = project({
    "src/a.ts": `
      export type Small = 1 | 2;
      export function clamp(x: number, lo: number, hi: number): number {
        if (x < lo) return lo;
        return x > hi ? hi : x;
      }
      export const label = (name: string, n: number) => "item " + name + n;
      export function count(xs: number[]) { let total = 0; for (const x of xs) total += x * 2; log(total); return !xs.length || total; }
      function log(_: unknown) {}
    `,
    "src/a.test.ts": `if (1 < 2) {}`,
  });
  const mutants = findMutants(root);
  assert.ok(mutants.every((m) => m.path === "src/a.ts"), "test files are not mutated");
  const show = (m: (typeof mutants)[number]) => `${m.operator}:${m.original}→${m.replacement}`;
  const all = mutants.map(show);
  for (const expected of ["comparison:<→<=", "comparison:>→>=", "condition:x < lo→true", "condition:x > hi→false", "arithmetic:+=→-=", "arithmetic:*→/", "constant:0→1", "constant:2→3", "logical:||→&&", "negation:!→", "call-removal:log(total);→;"]) {
    assert.ok(all.includes(expected), `missing ${expected} in ${all.join(", ")}`);
  }
  assert.ok(!mutants.some((m) => m.symbolKey === "src/a.ts#label"), "string concatenation is not an arithmetic mutant");
  assert.ok(!mutants.some((m) => m.line === 2), "type literals are not mutated");
  assert.equal(mutants.find((m) => m.original === "<")?.symbolKey, "src/a.ts#clamp");
  assert.equal(new Set(mutants.map((m) => m.id)).size, mutants.length);
});

test("kills mutants the tests detect, reports survivors, and ignores tests that already fail", async () => {
  const root = project({
    "src/discount.ts": `
      export function discount(total: number): number {
        if (total >= 100) return total * 0.9;
        return total;
      }
    `,
    "tests/discount.test.ts": `
      import { test } from "node:test";
      import assert from "node:assert/strict";
      import { discount } from "../src/discount.ts";
      test("large orders get 10% off", () => assert.equal(discount(200), 180));
      test("small orders pay full price", () => assert.equal(discount(50), 50));
      test("already broken", () => assert.equal(discount(1), 2));
    `,
  });
  const report = await runMutation(root, options());
  assert.equal(report.notRunReason, null);
  assert.deepEqual([report.baseline.tests, report.baseline.passed, report.baseline.failed], [3, 2, 1]);
  assert.deepEqual(report.baseline.failingTests, ["tests/discount.test.ts › already broken"]);
  const byChange = Object.fromEntries(report.mutants.map((m) => [`${m.original}→${m.replacement}`, m]));
  // Nothing tests exactly 100, so the boundary mutant survives.
  assert.equal(byChange[">=→>"]?.status, "SURVIVED");
  assert.equal(byChange["*→/"]?.status, "KILLED");
  assert.equal(byChange["*→/"]?.killedBy, "tests/discount.test.ts › large orders get 10% off");
  assert.equal(byChange["total >= 100→true"]?.status, "KILLED");
  assert.equal(report.stats.total, report.mutants.length);
  assert.equal(report.stats.killed + report.stats.survived + report.stats.timedOut + report.stats.errors, report.stats.total);
  assert.equal(report.stats.score, Math.round(((report.stats.killed + report.stats.timedOut) / report.stats.total) * 1000) / 10);
});

test("a mutant that makes the suite hang is stopped and counted as a timeout", async () => {
  const root = project({
    "src/countdown.ts": `export function countdown(n: number): number { let steps = 0; while (n > 0) { n -= 1; steps++; } return steps; }`,
    "tests/countdown.test.ts": `
      import { test } from "node:test";
      import assert from "node:assert/strict";
      import { countdown } from "../src/countdown.ts";
      test("counts down", () => assert.equal(countdown(3), 3));
    `,
  });
  const report = await runMutation(root, options());
  assert.equal(report.mutants.find((m) => m.original === "-=")?.status, "TIMEOUT");
});

test("does not run mutants without a passing test to detect them", async () => {
  const none = await runMutation(project({ "src/a.ts": `export const f = (x: number) => x + 1;` }), options());
  assert.equal(none.notRunReason, "No tests ran for the configured patterns.");
  assert.equal(none.mutants.length, 0);
  assert.equal(none.stats.score, null);
  assert.ok(none.mutantsFound > 0);
});

test("the mutant limit samples deterministically", async () => {
  const root = project({
    "src/a.ts": `export const f = (a: number, b: number) => (a < b ? a + 1 : b * 2) - 3;`,
    "tests/a.test.ts": `import { test } from "node:test"; import assert from "node:assert/strict"; import { f } from "../src/a.ts"; test("f", () => assert.equal(f(1, 2), -1));`,
  });
  const ids = async (seed: number) => (await runMutation(root, { ...options(), maxMutants: 3, seed })).mutants.map((m) => m.id);
  const first = await ids(1);
  assert.equal(first.length, 3);
  assert.deepEqual(await ids(1), first);
  assert.ok((await runMutation(root, { ...options(), maxMutants: 3 })).mutantsFound > 3);
});

test("corpus: the suite's blind spots show up as survivors", async () => {
  const report = await runMutation(corpus, { ...options(), concurrency: 4 });
  assert.deepEqual([report.baseline.passed, report.baseline.failed], [12, 6]);
  assert.equal(report.mutantsFound, report.mutants.length);
  assert.equal(report.stats.errors, 0);
  const survivor = (path: string, original: string) => report.mutants.find((m) => m.path === path && m.original === original && m.status === "SURVIVED");
  // placeOrder never checks that stock was reserved.
  assert.ok(survivor("src/orders.ts", "await inventory.reserve(line.sku, line.quantity);"));
  // Merging lines is only tested with a single SKU.
  assert.ok(report.mutants.some((m) => m.path === "src/cart.ts" && m.original === "line.sku === sku" && m.replacement === "true" && m.status === "SURVIVED"));
  // The D1 test already fails, so it cannot catch a change to the rounding precision.
  assert.ok(survivor("src/money.ts", "2"));
  assert.ok(report.stats.killed > report.stats.survived);
});
