# API Contracts

The machine-readable contract is `packages/contracts/openapi.json`, exported from
the running application (`./gradlew :services:api:exportOpenApi`). TypeScript types
are generated from it (`pnpm contracts:generate`). CI fails if either file is out of
date. This page covers the conventions that the schema cannot express.

## Conventions

- **Base path:** `/api/v1`. Health probes at `/health/live` and `/health/ready`.
- **Authentication:** `Authorization: Bearer <token>`. Accepted tokens: OIDC access tokens from the configured issuer, development tokens (only when dev login is enabled), and personal access tokens (`qf_pat_…`).
- **Authorization:** evaluated per request against the caller's membership in the organization that owns the resource. Resources in another tenant, or in an organization the caller does not belong to, return **404**. Members lacking a permission get **403** with `details.permission`.
- **Errors:** always the same envelope:
  ```json
  {"error": {"code": "stale_version", "message": "…", "status": 409, "requestId": "…",
             "timestamp": "…", "details": {}, "fieldErrors": [{"field": "name", "message": "must not be blank"}]}}
  ```
  `code` is stable and machine-readable; `message` is for people.
- **Request ids:** every response carries `X-Request-Id`, which also appears in error bodies, logs and audit events. A valid incoming `X-Request-Id` is propagated.
- **Pagination:** `?page=0&size=50` (max 200). Responses are `{items, page, size, total}`.
- **Optimistic locking:** mutable resources expose `version`. `PATCH` bodies must send it back; a mismatch returns `409 stale_version`.
- **Idempotency:** `POST /projects/{id}/test-runs` accepts `Idempotency-Key` (1–200 printable ASCII chars, scoped to the tenant). Replaying the same request returns the original job with `200`; reusing the key for a different request returns `409 idempotency_key_reused`. A new job returns `201`.
- **Rate limiting:** per caller token bucket; `429 rate_limited` with `Retry-After`.
- **Timestamps:** ISO-8601 UTC. **IDs:** UUIDv7 strings.

## Endpoints (Milestone 1)

