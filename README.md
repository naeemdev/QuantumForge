# QuantumForge

**Break your code. Prove your fix. Ship with confidence.**

QuantumForge is a multi-tenant platform for analysing, testing and verifying source
code, whether people or coding assistants wrote it. It runs untrusted code only in
isolated workers and keeps reproducible evidence for every result: the exact
snapshot, runtime, limits, logs and per-test outcomes.

> **Status:** Milestone 1 (platform foundation) is complete. Analysis, Break My Code,
> repair verification and the specialised engines are planned. See
> [docs/ROADMAP.md](docs/ROADMAP.md) and [docs/PROGRESS.md](docs/PROGRESS.md).

## What works today

- Organizations, workspaces, invitations, role-based access control, personal API tokens and an append-only audit trail
- OIDC authentication (plus an explicit development login)
- Source snapshots from archive uploads or public Git repositories: validated against traversal, link, zip-bomb and SSRF tricks, then canonicalised and content-addressed
- A job queue with idempotent submission, quotas, leasing, heartbeats, cancellation, retries and lease recovery
- An execution worker that verifies snapshots, runs tests in a sandbox and uploads logs, JUnit results and an environment record
- A web console for all of the above, including live job timelines, test results, logs and artifacts
- A seeded bug corpus with documented defects, regression tests and fix patches

## Repository layout

```
apps/web                 Next.js web console
services/api             Kotlin / Spring Boot control plane
services/worker          Execution worker and sandboxes
packages/contracts       OpenAPI document and generated TypeScript types
benchmarks/bug-corpus    Original sample code with documented seeded defects
infrastructure/docker    Container images
docs                     Architecture, security, database, API, roadmap, progress
```

## Quick start

```bash
cp .env.example .env    # replace the placeholder secrets
docker compose --profile dev-worker up --build
```

Then open http://localhost:3000. To run without Docker, and for tests, see
[docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).

## Documentation

- [Product vision](docs/PRODUCT_VISION.md)
- [Architecture](docs/ARCHITECTURE.md) · [Decisions](docs/DECISIONS.md)
- [Security model](docs/SECURITY_MODEL.md)
- [Database design](docs/DATABASE_DESIGN.md) · [API contracts](docs/API_CONTRACTS.md)
- [Development](docs/DEVELOPMENT.md) · [Deployment](docs/DEPLOYMENT.md)
- [Roadmap](docs/ROADMAP.md) · [Backlog](docs/IMPLEMENTATION_BACKLOG.md) · [Progress](docs/PROGRESS.md)

## Licence

Proprietary. All rights reserved.
