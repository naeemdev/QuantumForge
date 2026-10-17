import { test } from "node:test";
import assert from "node:assert/strict";
import { addItem, averageUnitPrice, emptyCart, itemCount, subtotal } from "../src/cart.ts";

test("merges lines with the same SKU", () => {
  let cart = addItem(emptyCart(), "SKU-1", 2.5, 2);
  cart = addItem(cart, "SKU-1", 2.5, 3);
  assert.equal(cart.lines.length, 1);
  assert.equal(itemCount(cart), 5);
  assert.equal(subtotal(cart), 12.5);
});

test("rejects a blank SKU and negative prices", () => {
  assert.throws(() => addItem(emptyCart(), "  ", 1, 1), TypeError);
  assert.throws(() => addItem(emptyCart(), "SKU-1", -1, 1), RangeError);
});

test("averages unit price across lines", () => {
  const cart = addItem(addItem(emptyCart(), "A", 10, 1), "B", 20, 1);
  assert.equal(averageUnitPrice(cart), 15);
});

// Regression test for D2.
test("D2: rejects zero, negative and fractional quantities", () => {
  assert.throws(() => addItem(emptyCart(), "SKU-1", 5, 0), RangeError);
  assert.throws(() => addItem(emptyCart(), "SKU-1", 5, -2), RangeError);
  assert.throws(() => addItem(emptyCart(), "SKU-1", 5, 1.5), RangeError);
});

// Regression test for D6.
test("D6: average unit price of an empty cart is zero", () => {
  assert.equal(averageUnitPrice(emptyCart()), 0);
});