| Method & path | Permission | Notes |
|---|---|---|
| `GET /health/live`, `GET /health/ready` | public | Ready checks DB and object storage |
| `GET /api/v1/auth/config` | public | Which sign-in methods are enabled |
| `POST /api/v1/dev/session` | public, dev only | Exists only when dev login is enabled |
| `GET /api/v1/me` | authenticated | Profile plus memberships and effective permissions |
| `GET /api/v1/roles` | authenticated | Built-in role/permission catalogue |
| `GET /api/v1/organizations` | authenticated | Caller's organizations (a token sees only its own) |
| `POST /api/v1/organizations` | authenticated (not API tokens) | Creates org, default workspace, owner membership |
| `GET /api/v1/organizations/{id}` | `org.read` | |
| `PATCH /api/v1/organizations/{id}` | `org.update` | Rename (versioned) |
| `GET /api/v1/organizations/{id}/workspaces` | `org.read` | |
| `GET /api/v1/organizations/{id}/members` | `org.read` | |
| `PATCH /api/v1/organizations/{id}/members/{membershipId}` | `org.members.manage` | No escalation above own rank; last owner protected |
| `DELETE /api/v1/organizations/{id}/members/{membershipId}` | `org.members.manage` | Also revokes the member's tokens there |
| `GET/POST /api/v1/organizations/{id}/invitations` | `org.members.manage` | Token returned once at creation |
| `DELETE /api/v1/organizations/{id}/invitations/{invitationId}` | `org.members.manage` | |
| `POST /api/v1/invitations/accept` | authenticated | Caller email must match the invitation |
| `GET/POST /api/v1/organizations/{id}/api-tokens` | `org.tokens.manage` | Secret returned once; tokens cannot mint tokens |
| `DELETE /api/v1/organizations/{id}/api-tokens/{tokenId}` | `org.tokens.manage` | |
| `GET /api/v1/organizations/{id}/audit-events` | `org.audit.read` | Newest first |
| `GET /api/v1/organizations/{id}/overview` | `project.read` | Dashboard aggregates, all live queries |
| `GET /api/v1/projects?organizationId=` | `project.read` | `q` filters by name/slug |
| `POST /api/v1/projects` | `project.create` | |
| `GET /api/v1/projects/{id}` | `project.read` | |
| `PATCH /api/v1/projects/{id}` | `project.update` | Versioned; can archive |
| `GET/POST /api/v1/projects/{id}/repositories` | `project.read` / `project.update` | `UPLOAD` or `GIT` (HTTPS, allowlisted hosts) |
| `GET /api/v1/repositories/{id}` | `project.read` | |
| `POST /api/v1/repositories/{id}/snapshots` | `source.import` | Multipart `archive` (.zip / .tar.gz). `201` new, `200` identical content |
| `POST /api/v1/repositories/{id}/git-imports` | `source.import` | Shallow clone of `ref` or default branch |
| `GET /api/v1/repositories/{id}/snapshots` | `project.read` | |
| `GET /api/v1/projects/{id}/revisions` | `project.read` | All snapshots of the project |
| `GET /api/v1/projects/{id}/files` | `project.read` | Files of `snapshotId` or the latest snapshot |
| `GET /api/v1/snapshots/{id}` | `project.read` | |
| `GET /api/v1/snapshots/{id}/files` | `project.read` | `prefix` filters a directory |
| `GET /api/v1/snapshots/{id}/languages` | `project.read` | |
| `GET /api/v1/snapshots/{id}/content?path=` | `project.read` | Text files up to 2 MiB |
| `POST /api/v1/projects/{id}/test-runs` | `job.submit` | Idempotency-Key supported; quota enforced (`429 quota_exceeded`) |
| `GET /api/v1/jobs?organizationId=` | `job.read` | Filters: `projectId`, repeated `state` |
| `GET /api/v1/jobs/{id}` | `job.read` | Job plus all attempts with environments |
| `POST /api/v1/jobs/{id}/cancel` | `job.cancel` | Queued: immediate. Running: requested, confirmed by worker |
| `GET /api/v1/jobs/{id}/events` | `job.read` | `after` cursor |
| `GET /api/v1/jobs/{id}/stream` | `job.read` | Server-Sent Events, honours `Last-Event-ID` |
| `GET /api/v1/jobs/{id}/test-results` | `job.read` | |
| `GET /api/v1/jobs/{id}/artifacts` | `job.read` | |
| `GET /api/v1/jobs/{id}/artifacts/{artifactId}/content` | `job.read` | Streams bytes; `X-Artifact-Sha256` header |

### Code intelligence (Milestone 2)

| Method & path | Permission | Notes |
|---|---|---|
| `POST /api/v1/projects/{id}/analyses` | `job.submit` | Queues a `STATIC_ANALYSIS` job; Idempotency-Key supported |
| `GET /api/v1/analyses/{id}` | `job.read` | Job, attempts, code index summary and finding counts |
| `GET /api/v1/analyses/{id}/findings` | `job.read` | Finding occurrences recorded by this analysis |
| `GET /api/v1/analyses/{id}/artifacts` | `job.read` | Includes the raw `analysis.json` report |
| `GET /api/v1/projects/{id}/code-index` | `project.read` | Latest index (or for `snapshotId`) with capabilities and limitations |
| `GET /api/v1/projects/{id}/symbols` | `project.read` | `q`, `kind`, `path`, paged |
| `GET /api/v1/projects/{id}/dependencies` | `project.read` | File graph (fan-in/out, findings), external packages, unresolved count |
| `GET /api/v1/projects/{id}/calls` | `project.read` | Call edges, optionally for one `symbol` |
| `GET /api/v1/projects/{id}/annotations?snapshotId&path` | `project.read` | Symbols, findings and diagnostics of one file |
| `GET /api/v1/projects/{id}/findings` | `project.read` | Filters: repeated `status`, repeated `severity`, `ruleId`, `path`, `q`, `source` (`STATIC_ANALYSIS`, `FUZZING`) |
| `GET /api/v1/projects/{id}/finding-counts` | `project.read` | By severity, status and rule |
| `GET /api/v1/findings/{id}` | `project.read` | Finding (with `source` and `evidenceLevel`), rule version, occurrences, status history, reproductions |
| `POST /api/v1/findings/{id}/status` | `finding.triage` | `CONFIRMED`, `FALSE_POSITIVE`, `ACCEPTED_RISK`, `OPEN`; dismissals need `reason`; versioned. `FIXED`/`REOPENED` are set only by analyses |
| `GET /api/v1/rules` | authenticated | Rule catalogue (latest versions) |

