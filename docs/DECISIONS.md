# Architecture Decisions

Short records of decisions that shape the codebase. Newest last. Status is
*Accepted* unless noted.

### D-001 Modular monolith for the control plane
One Spring Boot application with module packages (`identity`, `access`, `projects`,
`source`, `jobs`, `testing`, `audit`, `storage`). Splitting services early would add
network failure modes and distributed transactions without a scaling reason.
**Exception:** the execution worker is a separate process from day one because it
handles untrusted code (D-006).

### D-002 Kotlin, Spring Boot 4, Java 21
Spring Boot 4.0 (Jackson 3, Spring Framework 7) on Java 21 LTS, Kotlin 2.2. Virtual
threads are enabled for request handling.

### D-003 Spring JDBC (`JdbcClient`) instead of JPA
Every query must carry a tenant predicate, several operations depend on
PostgreSQL specifics (`FOR UPDATE SKIP LOCKED`, partial indexes, `ON CONFLICT`,
jsonb), and optimistic locking is done with explicit `version` predicates. Explicit
SQL keeps the tenant filter visible in code review. JPA can be adopted per module
later if a module benefits from it.

### D-004 UUIDv7 identifiers generated in the application
Time-ordered for index locality, random enough to be unguessable, and known before
insert (useful for blob keys and events).

### D-005 Tenant isolation through composite foreign keys
Each parent exposes `UNIQUE (tenant_id, id)`; children reference `(tenant_id,
parent_id)`. Together with tenant-filtered queries and 404-for-non-members, this
makes cross-tenant leaks require multiple independent bugs.

### D-006 Workers pull work over an internal HTTP API
Workers lease jobs over HTTP on a separate internal port and never touch the
database or object storage. This keeps credentials out of the untrusted zone, lets
workers run anywhere that can reach one port, and lets the API validate every
report. Leases use `SKIP LOCKED`; reports are accepted only with the current
attempt's lease token.

### D-007 Lease stored on the attempt
`job_attempts` carries the lease token hash and expiry instead of a separate
`worker_leases` table. Lease and attempt have the same lifecycle, and one row makes
"only the current attempt may report" a single predicate.

### D-008 Presets instead of arbitrary commands
Jobs name a preset (`NODE_TEST` in M1). The API decides what runs; users supply
bounded parameters only (patterns, limits). New toolchains are added as presets with
their own images. This removes shell injection and makes environments reproducible.

### D-009 Row-level security deferred (Proposed)
RLS keyed on a transaction-local `app.tenant_id` is planned as a further layer. It is
deferred until the access patterns that cross tenants by design (a user's own
memberships across organizations, the dispatcher) are restructured around it.

### D-010 Canonical, content-addressed snapshots
Uploads are repacked into a deterministic tar.gz (sorted, fixed modes and times),
and its SHA-256 identifies the snapshot. Identical trees deduplicate regardless of
archive format; workers verify the digest before running anything.

### D-011 Single top-level folder stripped from uploaded archives
Archives from code hosts wrap everything in `name-branch/`. Stripping a sole
top-level directory makes paths match the repository. This applies to archive
uploads only, not Git checkouts.

### D-012 Bearer tokens only; no cookies
The browser keeps the access token in `sessionStorage` and sends it in the
Authorization header. With no cookies, CSRF does not apply. The trade-off is XSS
exposure of the token; mitigations are a strict UI with no HTML injection, CSP
headers, and short-lived OIDC tokens.

### D-013 Generated TypeScript contracts
The frontend client is typed from the OpenAPI document the backend exports. CI fails
on drift. Response fields that cannot be null are marked `required` by an OpenAPI
customiser so the generated types are precise.

### D-014 Honest result vocabulary
`SUCCEEDED` means "ran, and the configured tests passed". `FAILED` means "ran, and
tests or the command failed". Platform faults are `INFRA_ERROR`, never `FAILED`. The
UI states that passing tests do not prove absence of defects, and labels
development isolation as not a security boundary.

### D-015 Integration tests against real PostgreSQL
Integration tests boot the full application against PostgreSQL
(`QF_TEST_DB_URL`) rather than an in-memory database, because the queue, locking
and constraint behaviour are PostgreSQL-specific. CI provides PostgreSQL as a
service; Testcontainers can be added where Docker is available.

### D-016 UI components kept in the web app for now
`packages/ui` from the long-term layout is not created yet; primitives live in
`apps/web/src/components/ui` until a second consumer exists.

### D-017 Analysis runs in the sandbox, not the API
Parsing and type-checking untrusted repositories is attacker-controlled input to a
large compiler. The adapter therefore runs as a job in the worker sandbox and hands
back a JSON report that the API validates against a schema and size limits before
storing anything. A malformed report fails the job; it never partially ingests.

### D-018 Adapters declare capabilities; the platform repeats them
The report carries capability levels and plain-language limitations. They are
stored with every index and shown in the UI, so a syntactic result is never
presented as semantic understanding.

### D-019 Content fingerprints for findings
Findings are identified by rule, file, owning symbol and whitespace-normalised
code rather than by line number. This survives unrelated edits, keeps triage
decisions attached, and makes "fixed" mean "this code pattern is gone", not "the
line moved".

### D-020 Static findings are suspected, always
A rule match is a suspicion. Findings carry `evidenceLevel = SUSPECTED`; human
confirmation is recorded with the reviewer, but it does not turn a static match
into a reproduced defect. Reproduced defects come from Break My Code (D-024, D-025).

### D-021 Self-hosted Monaco
The code viewer bundles Monaco's editor core and Monarch grammars instead of loading
them from a CDN, so self-hosted and air-gapped installations work, and no source
code view depends on third-party script hosts.

### D-022 Fuzzing uses generic oracles, not guessed specifications
The fuzzer reports only what is wrong regardless of intent: engine errors on
type-correct input, NaN/Infinity from finite input, hangs and crashes. It does not
guess what a function *should* return, so wrong-but-plausible results (D1's
rounding) stay out of reach until property or specification input exists. Errors the
code throws deliberately count as input rejection, not failures.

### D-023 One child process per target, limits enforced by the process
Each fuzzed function runs in its own Node.js child process with a V8 heap limit and
a per-call timer in the parent. Worker threads were tried first: their heap limits
are not enforced for large allocations, so a runaway function could exhaust the
sandbox's memory and take the whole campaign down. A process can be killed
without trace and its `process.exit` cannot end the campaign. The whole fuzzer still
runs inside the worker sandbox; the child processes are a second layer, not the
security boundary.

### D-024 A fuzzing finding is fixed only when its input stops failing
Not finding a failure again proves nothing: random generation may simply miss it.
Every campaign therefore receives the stored reproduction inputs of the project's
open fuzzing findings and replays them before generating new inputs. A finding
becomes `FIXED` only when its own input was replayed and no longer fails the same
way. Findings are tracked per source (static analysis, fuzzing): an analysis never
resolves a fuzzing finding and a campaign never resolves a static one.

### D-025 Evidence levels
`SUSPECTED` (a static rule matched), `OBSERVED` (a campaign saw the failure but not
every fresh re-run reproduced it), `REPRODUCED` (every one of the fresh re-runs
failed the same way). The level is stored per finding and shown wherever the
finding appears.
