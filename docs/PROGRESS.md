# Progress

_Last updated: 2026-10-17_

## Milestone 1 — Foundation: functionally complete, two items partly verified

The acceptance scenario runs end to end and is covered by an automated test
(`AcceptanceScenarioTest`):

1. A user signs in (dev login in tests; OIDC is supported for real deployments).
2. Creates the organization **Demo Engineering** and the project **Sample TypeScript Application**.
3. Uploads `benchmarks/bug-corpus/sample-commerce-api` as a GitHub-style archive. It is validated, canonicalised and stored as an immutable, content-addressed snapshot; re-uploading returns the same snapshot.
4. Queues a test run with an idempotency key.
5. A worker leases it, verifies the snapshot digest, runs `node:test` in a sandbox, heartbeats, uploads stdout, stderr, JUnit XML and an environment record, and reports the outcome.
6. The job is `FAILED` with **18 tests, 12 passed, 6 failed**. The failures are exactly the six documented seeded defects D1–D6, with their assertion messages.
7. After applying the corpus's fix patches and uploading again, the same run is `SUCCEEDED` with 18/18 passing.
8. History and the dashboard show both runs; artifact downloads match their recorded SHA-256.
9. A user in another tenant gets **404** on every one of the first tenant's resources, and sees only their own organization.

The same flow was also driven through the browser with Playwright against the
running stack.

## Test results (local run, 2026-10-17)

| Suite | Tests | Result |
|---|---|---|
| AcceptanceScenarioTest | 1 | pass |
| AuthorizationTest | 7 | pass |
| JobLifecycleTest | 8 | pass |
| SourceIngestionTest | 4 | pass |
| ArchiveIngestorTest | 7 | pass |
| SmallUnitsTest | 6 | pass |
| BlobStoreContractTest | 2 | 1 pass, 1 skipped (S3 case needs `QF_TEST_S3_ENDPOINT`; CI runs it against S3Mock) |
| WorkerUnitTest | 4 | pass |
| **Total** | **39** | **38 pass, 1 skipped, 0 fail** |

Other checks:
- `pnpm typecheck` ✅ · `pnpm lint` ✅ · `pnpm web:build` ✅
- `scripts/verify-bug-corpus.sh` ✅ (shipped: 12 pass / 6 fail; fixed: 18 pass / 0 fail)
- Flyway V1–V3 apply cleanly to an empty database ✅
- `docker compose config` ✅
- `prod` profile refuses to start without an OIDC issuer ✅ (manual)

## What was not verified here, and why

- **Container images and the Compose stack were not built or run.** The build environment has no Docker daemon. The CI workflow builds all three images.
- **`container` and `container-gvisor` sandboxes were not executed.** They are implemented, but all execution tests used `dev-process`. A Docker-backed CI test is backlog item N1.
- The S3 blob store contract test was later run locally against Adobe S3Mock 4.11.0 and passes; CI runs it on every build.
- **OIDC sign-in against a real identity provider was not exercised.** The decoder and browser PKCE flow are implemented; tests use the development issuer.

## Not started (by design, later milestones)

Code intelligence, static analysis, Break My Code, repair verification, formal and
numerical engines, COBOL laboratory, AI features, graph visualisation, billing. The
UI lists these under "Planned" and links to a roadmap page; no module shows data it
does not have.

## Next tasks

See `IMPLEMENTATION_BACKLOG.md` › Next. In order: N1 (container-mode CI test), N2
(Playwright in CI), N3 (row-level security), N7 (encrypted Git credentials), then M2
starting with the language adapter contract and the TypeScript indexer.
