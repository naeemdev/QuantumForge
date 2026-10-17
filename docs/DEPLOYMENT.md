# Deployment

Cloud SaaS and self-hosted installations run the same images. They differ in
configuration: identity provider, object storage, worker isolation and scale.

## Topology

```
Internet ──TLS──▶ ingress / reverse proxy ──▶ web (3000)
                                          └─▶ api public port (8080)
                  (never routes the api internal port)

private network:  api ──▶ PostgreSQL (managed or HA)
                  api ──▶ S3-compatible object storage

worker network:   worker hosts ──▶ api internal port (8081) only
                  (no route to PostgreSQL, object storage, cloud metadata or the internet)
```

## Requirements

| Component | Production setting |
|---|---|
| API | `SPRING_PROFILES_ACTIVE=prod`, ≥ 2 replicas behind the ingress, `QF_OIDC_ISSUER` (+ audience), `QF_STORAGE_TYPE=s3` |
| PostgreSQL | 16+, external or managed, TLS, point-in-time recovery enabled |
| Object storage | S3 or compatible; bucket private; versioning and lifecycle rules per retention policy |
| Workers | Dedicated hosts or node pool, `QF_ISOLATION_MODE=container-gvisor` (or microVM), runsc installed, egress blocked except to the API internal endpoint, metadata endpoint blocked, no other workloads |
| Secrets | From the platform secret store (Kubernetes secrets with KMS, Vault, cloud secret manager). Worker tokens ≥ 32 characters, one per worker pool |
| TLS | Terminated at the ingress; HSTS enabled |

The `prod` profile refuses to start with dev login, dev isolation, filesystem
storage, a missing issuer, or short worker tokens.

## Kubernetes outline

- `api` Deployment: two Services. `api-public` (8080) is referenced by the Ingress. `api-internal` (8081) is ClusterIP and reachable only from the worker namespace via NetworkPolicy.
- `web` Deployment and Service behind the same Ingress.
- `worker` Deployment in its own namespace on a tainted node pool with the gVisor RuntimeClass. NetworkPolicy egress allows only `api-internal`.
- Probes: liveness `GET /health/live`, readiness `GET /health/ready`.
- Graceful shutdown: the API drains for 30 s. Workers stop leasing on SIGTERM; a job in progress is abandoned, its lease expires and the job is retried on another worker.

## Observability

- Structured console logs with request ids; ship them to the central log store.
- Health endpoints for orchestration. Metrics and OpenTelemetry tracing export are planned (backlog), using Spring Boot's Micrometer and OTLP support.

## Backups and recovery

- PostgreSQL: continuous archiving / PITR; test restores regularly.
- Object storage: bucket versioning. Snapshot blobs are content-addressed, so a restored database row always maps to the same key.
- Audit events are append-only in the database; export them to write-once storage for long-term retention.

## Upgrades

Flyway runs migrations at API startup; migrations are additive and backward
compatible within a release, so a rolling update can run old and new API pods
together. Workers and API communicate through the versioned `/internal/v1` protocol,
and unknown fields are ignored on both sides.
