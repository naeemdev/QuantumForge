import { execFileSync } from "node:child_process";
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { expect, type Page } from "@playwright/test";

export const repoRoot = resolve(import.meta.dirname, "../../..");

/** Packs the seeded sample repository the way a user would download it from a code host. */
export function sampleArchive(): string {
  const dir = mkdtempSync(join(tmpdir(), "qf-e2e-"));
  const archive = join(dir, "sample-commerce-api.tar.gz");
  execFileSync("tar", ["-czf", archive, "-C", join(repoRoot, "benchmarks/bug-corpus"), "sample-commerce-api"]);
  return archive;
}

export function uniqueEmail(tag: string): string {
  return `${tag}-${Date.now()}-${Math.floor(Math.random() * 1e6)}@e2e.test`;
}

export async function signUp(page: Page, email: string, organization: string): Promise<void> {
  await page.goto("/login");
  await page.getByLabel("Email").fill(email);
  await page.getByRole("button", { name: "Sign in" }).click();
  await page.waitForURL("**/onboarding");
  await page.getByLabel("Organization name").fill(organization);
  await page.getByRole("button", { name: "Create organization" }).click();
  await page.waitForURL("**/overview");
}

export async function createProjectWithSample(page: Page, name: string): Promise<string> {
  await page.goto("/projects");
  await page.getByRole("button", { name: "New project" }).first().click();
  await page.getByLabel("Name").fill(name);
  await page.getByRole("button", { name: "Create project" }).click();
  await page.waitForURL(/\/projects\/[0-9a-f-]+$/);
  const url = page.url();
  await page.getByRole("button", { name: "Add" }).click();
  await page.getByLabel("Name").fill("sample-commerce-api");
  await page.getByRole("button", { name: "Add repository" }).click();
  await page.locator("input[type=file]").setInputFiles(sampleArchive());
  await expect(page.getByText(/Imported snapshot/)).toBeVisible({ timeout: 30_000 });
  return url;
}
