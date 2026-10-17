import { test } from "node:test";
import assert from "node:assert/strict";
import { HttpError, withRetry } from "../src/retry.ts";

const noSleep = async () => {};

test("retries transient server errors until success", async () => {
  let calls = 0;
  const result = await withRetry(async () => {
    calls++;
    if (calls < 3) throw new HttpError(503, "unavailable");
    return "ok";
  }, { retries: 3, baseDelayMs: 10, sleep: noSleep });
  assert.equal(result, "ok");
  assert.equal(calls, 3);
});

test("gives up after the configured number of retries", async () => {
  let calls = 0;
  await assert.rejects(withRetry(async () => {
    calls++;
    throw new HttpError(503, "unavailable");
  }, { retries: 2, baseDelayMs: 10, sleep: noSleep }));
  assert.equal(calls, 3);
});

test("backs off exponentially", async () => {
  const delays: number[] = [];
  await assert.rejects(withRetry(async () => {
    throw new HttpError(500, "boom");
  }, { retries: 3, baseDelayMs: 100, sleep: async (ms) => { delays.push(ms); } }));
  assert.deepEqual(delays, [100, 200, 400]);
});

// Regression test for D4.
test("D4: does not retry 4xx client errors", async () => {
  let calls = 0;
  await assert.rejects(withRetry(async () => {
    calls++;
    throw new HttpError(422, "invalid payment details");
  }, { retries: 3, baseDelayMs: 10, sleep: noSleep }), HttpError);
  assert.equal(calls, 1);
});
