import { test } from "node:test";
import assert from "node:assert/strict";
import { cpSync, mkdtempSync, mkdirSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { execFileSync, spawnSync } from "node:child_process";
import { decode, encode } from "../src/fuzz/codec.ts";
import { discoverTargets } from "../src/fuzz/discover.ts";
import { generate, prng, shrinkCandidates } from "../src/fuzz/generate.ts";
import { DEFAULT_CAMPAIGN, runCampaign } from "../src/fuzz/campaign.ts";
import type { TypeSpec } from "../src/fuzz/types.ts";

const corpus = resolve(import.meta.dirname, "../../../benchmarks/bug-corpus/sample-commerce-api");
const quick = { ...DEFAULT_CAMPAIGN, iterationsPerTarget: 150, callTimeoutMs: 500 };

function project(files: Record<string, string>): string {
  const root = mkdtempSync(join(tmpdir(), "qf-fuzz-"));
  for (const [path, content] of Object.entries(files)) {
    mkdirSync(dirname(join(root, path)), { recursive: true });
    writeFileSync(join(root, path), content);
  }
  return root;
}

test("codec round-trips the values JSON cannot carry", () => {
  const value = [NaN, Infinity, -Infinity, -0, undefined, { a: undefined, b: [1, "x"] }];
  const back = decode(JSON.parse(JSON.stringify(encode(value)))) as unknown[];
  assert.ok(Number.isNaN(back[0]));
  assert.equal(back[1], Infinity);
  assert.equal(back[2], -Infinity);
  assert.ok(Object.is(back[3], -0));
  assert.equal(back[4], undefined);
  assert.deepEqual(back[5], { a: undefined, b: [1, "x"] });
  assert.ok("a" in (back[5] as object));
});

test("generation is deterministic for a seed and respects the declared shape", () => {
  const spec: TypeSpec = { kind: "object", properties: [
    { name: "id", optional: false, type: { kind: "string" } },
    { name: "tags", optional: true, type: { kind: "array", element: { kind: "union", options: [{ kind: "literal", value: "a" }, { kind: "null" }] } } },
  ] };
  const a = Array.from({ length: 50 }, ((r) => () => generate(spec, r))(prng(7)));
  const b = Array.from({ length: 50 }, ((r) => () => generate(spec, r))(prng(7)));
  assert.deepEqual(a, b);
  for (const v of a as { id: unknown; tags?: unknown[] }[]) {
    assert.equal(typeof v.id, "string");
    for (const t of v.tags ?? []) assert.ok(t === "a" || t === null);
  }
});

test("shrink candidates are strictly simpler", () => {
  assert.deepEqual(shrinkCandidates({ kind: "number" }, 0), []);
  assert.ok(shrinkCandidates({ kind: "number" }, 734.25).every((n) => Math.abs(n as number) < 734.25 || Number.isInteger(n)));
  assert.deepEqual(shrinkCandidates({ kind: "array", element: { kind: "number" } }, [5])[0], []);
  assert.deepEqual(shrinkCandidates({ kind: "string" }, ""), []);
});

test("discovery fuzzes plain functions and explains every skip", () => {
  const root = project({
    "src/lib.ts": `
      export function area(w: number, h: number): number { return w * h; }
      export const label = (p: { name: string; age?: number }, loud?: boolean) => p.name;
      export function apply(f: (x: number) => number) { return f(1); }
      export function first<T>(xs: T[]): T | undefined { return xs[0]; }
      export function when(d: Date) { return d.getTime(); }
      export function loose(x: any) { return x; }
      export class Box { constructor(public v: number) {} }
      function hidden(x: number) { return x; }
    `,
    "src/lib.test.ts": `export function helper(x: number) { return x; }`,
  });
  const { targets, skipped } = discoverTargets(root);
  assert.deepEqual(targets.map((t) => t.exportName), ["area", "label"]);
  const label = targets.find((t) => t.exportName === "label")!;
  assert.equal(label.parameters[1]!.optional, true);
  assert.deepEqual((label.parameters[0]!.type as { properties: { name: string; optional: boolean }[] }).properties.map((p) => [p.name, p.optional]), [["name", false], ["age", true]]);
  const reasons = Object.fromEntries(skipped.map((s) => [s.exportName, s.reason]));
  assert.match(reasons.apply!, /function type/);
  assert.match(reasons.first!, /generic/);
  assert.match(reasons.when!, /built-in type 'Date'/);
  assert.match(reasons.loose!, /no shape/);
  assert.match(reasons.Box!, /class/);
  assert.equal(reasons.helper, undefined, "test files are not fuzzed");
});

test("finds, shrinks and reproduces engine errors and hangs; ignores deliberate validation", async () => {
  const root = project({
    "src/m.ts": `
      export interface Order { items: { price: number; tags?: string[] }[] }
      export function firstTag(order: Order): string {
        return order.items[0].tags![0].toUpperCase();
      }
      export function validated(qty: number): number {
        if (!Number.isInteger(qty) || qty <= 0) throw new TypeError("qty must be a positive integer");
        return qty * 2;
      }
      export function spin(n: number): number {
        while (n > 500) {}
        return n;
      }
      export const ok = (s: string) => s.length;
    `,
  });
  const report = await runCampaign(root, quick);
  const byName = Object.fromEntries(report.targets.map((t) => [t.exportName, t]));

  // Three distinct defects in one expression, each shrunk to its own minimal order.
  const minimal: Record<string, unknown> = {
    "(reading 'tags')": [{ items: [] }],
    "(reading '0')": [{ items: [{ price: 0 }] }],
    "(reading 'toUpperCase')": [{ items: [{ price: 0, tags: [] }] }],
  };
  const crashes = byName.firstTag!.failures;
  assert.ok(crashes.length >= 2);
  for (const crash of crashes) {
    assert.equal(crash.oracle, "runtime-error");
    const key = Object.keys(minimal).find((k) => crash.message.endsWith(k));
    assert.ok(key, crash.message);
    assert.deepEqual(decode(crash.input), minimal[key], crash.message);
    assert.deepEqual(crash.reproductions, { attempts: 3, reproduced: 3 });
  }

  assert.deepEqual(byName.validated!.failures, []);
  assert.ok(byName.validated!.rejectedInputs > 0);

  assert.equal(byName.spin!.failures[0]?.oracle, "timeout");
  assert.deepEqual(byName.ok!.failures, []);
  assert.equal(report.stats.failures, crashes.length + 1);
});

test("a function that exhausts its heap is reported as a crash, not a hang", async () => {
  const root = project({
    "src/grow.ts": `
      export function grow(n: number): number[][] {
        const out: number[][] = [];
        for (;;) out.push(new Array(1e6).fill(n));
      }
    `,
  });
  const report = await runCampaign(root, { ...quick, callTimeoutMs: 20_000, reproductionAttempts: 1 });
  const [failure] = report.targets[0]!.failures;
  assert.equal(failure?.oracle, "crash");
  assert.match(failure!.message, /memory/);
});

test("campaigns are reproducible from their seed", async () => {
  const root = project({ "src/a.ts": `export const half = (xs: number[]) => xs.reduce((a, b) => a + b) / 2;` });
  const strip = (r: Awaited<ReturnType<typeof runCampaign>>) => r.targets.map((t) => ({ ...t, durationMs: 0 }));
  const a = await runCampaign(root, quick);
  const b = await runCampaign(root, quick);
  assert.deepEqual(strip(a), strip(b));
  assert.match(a.targets[0]!.failures[0]!.message, /Reduce of empty array/);
});

test("corpus: finds D6 without being told where it is, and nothing once the fixes are applied", async () => {
  const report = await runCampaign(corpus, quick);
  const failures = report.targets.flatMap((t) => t.failures.map((f) => ({ ...f, target: `${t.path}#${t.exportName}` })));
  assert.equal(failures.length, 1);
  const [d6] = failures;
  assert.equal(d6!.target, "src/cart.ts#averageUnitPrice");
  assert.equal(d6!.oracle, "non-finite-result");
  assert.deepEqual(decode(d6!.input), [{ lines: [] }]);
  assert.equal(d6!.reproductions.reproduced, 3);
  assert.ok(report.skipped.some((s) => s.exportName === "withRetry"));

  // The generated regression test fails on the shipped code and passes on the fix.
  const work = mkdtempSync(join(tmpdir(), "qf-fuzz-corpus-"));
  cpSync(corpus, work, { recursive: true });
  writeFileSync(join(work, "qf-repro.test.ts"), d6!.reproductionTest);
  const run = () => spawnSync(process.execPath, ["--experimental-strip-types", "--test", "qf-repro.test.ts"], {
    cwd: work,
    encoding: "utf8",
    // Run as an independent test process, not as a child of this test runner.
    env: { ...process.env, NODE_TEST_CONTEXT: undefined },
  });
  assert.notEqual(run().status, 0);
  for (const patch of ["D1", "D2", "D3", "D4", "D5", "D6"]) {
    execFileSync("patch", ["-s", "-p1", "-i", join(work, "fixes", `${patch}.patch`)], { cwd: work });
  }
  assert.equal(run().status, 0);

  const fixed = await runCampaign(work, quick);
  assert.equal(fixed.stats.failures, 0);
});
