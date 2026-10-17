import { test } from "node:test";
import assert from "node:assert/strict";
import { ruleHits } from "./helpers.ts";

const hits = (code: string, rule: string) => ruleHits({ "src/a.ts": code }, rule).length;

test("QF-TS-001 flags eval, Function and string timers only", () => {
  assert.equal(hits(`export function f(s: string) { return eval(s); }`, "QF-TS-001"), 1);
  assert.equal(hits(`export const g = new Function("a", "return a");`, "QF-TS-001"), 1);
  assert.equal(hits(`setTimeout("tick()", 10);`, "QF-TS-001"), 1);
  assert.equal(hits(`setTimeout(() => tick(), 10); function tick() {}`, "QF-TS-001"), 0);
});

test("QF-TS-002 flags unawaited promises but not awaited, returned, voided or caught ones", () => {
  const prelude = `async function save(): Promise<void> {}\n`;
  assert.equal(hits(prelude + `export async function f() { save(); }`, "QF-TS-002"), 1);
  assert.equal(hits(prelude + `export function f() { save().then(() => 1); }`, "QF-TS-002"), 1);
  assert.equal(hits(prelude + `export async function f() { await save(); }`, "QF-TS-002"), 0);
  assert.equal(hits(prelude + `export function f() { return save(); }`, "QF-TS-002"), 0);
  assert.equal(hits(prelude + `export function f() { void save(); }`, "QF-TS-002"), 0);
  assert.equal(hits(prelude + `export function f() { save().catch(() => {}); }`, "QF-TS-002"), 0);
  assert.equal(hits(`function sync(): number { return 1; }\nexport function f() { sync(); }`, "QF-TS-002"), 0);
});

test("QF-TS-003 flags empty catch blocks", () => {
  assert.equal(hits(`try { JSON.parse("x"); } catch {}`, "QF-TS-003"), 1);
  assert.equal(hits(`try { JSON.parse("x"); } catch (e) { /* ignored */ }`, "QF-TS-003"), 1);
  assert.equal(hits(`try { JSON.parse("x"); } catch (e) { console.error(e); }`, "QF-TS-003"), 0);
});

test("QF-TS-004 flags retry loops that never classify the error", () => {
  const bad = `
    export async function retry(fn: () => Promise<number>, n: number) {
      for (let i = 0; ; i++) {
        try { return await fn(); }
        catch (error) { if (i >= n) throw error; }
      }
    }`;
  const good = `
    function transient(e: unknown) { return e instanceof Error && e.message === "timeout"; }
    export async function retry(fn: () => Promise<number>, n: number) {
      for (let i = 0; ; i++) {
        try { return await fn(); }
        catch (error) { if (i >= n || !transient(error)) throw error; }
      }
    }`;
  const rethrowOnly = `
    export async function once(fn: () => Promise<number>) {
      while (true) {
        try { return await fn(); } catch (error) { throw error; }
      }
    }`;
  assert.equal(hits(bad, "QF-TS-004"), 1);
  assert.equal(hits(good, "QF-TS-004"), 0);
  assert.equal(hits(rethrowOnly, "QF-TS-004"), 0);
});

test("QF-TS-005 flags interpolated SQL but not parameterised queries", () => {
  const db = `declare const db: { query(sql: string, params?: unknown[]): Promise<unknown> };\n`;
  assert.equal(hits(db + "export function f(id: string) { return db.query(`SELECT * FROM users WHERE id = ${id}`); }", "QF-TS-005"), 1);
  assert.equal(hits(db + `export function f(id: string) { return db.query("DELETE FROM users WHERE id = '" + id + "'"); }`, "QF-TS-005"), 1);
  assert.equal(hits(db + `export function f(id: string) { return db.query("SELECT * FROM users WHERE id = $1", [id]); }`, "QF-TS-005"), 0);
  assert.equal(hits(db + "export function f(t: string) { return db.query(`hello ${t}`); }", "QF-TS-005"), 0);
});

test("QF-TS-006 flags read-modify-write across awaits unless run under a lock", () => {
  const store = `declare const store: { get(k: string): Promise<number>; set(k: string, v: number): Promise<void> };\n`;
  const bad = store + `export async function take(k: string) { const n = await store.get(k); await store.set(k, n - 1); }`;
  const locked = store + `declare function withLock<T>(k: string, f: () => Promise<T>): Promise<T>;
    export async function take(k: string) { await withLock(k, async () => { const n = await store.get(k); await store.set(k, n - 1); }); }`;
  const differentKey = store + `export async function copy(a: string, b: string) { const n = await store.get(a); await store.set(b, n); }`;
  assert.equal(hits(bad, "QF-TS-006"), 1);
  assert.equal(hits(locked, "QF-TS-006"), 0);
  assert.equal(hits(differentKey, "QF-TS-006"), 0);
});

test("QF-TS-007 flags division by an unchecked count", () => {
  assert.equal(hits(`export function avg(xs: number[]) { return xs.reduce((a, b) => a + b, 0) / xs.length; }`, "QF-TS-007"), 1);
  assert.equal(hits(`export function avg(xs: number[]) { if (xs.length === 0) return 0; return xs.reduce((a, b) => a + b, 0) / xs.length; }`, "QF-TS-007"), 0);
  assert.equal(hits(`export function half(x: number) { return x / 2; }`, "QF-TS-007"), 0);
});

test("QF-TS-008 flags toFixed round-trips but not formatting", () => {
  assert.equal(hits(`export const r = (x: number) => Number(x.toFixed(2));`, "QF-TS-008"), 1);
  assert.equal(hits(`export const r = (x: number) => +x.toFixed(2);`, "QF-TS-008"), 1);
  assert.equal(hits(`export const label = (x: number) => "$" + x.toFixed(2);`, "QF-TS-008"), 0);
});

test("QF-TS-009 flags disabled TLS verification", () => {
  assert.equal(hits(`export const agent = { rejectUnauthorized: false };`, "QF-TS-009"), 1);
  assert.equal(hits(`process.env.NODE_TLS_REJECT_UNAUTHORIZED = "0";`, "QF-TS-009"), 1);
  assert.equal(hits(`export const agent = { rejectUnauthorized: true };`, "QF-TS-009"), 0);
});
