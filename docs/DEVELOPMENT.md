# Development Guide

## Prerequisites

- JDK 21
- Node.js 22.6+ (22.22 used) with pnpm 10 (`corepack enable`)
- PostgreSQL 16 (local install or `docker compose up postgres`)
- git

## First-time setup

```bash
# Databases (local PostgreSQL)
sudo -u postgres psql -c "CREATE ROLE quantumforge LOGIN PASSWORD 'quantumforge'"
sudo -u postgres psql -c "CREATE DATABASE quantumforge OWNER quantumforge"
sudo -u postgres psql -c "CREATE DATABASE quantumforge_test OWNER quantumforge"

pnpm install
```

## Running locally without Docker

Use three terminals.

```bash
# 1. API (dev profile: dev login + a registered dev worker)
QF_DB_PASSWORD=quantumforge SPRING_PROFILES_ACTIVE=dev ./gradlew :services:api:bootRun

# 2. Worker (dev-process isolation; development only)
QF_CONTROL_PLANE_URL=http://localhost:8081 \
QF_WORKER_TOKEN=dev-worker-token-change-me \
QF_ISOLATION_MODE=dev-process \
./gradlew :services:worker:run

# 3. Web console
NEXT_PUBLIC_API_URL=http://localhost:8080 pnpm web:dev
```

Open http://localhost:3000, sign in with any email (dev login), create an
organization and a project, add an *Upload* repository, upload
`benchmarks/bug-corpus/sample-commerce-api` as a `.tar.gz` or `.zip`, then **Run tests**.

Stop each process with Ctrl+C. The API shuts down gracefully (in-flight requests get
30 s). A worker stopped mid-job leaves its lease to expire, and the job is retried.

## Running with Docker Compose

```bash
cp .env.example .env           # then replace every value
docker compose --profile dev-worker up --build
# web: http://localhost:3000   api: http://localhost:8080

docker compose down            # stop, keep data
docker compose down -v         # stop and delete the database and blob volumes
```

Only the public API port is published. The worker port (8081) is reachable only on
the internal `workers` network.

## Tests

```bash
./gradlew test                                   # all backend tests (needs PostgreSQL)
QF_TEST_DB_URL=jdbc:postgresql://host:5432/db ./gradlew test
./gradlew :services:api:test --tests '*AcceptanceScenarioTest'

pnpm typecheck && pnpm lint && pnpm web:build    # frontend
scripts/verify-bug-corpus.sh                     # corpus still behaves as documented
```

Integration tests rebuild the schema in `quantumforge_test` once per run. They create
their own users and organizations, so they can run in any order. The acceptance test
runs a real worker and needs `node` and `git` on the PATH.

## End-to-end browser tests

```bash
sudo -u postgres psql -c "CREATE DATABASE quantumforge_e2e OWNER quantumforge"   # once
pnpm install
pnpm --filter @quantumforge/e2e exec playwright install chromium                 # once
scripts/e2e-stack.sh start     # builds and starts API, worker (with analyzer) and web
pnpm e2e
scripts/e2e-stack.sh stop
```

The specs in `tests/e2e/specs` drive the real UI: sign-in, onboarding, importing
the seeded sample, a test run (6 of 18 failing), an analysis (5 findings), triage
and code intelligence. Logs are in `build/e2e/`.

## Container sandbox tests

`ContainerSandboxTest` (worker) and `ContainerExecutionTest` (API) need Docker and
are skipped unless enabled:

```bash
pnpm --filter @quantumforge/lang-typescript deploy --prod --legacy /tmp/ts-analyzer
docker pull node:22.22-bookworm-slim
QF_TEST_DOCKER=1 QF_TEST_TS_ANALYZER=/tmp/ts-analyzer ./gradlew test
```

They check the isolation properties directly (uid 65534, read-only root and
source, no network, worker secrets absent, timeouts, runtime errors reported as
infrastructure errors) and run the M1/M2 acceptance flows in containers.

## Changing the API

1. Change the Kotlin controller or DTO.
2. `./gradlew :services:api:exportOpenApi` → `packages/contracts/openapi.json`
3. `pnpm contracts:generate` → `packages/contracts/src/schema.ts`
4. Commit both; CI fails on drift.

## Database changes

Add `V<n>__description.sql` under `services/api/src/main/resources/db/migration`.
Never edit an applied migration. Follow the conventions in `DATABASE_DESIGN.md`
(tenant id, composite foreign keys, audit stamps, version column).

## Configuration reference

| Variable | Default | Purpose |
|---|---|---|
| `QF_DB_URL`, `QF_DB_USER`, `QF_DB_PASSWORD` | local `quantumforge` | PostgreSQL |
| `QF_PORT` / `QF_INTERNAL_PORT` | 8080 / 8081 | Public and worker ports |
| `QF_OIDC_ISSUER`, `QF_OIDC_AUDIENCE` | — | OIDC resource server |
| `QF_DEV_LOGIN` | false | Development login (forbidden in `prod`) |
| `QF_STORAGE_TYPE` | filesystem | `filesystem` or `s3` |
| `QF_STORAGE_ROOT` | ./data/blobs | Filesystem store root |
| `QF_S3_ENDPOINT`, `QF_S3_BUCKET`, `QF_S3_REGION`, `QF_S3_ACCESS_KEY`, `QF_S3_SECRET_KEY` | — | S3-compatible store |
| `QF_ALLOW_DEV_ISOLATION` | false | Dispatch to `dev-process` workers (forbidden in `prod`) |
| `QF_CORS_ORIGINS` | http://localhost:3000 | Allowed browser origins |
| Worker: `QF_CONTROL_PLANE_URL`, `QF_WORKER_TOKEN`, `QF_ISOLATION_MODE`, `QF_WORK_ROOT`, `QF_CONTAINER_IMAGE` | — | Worker settings |
| Web: `NEXT_PUBLIC_API_URL`, `NEXT_PUBLIC_OIDC_CLIENT_ID` | — | Compiled into the web bundle |
