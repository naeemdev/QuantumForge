# Database Design

PostgreSQL 16, migrated by Flyway (`services/api/src/main/resources/db/migration`).
The migrations are the source of truth; this document explains the reasoning.

## Conventions

| Topic | Rule |
|---|---|
| Identifiers | UUIDv7, generated in the application (`common/Ids.kt`). Time-ordered, so B-tree inserts stay mostly sequential; 74 random bits keep IDs unguessable. `bigserial` only for append-only streams that need a cursor (`audit_events`, `job_events`). |
| Tenancy | Every tenant-owned table has `tenant_id`. Each parent exposes `UNIQUE (tenant_id, id)` and children reference it with a composite foreign key `(tenant_id, parent_id)`. The database rejects any row whose parent belongs to another tenant, even if application code had a bug. |
| Audit stamps | Mutable business rows carry `created_at, created_by, updated_at, updated_by`. `created_by`/`updated_by` always come from the authenticated caller, never from request bodies. |
| Optimistic locking | Rows edited by people carry `version`; updates use `WHERE version = :expected` and return `409 stale_version` on mismatch. |
| Immutability | `source_snapshots` and `source_files` have no `updated_*` columns: a snapshot never changes. `audit_events` rejects `UPDATE`/`DELETE` with a trigger. |
| Blobs | Never in PostgreSQL. Archives, logs and reports live in object storage; tables hold key, size, SHA-256 and media type. |
| Time | `timestamptz` everywhere, written as UTC. |

## Entities (Milestone 1)

### Identity and tenancy (V1)

- **tenants** — billing/isolation boundary. Holds quotas (`max_active_jobs`, `max_snapshot_bytes`) and `owner_user_id`.
- **users** — provisioned just-in-time from `(issuer, subject)` of a verified token. Email and name are refreshed from the identity provider.
- **organizations** — belong to one tenant. Globally unique `slug`.
- **workspaces** — subdivide an organization; a `default` workspace is created with every organization.
- **permissions / roles / role_permissions** — seeded catalogue. Built-in roles have `organization_id IS NULL`; the column is reserved for custom roles.
- **organization_memberships** — `(organization_id, user_id)` unique; status `ACTIVE | SUSPENDED | REMOVED`. Removal is a status change so history keeps its references.
- **invitations** — store only the SHA-256 of the token. A partial unique index allows one *pending* invitation per email per organization.
- **api_tokens** — personal access tokens, scoped to one organization, hashed, with expiry and revocation.
- **audit_events** — append-only, trigger-protected. Records actor, action, target, outcome (`SUCCESS | DENIED | FAILURE`), request id, client address.

### Projects and source (V2)

- **projects** — in a workspace; `(workspace_id, slug)` unique.
- **repositories** — `UPLOAD` or `GIT`; a check constraint ties `remote_url` to `GIT`.
- **source_snapshots** — immutable revisions. `content_digest` is the SHA-256 of the canonical archive the platform built, so `(repository_id, content_digest)` is unique and re-uploading identical content returns the existing row.
- **source_files** — one row per accepted file with size, SHA-256, language, binary flag and line count.
- **language_detections** — per-snapshot language totals for dashboards.

### Jobs and evidence (V3)

- **workers** — registered execution workers (platform-level, no tenant). Store the token hash and the isolation mode.
- **analysis_jobs** — the queue. State machine in `docs/ARCHITECTURE.md`. `(tenant_id, idempotency_key)` is unique; `request_hash` detects key reuse with a different body. A partial index on `state = 'QUEUED'` keeps dispatch scans small.
- **job_attempts** — one per lease. The lease (token hash, expiry, heartbeat) lives on the attempt because both have the same lifetime; this replaces a separate `worker_leases` table. Stores outcome, exit code, duration and the worker-reported environment.
- **job_events** — ordered timeline; the `bigserial` id is the SSE cursor.
- **job_artifacts** — evidence metadata; `(attempt_id, name)` unique so retried uploads are idempotent.
- **test_runs / test_results** — parsed results per attempt. `total = passed + failed + skipped + errored` is enforced by a check constraint.

