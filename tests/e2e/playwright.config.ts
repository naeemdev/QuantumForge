import { defineConfig, devices } from "@playwright/test";

/**
 * Expects a running stack (see docs/DEVELOPMENT.md › End-to-end tests):
 * web on E2E_BASE_URL (default http://localhost:3000), the API in the dev
 * profile, and a worker with the TypeScript adapter.
 */
export default defineConfig({
  testDir: "./specs",
  timeout: 120_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: process.env.CI ? [["list"], ["html", { open: "never" }]] : "list",
  use: {
    baseURL: process.env.E2E_BASE_URL ?? "http://localhost:3000",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"], viewport: { width: 1440, height: 900 } } }],
});
