import { expect, test } from "@playwright/test";
import { createProjectWithSample, signUp, uniqueEmail } from "./helpers.ts";

test("a mutation run scores the sample's tests and lists the changes they miss", async ({ page }) => {
  test.setTimeout(300_000);
  const pageErrors: string[] = [];
  page.on("pageerror", (e) => pageErrors.push(String(e)));

  await signUp(page, uniqueEmail("mutation"), "E2E Mutation");
  await createProjectWithSample(page, "Mutated Commerce API");

  await page.getByRole("link", { name: "Break My Code" }).click();
  await page.getByRole("tab", { name: "Mutation testing" }).click();
  await expect(page.getByText("No mutation runs yet")).toBeVisible();
  await page.getByRole("button", { name: "New mutation run" }).click();
  await page.getByRole("dialog").getByRole("button", { name: "Start run" }).click();

  await page.waitForURL(/\/break-my-code\/mutation\/[0-9a-f-]+$/);
  await expect(page.getByText(/mutants were detected by the 12 passing tests/)).toBeVisible({ timeout: 180_000 });
  await expect(page.getByText(/6 tests already fail on the unmutated code/)).toBeVisible();
  await expect(page.getByRole("heading", { name: "Surviving mutants" })).toBeVisible();
  await expect(page.getByText("await inventory.reserve(line.sku, line.quantity);").first()).toBeVisible();

  await page.getByRole("link", { name: "Break My Code" }).first().click();
  await page.getByRole("tab", { name: "Mutation testing" }).click();
  await expect(page.locator("tbody tr")).toHaveCount(1);

  expect(pageErrors).toEqual([]);
});
