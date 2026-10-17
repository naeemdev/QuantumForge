import { expect, test } from "@playwright/test";
import { createProjectWithSample, signUp, uniqueEmail } from "./helpers.ts";

test("unauthenticated visitors are sent to sign-in", async ({ page }) => {
  await page.goto("/overview");
  await page.waitForURL(/\/login/);
  await expect(page.getByRole("heading", { name: "Sign in" })).toBeVisible();
  await expect(page.getByText(/Development sign-in is enabled/)).toBeVisible();
});

test("import, run tests, analyse and triage the seeded sample", async ({ page }) => {
  const pageErrors: string[] = [];
  page.on("pageerror", (e) => pageErrors.push(String(e)));

  await signUp(page, uniqueEmail("flow"), "E2E Engineering");
  const projectUrl = await createProjectWithSample(page, "Sample TypeScript Application");

  // Test execution: the seeded defects make exactly six tests fail.
  await page.getByRole("button", { name: "Run tests" }).click();
  await page.getByRole("button", { name: "Queue run" }).click();
  await page.waitForURL(/\/test-runs\/[0-9a-f-]+$/);
  await expect(page.getByText("6 of 18 tests failed.", { exact: false })).toBeVisible({ timeout: 90_000 });
  await expect(page.getByText("D5: concurrent reservations never oversell the last unit")).toBeVisible();
  await page.getByRole("tab", { name: "Logs" }).click();
  await expect(page.locator("pre").filter({ hasText: "D1: rounds half-cent amounts up" })).toBeVisible();
  await page.getByRole("tab", { name: "Environment" }).click();
  await expect(page.getByText(/not a security boundary/)).toBeVisible();

  // Static analysis: five suspected issues.
  await page.goto(projectUrl);
  await page.getByRole("button", { name: "Analyse" }).click();
  await page.waitForURL(/\/test-runs\/[0-9a-f-]+$/);
  await expect(page.getByText(/Analysis completed: .* 5 findings detected/)).toBeVisible({ timeout: 90_000 });
  await expect(page.getByRole("link", { name: /rounds the binary value/ })).toBeVisible();

  await page.goto("/findings");
  await expect(page.locator("tbody tr")).toHaveCount(5);
  await page.getByRole("link", { name: /retry loop catches every error/ }).click();
  await expect(page.locator(".monaco-editor").first()).toBeVisible();
  await page.getByRole("button", { name: "Mark false positive" }).click();
  await page.getByLabel("Reason").fill("Callers only retry idempotent reads");
  await page.getByRole("dialog").getByRole("button", { name: "Mark false positive" }).click();
  await expect(page.getByText("“Callers only retry idempotent reads”")).toBeVisible();

  await page.goto("/findings");
  await expect(page.locator("tbody tr")).toHaveCount(4);

  // Code intelligence: the index and call graph are navigable.
  await page.goto("/code-intelligence");
  await expect(page.getByText("Adapter capabilities")).toBeVisible();
  await page.getByText("averageUnitPrice").first().click();
  await expect(page.getByText("Called by")).toBeVisible();
  await expect(page.getByText(/^itemCount/).first()).toBeVisible();

  expect(pageErrors).toEqual([]);
});