### Break My Code (Milestone 3)

| Method & path | Permission | Notes |
|---|---|---|
| `POST /api/v1/projects/{id}/fuzz-campaigns` | `job.submit` | Queues a `FUZZ_CAMPAIGN` job. Body: `snapshotId`, `seed`, `iterationsPerTarget` (1–100000), `callTimeoutMs` (50–60000), `timeoutSeconds`, `priority`. The API adds the project's open fuzzing findings' reproduction inputs as the replay corpus (not part of the idempotency hash). Idempotency-Key supported |
| `GET /api/v1/projects/{id}/fuzz-campaigns` | `project.read` | Campaign jobs, newest first, with campaign statistics once finished |
| `GET /api/v1/fuzz-campaigns/{id}` | `job.read` | Job, attempts, campaign (engine, seed, oracles, limitations, counts), every target tested or skipped with the reason, and the reproductions this campaign recorded |
| `GET /api/v1/fuzz-campaigns/{id}/artifacts` | `job.read` | Includes the raw `fuzz.json` report |

| `POST /api/v1/projects/{id}/mutation-runs` | `job.submit` | Queues a `MUTATION_TEST` job. Body: `snapshotId`, `testPatterns` (globs, default node:test conventions), `seed`, `maxMutants` (1–5000), `timeoutSeconds`, `priority`. Idempotency-Key supported |
| `GET /api/v1/projects/{id}/mutation-runs` | `project.read` | Mutation runs, newest first, with score and counts once finished |
| `GET /api/v1/mutation-runs/{id}` | `job.read` | Job, attempts, run (baseline, score, operators, limitations) and every mutant, survivors first |
| `GET /api/v1/mutation-runs/{id}/artifacts` | `job.read` | Includes the raw `mutation.json` report |

Reproduction inputs use a JSON encoding that tags what JSON cannot carry:
`{"$qf": "NaN" | "Infinity" | "-Infinity" | "-0" | "undefined"}`.

### Mapping to the long-term API

The product plan names `POST /projects/{id}/analyses`, `GET /analyses/{id}` and
`/repair-verifications`. Test execution is exposed as `test-runs` and static
analysis as `analyses`, fuzzing as `fuzz-campaigns`, mutation testing as `mutation-runs`; repair verifications (M4) will be another job type on the same
queue. The `/jobs` endpoints stay the generic view (filter with `type`).

## Worker protocol (internal, not in OpenAPI)

Served only on the internal port (default 8081), authenticated with a worker token.
Every call after leasing carries `X-Lease-Token`.

| Call | Purpose |
|---|---|
| `POST /internal/v1/leases` | Lease the next job (`204` when the queue is empty, `409 isolation_not_permitted` for disallowed isolation) |
| `POST /internal/v1/attempts/{id}/heartbeat` | Extend the lease; response says whether cancellation was requested |
| `POST /internal/v1/attempts/{id}/start` | Report the execution environment; job becomes `RUNNING` |
| `GET /internal/v1/attempts/{id}/snapshot` | Download the canonical archive (worker verifies its SHA-256) |
| `PUT /internal/v1/attempts/{id}/artifacts/{name}?kind=` | Upload evidence (size-capped, idempotent per name) |
| `POST /internal/v1/attempts/{id}/complete` | Report outcome and parsed test results; idempotent for identical reports |

Only the current, unfinished attempt is accepted; anything else gets `409 lease_lost`
and the worker discards its results.
