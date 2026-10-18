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
                 │   ├─ analysis   (code index, rules, findings and their lifecycle) │
                 │   ├─ audit      (append-only security audit trail)                │
                 │   └─ storage    (BlobStore: filesystem or S3-compatible)             │
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

## Code intelligence (M2)

Analysis is a job type (`STATIC_ANALYSIS`) on the same queue. Parsing untrusted
code is still processing hostile input, so it runs in the worker sandbox, never in
the API.

```
worker sandbox: node language-packs/typescript/src/cli.ts --root src --out out/analysis.json
        │  (TypeScript compiler API: parse, bind, type-check; never executes project code)
        ▼
analysis.json ──upload──▶ job_artifacts (ANALYSIS_REPORT)
        │
API, in the job's completion transaction:
  validate against the language-sdk schema and limits
  → code_indexes / code_files / code_symbols / code_imports / code_calls
  → rules, rule_versions (registered from the report)
  → findings (+ occurrences, status history)
```

- **Adapter contract** (`packages/language-sdk`): every adapter writes one
  `AnalysisReport` and declares its capability levels (`none`, `syntactic`,
  `semantic`) and limitations. The UI shows them next to the results.
- **TypeScript adapter** (`language-packs/typescript`): symbols, resolved imports,
  a direct call graph through the type checker, cyclomatic complexity, syntax
  diagnostics, and nine versioned rules.
- **Findings** are keyed by a fingerprint (rule + file + owning symbol + normalised
  code + occurrence index), so they keep their identity when unrelated lines move.
  Lifecycle: new → `OPEN`; seen again after `FIXED` → `REOPENED`; absent from an
  analysis of a snapshot at least as new as where it was last seen, in a file that
  was analysed → `FIXED`. Human decisions (`CONFIRMED`, `FALSE_POSITIVE`,
  `ACCEPTED_RISK`) are never overwritten by analyses. Analysing an older snapshot
  cannot reopen what a newer one fixed.
- **Evidence level:** static findings are always presented as *suspected*.

## Break My Code (M3)

Fuzzing is a third job type (`FUZZ_CAMPAIGN`, preset `TS_FUZZ`). Unlike analysis it
executes project code, which is why it exists only as a sandboxed job.

```
API: submit → config.fuzz = { seed, iterations, call limit, corpus }
        corpus = latest reproduction input of every open fuzzing finding
worker sandbox: node language-packs/typescript/src/fuzz/cli.ts --root src --corpus out/corpus.json --out out/fuzz.json
   discover   exported functions whose parameter types can be generated (compiler API);
              classes, callbacks, generics, any/unknown and built-ins are skipped with a reason
   per target one child process (heap limit), parent enforces the per-call timeout
              1. replay corpus inputs for this function
              2. generate inputs from the types (seeded PRNG, boundary values first)
              3. oracles: engine error · NaN/Infinity from finite input · timeout · crash
              4. on a new failure: shrink greedily, re-run 3× in fresh processes,
                 emit a node:test regression test
        ▼
fuzz.json ──upload──▶ job_artifacts (FUZZ_REPORT)
        │
API, in the job's completion transaction:
  validate → fuzz_campaigns, fuzz_targets
  → findings (source FUZZING, rules QF-FUZZ-001..004, evidence REPRODUCED/OBSERVED)
  → defect_reproductions
  → FIXED for corpus findings whose input was replayed and no longer fails
```

A campaign is reproducible from its seed. Failures are identified by function,
oracle and error signature (not the input), so the same defect found with a
different input is the same finding.

### Mutation testing

A fourth job type (`MUTATION_TEST`, preset `TS_MUTATION`).

```
worker sandbox: node language-packs/typescript/src/mutate/cli.ts --root src --work out/work --out out/mutation.json [--tests <glob>]...
   find mutants      AST of non-test files: comparison, equality, arithmetic (not string +),
                     logical, negation, condition, constant, call removal; each must still parse
   baseline          node:test with a JSON-lines reporter → per-test pass/fail
   per mutant        apply in one of N work copies, run the suite, restore
                     KILLED if a baseline-passing test fails or goes missing,
                     TIMEOUT on the limit, SURVIVED otherwise
        ▼
mutation.json ──upload──▶ job_artifacts (MUTATION_REPORT) ──API──▶ mutation_runs, mutation_results
```

## Frontend

Next.js App Router, TypeScript strict, Tailwind, TanStack Query. Source is shown in a
read-only, self-hosted Monaco editor (no CDN; syntax colouring only, no language
services) with findings rendered as editor markers. The API client
is typed from the OpenAPI document the backend generates
(`packages/contracts/openapi.json` → `packages/contracts/src/schema.ts`).
Pages only render data the API returned; there is no mock data in production code.

## Repository layout (current)

```
apps/web                 Next.js frontend
services/api             Spring Boot control plane
services/worker          execution worker
packages/contracts       OpenAPI document + generated TypeScript types
packages/language-sdk    language adapter contract (report schema, capabilities)
language-packs/typescript TypeScript/JavaScript adapter, rules, fuzzer (src/fuzz) and mutation runner (src/mutate)
benchmarks/bug-corpus    original sample repositories with documented seeded defects
infrastructure/docker    Dockerfiles and compose files
docs                     design docs, backlog, progress
```
Directories from the long-term plan are added when their modules are built.
