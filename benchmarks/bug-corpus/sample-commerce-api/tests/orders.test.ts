import { test } from "node:test";
import assert from "node:assert/strict";
import { addItem, emptyCart } from "../src/cart.ts";
import { InventoryService, MemoryStockStore } from "../src/inventory.ts";
import { type AuditLog, placeOrder } from "../src/orders.ts";

test("prices an order and writes an audit entry", async () => {
  const entries: unknown[] = [];
  const audit: AuditLog = { write: async (entry) => { entries.push(entry); } };
  const inventory = new InventoryService(new MemoryStockStore({ A: 10 }));
  const order = await placeOrder(addItem(emptyCart(), "A", 20, 2), inventory, audit, 10);
  assert.equal(order.subtotal, 40);
  assert.equal(order.tax, 4);
  assert.equal(order.total, 44);
  assert.equal(entries.length, 1);
});

// Regression test for D3.
test("D3: a failed audit write fails the order", async () => {
  const audit: AuditLog = {
    write: () => new Promise((_, reject) => setTimeout(() => reject(new Error("audit store unavailable")), 5)),
  };
  const inventory = new InventoryService(new MemoryStockStore({ A: 10 }));
  await assert.rejects(placeOrder(addItem(emptyCart(), "A", 20, 1), inventory, audit, 10), /audit store unavailable/);
});
