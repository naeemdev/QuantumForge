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

### Mapping to the long-term API

The product plan names `POST /projects/{id}/analyses`, `GET /analyses/{id}` and
`/repair-verifications`. In M1 the only job type is test execution, exposed as
`test-runs`. Analyses (M2) and repair verifications (M4) will be new job types on the
same queue with their own resource paths; the `/jobs` endpoints stay the generic view.

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
