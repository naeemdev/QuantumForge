import { expect, test } from "@playwright/test";
import { createProjectWithSample, signUp, uniqueEmail } from "./helpers.ts";

test("a fuzz campaign reproduces the empty-cart defect with a minimal input", async ({ page }) => {
  const pageErrors: string[] = [];
  page.on("pageerror", (e) => pageErrors.push(String(e)));

  await signUp(page, uniqueEmail("fuzz"), "E2E Fuzzing");
  await createProjectWithSample(page, "Fuzzed Commerce API");

  await page.getByRole("link", { name: "Break My Code" }).click();
  await expect(page.getByText("No defects reproduced yet")).toBeVisible();
  await page.getByRole("button", { name: "New campaign" }).click();
  await page.getByLabel("Calls per function").fill("150");
  await page.getByRole("dialog").getByRole("button", { name: "Start campaign" }).click();

  await page.waitForURL(/\/break-my-code\/[0-9a-f-]+$/);
  await expect(page.getByText(/1 failure found in 8 functions/)).toBeVisible({ timeout: 90_000 });
  await expect(page.getByText("averageUnitPrice({ lines: [] })").first()).toBeVisible();
  await expect(page.getByText("Reproduced 3 of 3")).toBeVisible();
  await page.getByRole("button", { name: /Show \d+ skipped/ }).click();
  await expect(page.getByText("Skipped: generic function")).toBeVisible();

  await page.getByRole("link", { name: "Open finding" }).click();
  await expect(page.getByText("Reproduced · fuzzing")).toBeVisible();
  await expect(page.getByText(/import \{ averageUnitPrice \} from "\.\/src\/cart\.ts"/)).toBeVisible();

  await page.goto("/break-my-code");
  await expect(page.locator("tbody tr").filter({ hasText: "QF-FUZZ-002" })).toHaveCount(1);

  expect(pageErrors).toEqual([]);
});
