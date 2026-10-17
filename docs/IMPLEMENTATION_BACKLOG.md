# Implementation Backlog

Milestone 1 broken into tasks, in dependency order. Status: ✅ done · 🔶 partial · ⬜ open.

---

### T1 ✅ Repository and build setup
- **Objective:** Executable monorepo with reproducible builds.
- **Files:** `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `gradlew`, `package.json`, `pnpm-workspace.yaml`, `.gitignore`
- **Depends on:** —
- **Details:** Gradle multi-project (Kotlin DSL, version catalog, wrapper 8.14.3); pnpm workspace for `apps/*` and `packages/*`.
- **Tests:** `./gradlew build`, `pnpm install --frozen-lockfile`.
- **Acceptance:** Clean checkout builds with only JDK 21 and Node 22.

### T2 ✅ Spring Boot application skeleton
- **Files:** `services/api/**/QuantumForgeApplication.kt`, `config/*`, `common/*`, `application*.yml`
- **Depends on:** T1
- **Details:** Error envelope, request ids, paging, UUIDv7, properties, profiles (`dev`, `prod`), production safety check, health endpoints.
- **Tests:** `SmallUnitsTest`; prod refusal verified manually (`java -jar` with `prod` profile).
- **Acceptance:** App starts in `dev`; refuses to start in `prod` with unsafe settings.

### T3 ✅ PostgreSQL schema and migrations
- **Files:** `db/migration/V1..V3`
- **Depends on:** T2
- **Details:** See `DATABASE_DESIGN.md`.
- **Tests:** Every integration test run migrates from an empty schema; `JobLifecycleTest` checks audit immutability.
- **Acceptance:** Clean migrate succeeds; constraints enforce tenant consistency.

### T4 ✅ Shared API contracts
- **Files:** `config/CoreConfig.kt`, `OpenApiExportTest.kt`, `packages/contracts/*`
- **Depends on:** T2
- **Details:** springdoc public group (internal routes excluded); required-field customiser; generated TS types.
- **Tests:** `AuthorizationTest` (no internal routes in the document); CI drift checks.
- **Acceptance:** `exportOpenApi` + `contracts:generate` produce the committed files.

### T5 ✅ Authentication and tenant context
- **Files:** `security/*`, `identity/UserService.kt`, `identity/DevLoginController.kt`, `identity/ApiTokenService.kt`
- **Depends on:** T3
- **Details:** Issuer-routed JWT validation (OIDC + dev), JIT users, PATs, worker tokens, port separation, rate limiting.
- **Tests:** `AuthorizationTest`, `SmallUnitsTest`.
- **Acceptance:** Missing or invalid credentials → 401; tokens scoped and revocable.

### T6 ✅ Organizations, memberships and RBAC
- **Files:** `access/*`, `identity/OrganizationService.kt`, `identity/InvitationService.kt`, `identity/IdentityController.kt`, `audit/*`
- **Depends on:** T5
- **Tests:** `AuthorizationTest` (invitations, viewer restrictions, escalation, last owner, stale versions, audit of denials).
- **Acceptance:** Permissions enforced server-side; non-members get 404.

### T7 ✅ Projects and repositories
- **Files:** `projects/*`
- **Depends on:** T6
- **Tests:** `AcceptanceScenarioTest`, `SourceIngestionTest` (Git URL validation).
- **Acceptance:** CRUD with versioning; Git URLs restricted to HTTPS on allowed hosts.

### T8 ✅ Secure source snapshot ingestion
- **Files:** `source/*`, `storage/*`
- **Depends on:** T7
- **Details:** Archive validation and canonicalisation, Git import, language detection, file listing and content.
- **Tests:** `ArchiveIngestorTest`, `SourceIngestionTest`, `BlobStoreContractTest`.
- **Acceptance:** Hostile archives rejected with specific codes; identical content deduplicated.

### T9 ✅ Job orchestration
- **Files:** `jobs/*`
- **Depends on:** T8
- **Details:** Submission with idempotency and quota, SKIP LOCKED leasing, heartbeats, cancellation, retries, reaper, events, SSE.
- **Tests:** `JobLifecycleTest`.
- **Acceptance:** Retries never corrupt state; stale workers cannot overwrite results.

### T10 ✅ Execution worker and evidence storage
- **Files:** `services/worker/**`, `jobs/DispatchService.kt`, `testing/*`
- **Depends on:** T9
- **Details:** Snapshot verification, sandboxes, bounded capture, JUnit parsing, artifact upload, environment report.
- **Tests:** `WorkerUnitTest`, `AcceptanceScenarioTest`.
- **Acceptance:** Real execution of the sample yields stored logs, JUnit, environment and per-test results.

### T11 ✅ Functional web UI
- **Files:** `apps/web/**`
- **Depends on:** T4, T10
- **Details:** Sign-in, onboarding, overview, projects, snapshots, source browser, test runs (live), organization admin, settings, roadmap.
- **Tests:** typecheck, lint, production build; Playwright walkthrough (manual script, not yet in CI).
- **Acceptance:** The acceptance scenario can be performed entirely in the browser.

### T12 🔶 Docker development environment
- **Files:** `infrastructure/docker/*`, `docker-compose.yml`, `.env.example`
- **Depends on:** T10, T11
- **Status:** Written and `docker compose config` validated. Images not built locally (no Docker daemon in the build environment); CI builds them.
- **Acceptance:** `docker compose --profile dev-worker up --build` runs the full scenario.

### T13 ✅ Bug corpus and verification
- **Files:** `benchmarks/bug-corpus/sample-commerce-api/**`, `scripts/verify-bug-corpus.sh`
- **Acceptance:** Shipped tree fails exactly the 6 regression tests; all fix patches make it pass.

### T14 ✅ CI
- **Files:** `.github/workflows/ci.yml`
- **Acceptance:** Backend, web, corpus and image jobs run on every PR.

---

## Next (carry-over and M2 preparation)

| ID | Task | Notes |
|---|---|---|
| N1 | Container-mode end-to-end test in CI | Run `ContainerSandbox` against Docker on the CI runner |
| N2 | Playwright UI test in CI | Promote the manual walkthrough script |
| N3 | PostgreSQL row-level security | D-009 |
| N4 | Retention and deletion policies | Snapshots, artifacts, audit export |
| N5 | Shared rate-limit store | Needed before running more than one API instance |
| N6 | Teams and team membership | Schema + API + UI |
| N7 | Encrypted private Git credentials | Envelope encryption, scoped, redacted |
| N8 | Dependency scanning in CI | Gradle and pnpm |
| N9 | CLI (`apps/cli`) | Submit runs, wait, exit code for CI gates |
| N10 | ✅ M2: TypeScript adapter contract and indexer | Done; see M2 tasks below |
| N11 | Persist the project's tsconfig options in analysis | Path aliases and project strictness currently ignored |
| N12 | Semantic diagnostics as findings (opt-in) | Needs dependency type information to avoid noise |
| N13 | Held-out evaluation corpus for rules | Measure precision/recall on samples rule authors have not seen |
| N14 | Interactive dependency graph view | Table + side panel today; WebGL graph is M6 |

---

## Milestone 2 — Code intelligence

### M2-T1 ✅ Language adapter contract
- **Files:** `packages/language-sdk/src/index.ts`
- **Details:** Report schema v1 (files, symbols, imports, calls, rules, findings, stats) and capability levels.

### M2-T2 ✅ TypeScript adapter
- **Files:** `language-packs/typescript/src/**`
- **Details:** Compiler-API program with fixed options; symbols, complexity, resolved imports, call graph, syntax diagnostics; nine versioned rules; content fingerprints.
- **Tests:** `language-packs/typescript/test` (15): per-rule positive/negative cases, indexing, graph resolution, fingerprint stability, corpus before/after fixes.

### M2-T3 ✅ Analysis job and worker integration
- **Files:** `jobs/JobModels.kt`, `services/worker/**/JobRunner.kt`, sandboxes (tool mounts)
- **Details:** `STATIC_ANALYSIS` job type with `TS_ANALYSIS` preset; analyzer mounted read-only into container sandboxes.

### M2-T4 ✅ Report ingestion and finding lifecycle
- **Files:** `V4__code_intelligence.sql`, `analysis/ReportIngestion.kt`, `analysis/AnalysisReport.kt`
- **Tests:** `CodeIntelligenceTest` (lifecycle across five snapshots, triage, tenant isolation, viewer permissions).

### M2-T5 ✅ Code intelligence API
- **Files:** `analysis/CodeIntelligenceService.kt`, `analysis/FindingService.kt`, `analysis/AnalysisController.kt`

### M2-T6 ✅ UI
- **Files:** `apps/web/src/app/(app)/code-intelligence`, `findings`, `findings/[findingId]`, source viewer, run page
- **Details:** Self-hosted Monaco viewer with finding markers and outline; symbol search with callers/callees; dependency table; findings explorer and triage with reasons.
