import { test } from "node:test";
import assert from "node:assert/strict";
import { InventoryService, MemoryStockStore, OutOfStockError } from "../src/inventory.ts";

test("reserves stock and decrements availability", async () => {
  const inventory = new InventoryService(new MemoryStockStore({ "SKU-1": 5 }));
  await inventory.reserve("SKU-1", 2);
  assert.equal(await inventory.available("SKU-1"), 3);
});

test("refuses to reserve more than is available", async () => {
  const inventory = new InventoryService(new MemoryStockStore({ "SKU-1": 1 }));
  await assert.rejects(inventory.reserve("SKU-1", 2), OutOfStockError);
});

// Regression test for D5.
test("D5: concurrent reservations never oversell the last unit", async () => {
  const inventory = new InventoryService(new MemoryStockStore({ "SKU-1": 1 }));
  const results = await Promise.allSettled([inventory.reserve("SKU-1", 1), inventory.reserve("SKU-1", 1)]);
  const succeeded = results.filter((r) => r.status === "fulfilled").length;
  assert.equal(succeeded, 1, "exactly one reservation may succeed");
  assert.equal(await inventory.available("SKU-1"), 0);
});
