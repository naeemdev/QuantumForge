import { test } from "node:test";
import assert from "node:assert/strict";
import { formatUsd, roundCurrency, salesTax } from "../src/money.ts";

test("formats dollars with grouping and two decimals", () => {
  assert.equal(formatUsd(1234567.5), "$1,234,567.50");
  assert.equal(formatUsd(-3), "-$3.00");
});

test("computes sales tax for a typical subtotal", () => {
  assert.equal(salesTax(100, 8.25), 8.25);
});

test("rejects tax rates outside 0-100", () => {
  assert.throws(() => salesTax(10, -1), RangeError);
  assert.throws(() => salesTax(10, 101), RangeError);
});

// Regression test for D1.
test("D1: rounds half-cent amounts up", () => {
  assert.equal(roundCurrency(1.005), 1.01);
  assert.equal(roundCurrency(2.675), 2.68);
});
