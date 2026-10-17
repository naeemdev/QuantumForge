# Sample Commerce API — seeded defect catalogue

This library was written for QuantumForge's test corpus. It contains six
deliberate defects. Each one has a regression test that fails against the
shipped code and a patch in `fixes/` that makes it pass. Every other test passes
on the shipped code.

Verified with Node.js 22.22:

| Tree                         | Tests | Pass | Fail |
|------------------------------|-------|------|------|
| as shipped                   | 18    | 12   | 6    |
| with all `fixes/*.patch`     | 18    | 18   | 0    |

Run: `node --experimental-strip-types --test "tests/**/*.test.ts"` (no dependencies to install).

---

## D1 — Incorrect rounding

- **Where:** `src/money.ts`, `roundCurrency`
- **Defect:** `Number(amount.toFixed(2))` rounds the binary floating-point value.
  `1.005` is stored as `1.00499999…` and rounds down.
- **Reproduction:** `roundCurrency(1.005)`, `roundCurrency(2.675)`
- **Expected:** `1.01`, `2.68` (half away from zero on the decimal value)
- **Actual:** `1`, `2.67`
- **Regression test:** `tests/money.test.ts` › `D1: rounds half-cent amounts up`
- **Known fix:** `fixes/D1.patch`. Shift by two decimal digits in exponent
  notation (`Number("1.005e2") === 100.5`), then `Math.round`.

## D2 — Missing input validation

- **Where:** `src/cart.ts`, `addItem`
- **Defect:** `quantity` is never validated.
- **Reproduction:** `addItem(emptyCart(), "SKU-1", 5, -2)` (also `0` and `1.5`)
- **Expected:** `RangeError`
- **Actual:** the line is added and the cart subtotal becomes `-10`
- **Regression test:** `tests/cart.test.ts` › `D2: rejects zero, negative and fractional quantities`
- **Known fix:** `fixes/D2.patch`. Require a positive integer.

## D3 — Unhandled asynchronous operation

- **Where:** `src/orders.ts`, `placeOrder`
- **Defect:** `audit.write(...)` is called without `await`.
- **Reproduction:** place an order with an audit log whose `write` rejects.
- **Expected:** `placeOrder` rejects with the audit error.
- **Actual:** `placeOrder` resolves; the rejection surfaces later as an unhandled
  rejection, and the order has no audit record.
- **Regression test:** `tests/orders.test.ts` › `D3: a failed audit write fails the order`
- **Known fix:** `fixes/D3.patch`. `await` the write.

## D4 — Incorrect retry behaviour

- **Where:** `src/retry.ts`, `withRetry`
- **Defect:** every error is retried, including permanent 4xx client errors.
- **Reproduction:** `withRetry(() => { throw new HttpError(422, "...") }, { retries: 3, ... })`
- **Expected:** one call, then the error is thrown.
- **Actual:** four calls.
- **Regression test:** `tests/retry.test.ts` › `D4: does not retry 4xx client errors`
- **Known fix:** `fixes/D4.patch`. Retry only 5xx, 408 and 429 (and non-HTTP errors).

## D5 — Concurrency-sensitive update

- **Where:** `src/inventory.ts`, `InventoryService.reserve`
- **Defect:** read-check-write spans two `await`s with no mutual exclusion.
- **Reproduction:** stock `{ "SKU-1": 1 }`, run two `reserve("SKU-1", 1)` concurrently.
- **Expected:** exactly one succeeds; the other throws `OutOfStockError`.
- **Actual:** both succeed (the last unit is sold twice).
- **Regression test:** `tests/inventory.test.ts` › `D5: concurrent reservations never oversell the last unit`
- **Known fix:** `fixes/D5.patch`. Serialise operations per SKU with a promise-chain lock.
  A real database implementation would use `UPDATE … WHERE stock >= :qty` or row locking instead.

## D6 — Missing edge-case handling

- **Where:** `src/cart.ts`, `averageUnitPrice`
- **Defect:** an empty cart divides `0 / 0`.
- **Reproduction:** `averageUnitPrice(emptyCart())`
- **Expected:** `0`
- **Actual:** `NaN`
- **Regression test:** `tests/cart.test.ts` › `D6: average unit price of an empty cart is zero`
- **Known fix:** `fixes/D6.patch`. Return `0` when the item count is zero.

---

`D2.patch` and `D6.patch` both touch `src/cart.ts` in separate hunks; they apply
independently and in either order.

Provenance: original code written for this repository; no third-party code.
