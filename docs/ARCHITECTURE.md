# Architecture

## Shape of the system

```
                 ┌────────────────────────── trusted zone ───────────────────────────┐
 browser ──TLS──▶│  apps/web (Next.js)                                               │
                 │        │ bearer token (OIDC / dev login)                          │
                 │        ▼                                                          │
 CLI / CI ──────▶│  services/api  (Kotlin, Spring Boot — modular monolith)            │
                 │   public port 8080        internal port 8081 (workers only)       │
                 │   ├─ identity   (users, tenants, orgs, memberships, tokens)       │
                 │   ├─ access     (RBAC evaluation, tenant context)                 │
                 │   ├─ projects   (projects, repositories)                          │
                 │   ├─ source     (archive/Git ingestion, snapshots, files)         │
                 │   ├─ jobs       (queue, leasing, attempts, events, artifacts)     │
                 │   ├─ testing    (test runs and parsed results)                    │
                 │   ├─ audit      (append-only security audit trail)                │
                 │   └─ storage    (BlobStore: filesystem or S3/MinIO)               │
                 │        │                    │                                     │
                 │   PostgreSQL           object storage                             │
                 └──────────────────────────────┬────────────────────────────────────┘
                                                │ lease / heartbeat / report (HTTPS, worker token)
                 ┌──────────────────────────────▼──────── untrusted zone ────────────┐
                 │  services/worker  (Kotlin)                                        │
                 │   leases a job, downloads the snapshot, runs it inside a sandbox  │
                 │   (dev process sandbox | container | container + gVisor/microVM) │
                 │   and uploads logs, JUnit results and environment details.        │
                 └───────────────────────────────────────────────────────────────────┘
```

## Modular monolith

The control plane is one deployable Spring Boot app split into packages with
clear ownership (`com.quantumforge.<module>`). Modules talk through Kotlin
service interfaces, never through each other's tables. We split a module out
only when it has a real scaling or isolation reason. The one split made on day
one is the **execution worker**: it runs untrusted code, so it is a separate
process with its own credentials, no database access and no production secrets.

## Request path

1. `BearerAuthenticationFilter` resolves the caller from either a JWT (OIDC
   issuer, or the dev issuer when explicitly enabled) or an opaque `qf_pat_…`
   API token (hashed lookup).
2. Users are provisioned just-in-time from `(issuer, subject)`.
3. Controllers are thin; they call module services.
4. Services call `AccessService.require(orgId, Permission)` which loads the
   caller's membership in that organization and checks the role's permissions.
   Every repository query also filters by `tenant_id` resolved from the
   authorised organization — never from the request body.
5. Errors leave as one envelope (`ApiError`) via `GlobalExceptionHandler`.
   Resources outside the caller's tenant return **404**, not 403, so IDs cannot be probed.

## Tenancy model

`tenant → organizations → workspaces → projects → repositories → snapshots → jobs`.

- M1 creates one tenant per self-serve signup (the first organization creates it).
  Additional organizations created by the tenant owner join the same tenant.
- Every tenant-owned table carries `tenant_id`. Child tables reference parents
  with composite foreign keys `(tenant_id, parent_id)`, so the database itself
  refuses a row that points at another tenant's parent.
- Row-level security is planned as a second layer (see DECISIONS.md, D-009).

## Job engine

Jobs are rows in `analysis_jobs`. A worker leases one with
`SELECT … FOR UPDATE SKIP LOCKED`, which creates a `job_attempts` row carrying a
random lease token and an expiry. The worker heartbeats to extend the lease and
learns about cancellation from the heartbeat response. Reports are accepted only
with the current attempt's lease token, so a worker whose lease expired cannot
overwrite a newer attempt. A scheduled reaper re-queues expired leases until
`max_attempts` is reached, then marks the job `INFRA_ERROR`.

State machine:

```
QUEUED ──lease──▶ LEASED ──start──▶ RUNNING ──▶ SUCCEEDED | FAILED | TIMED_OUT | CANCELLED
  │                 │                  │
  │                 └──lease expired───┴──▶ QUEUED (retry) or INFRA_ERROR (attempts exhausted)
  └──cancel──▶ CANCELLED
```

`SUCCEEDED` means the job ran to completion and its tests passed; `FAILED` means
it ran and the tests (or the command) failed. An infrastructure fault is
`INFRA_ERROR`, never `FAILED`, so test failures and platform failures stay apart.

## Source ingestion

Archives (`.zip`, `.tar.gz`) are streamed, validated entry by entry (path
normalisation, traversal, absolute paths, symlinks/hard links/devices rejected,
entry count, per-file and total size, compression ratio) and re-packed into a
canonical tar.gz whose SHA-256 identifies the snapshot. Git imports run `git`
through `ProcessBuilder` with an argument list (no shell), hooks disabled,
only `https://` URLs, and no credential prompts. The cloned tree goes through
the same validation as an upload. Snapshots are immutable; uploading identical
content twice returns the existing snapshot.

## Evidence storage

Blobs (snapshot archives, logs, JUnit XML) live in object storage under
`tenants/{tenantId}/…`. PostgreSQL stores metadata only: key, size, SHA-256,
media type. Downloads go through the API, which checks authorization first.

## Worker isolation levels

| Mode              | Boundary                                   | Allowed for            |
|-------------------|--------------------------------------------|------------------------|
| `dev-process`     | child process, scrubbed env, timeouts, output caps, `ulimit` | local development & CI only |
| `container`       | `docker run` non-root, read-only rootfs, no network, caps dropped, pids/mem/cpu limits | trusted-ish code, staging |
| `container-gvisor`| as above with `--runtime=runsc`            | production minimum     |

The API refuses to dispatch to a worker reporting `dev-process` unless
`quantumforge.execution.allow-dev-isolation=true`, which the `prod` profile
forbids at startup. Plain Docker is **not** presented as a hostile-code boundary.

## Frontend

Next.js App Router, TypeScript strict, Tailwind, TanStack Query. The API client
is typed from the OpenAPI document the backend generates
(`packages/contracts/openapi.json` → `packages/contracts/src/schema.ts`).
Pages only render data the API returned; there is no mock data in production code.

## Repository layout (current)

```
apps/web                 Next.js frontend
services/api             Spring Boot control plane
services/worker          execution worker
packages/contracts       OpenAPI document + generated TypeScript types
benchmarks/bug-corpus    original sample repositories with documented seeded defects
infrastructure/docker    Dockerfiles and compose files
docs                     design docs, backlog, progress
```
Directories from the long-term plan are added when their modules are built.