### Code intelligence (V4)

- **rules / rule_versions** — catalogue registered from adapter reports; every version that produced a finding is kept, with its severity, confidence and text.
- **code_indexes** — one per successful analysis job: adapter, version, toolchain, declared capabilities, limitations and stats.
- **code_files / code_symbols / code_imports / code_calls** — the index itself, scoped by `index_id` and tenant.
- **findings** — one row per issue per project, unique on `(project_id, fingerprint)`. Tracks first/last seen job and snapshot, status, and the snapshot that resolved it (`resolved_snapshot_id`), so older snapshots cannot reopen newer fixes.
- **finding_occurrences** — immutable evidence: one row per finding per analysis, with exact location, snippet and rule version.
- **finding_status_history** — every transition with actor (user, API token or system), reason and the analysis that caused it.
- New permission `finding.triage` (owner, admin, member).

### Break My Code (V5)

- **findings** gain `source` (`STATIC_ANALYSIS`, `FUZZING`) and `evidence_level` (`SUSPECTED`, `OBSERVED`, `REPRODUCED`). The lifecycle is evaluated per source.
- **fuzz_campaigns** — one per successful `FUZZ_CAMPAIGN` job: engine and version, seed, iterations, call limit, corpus size, counts, oracles and limitations as reported.
- **fuzz_targets** — every exported function the campaign found, `TESTED` with its counters or `SKIPPED` with the reason.
- **defect_reproductions** — one per failure per campaign: oracle, message, stack, minimal and original input (tagged JSON), shrink steps, re-run attempts and how many reproduced, and the generated regression test. The latest one per open finding is the input replayed by the next campaign.
- Job type `FUZZ_CAMPAIGN` and artifact kind `FUZZ_REPORT`.

### Mutation testing (V6)

- **mutation_runs** — one per successful `MUTATION_TEST` job: engine, seed, mutant limit, test patterns, the baseline (tests passed and failed on the unmutated code, with the failing names), counts, score (`numeric(5,1)`, null when nothing was scored), operators and limitations. `not_run_reason` explains a run without mutants (no tests, no passing test, baseline timed out).
- **mutation_results** — one row per mutant: location, operator, original and replacement text, enclosing symbol, `KILLED`/`SURVIVED`/`TIMEOUT`/`ERROR` and the test that killed it.
- Job type `MUTATION_TEST`, artifact kind `MUTATION_REPORT`. Results are not findings (D-027).

## Transaction boundaries

- Ingestion validates and uploads blobs **outside** any transaction; only the final metadata insert is transactional, and it uses `ON CONFLICT DO NOTHING` so concurrent identical imports converge on one snapshot.
- Job admission locks the tenant row (`SELECT … FOR UPDATE`) so concurrent submissions cannot exceed `max_active_jobs`.
- Dispatch uses `FOR UPDATE SKIP LOCKED` so several API instances can lease concurrently without blocking each other.
- Worker reports lock the job row (`FOR UPDATE OF j`) to serialise with cancellation and the lease reaper.
- Analysis and fuzz report ingestion run inside the job-completion transaction: a job is `SUCCEEDED` exactly when its index, campaign and finding updates are stored. Reports are fully validated before the first write.
- Successful audit events commit with the change they describe; DENIED events use a separate transaction because the request's own transaction rolls back.

## Planned tables

Added when their modules are built, not before: `teams`, `team_members`,
`repository_credentials` (encrypted);
`repair_proposals`, `repair_verification_runs` (M4); `subscription_plans`,
`subscriptions`, `usage_events`, `usage_aggregates`, `quota_policies` (M7).

## Defence in depth: row-level security (planned)

Composite foreign keys stop cross-tenant references at write time, and every
query filters by `tenant_id`. Row-level security policies keyed on a
per-transaction `app.tenant_id` setting are the planned third layer (D-009). They
need care with connection pooling and with the few legitimately cross-tenant
reads (a user listing their own memberships), so they are scheduled rather than
rushed.
