# Security Model

QuantumForge stores customers' private source code and executes it. The design
assumes that **every repository is hostile** and that **any one control can fail**.

## Trust zones

| Zone | Components | May access |
|---|---|---|
| Trusted control plane | API, web console, PostgreSQL, object storage | Everything it needs; never executes repository code |
| Worker host | Worker process | Only the API's internal port, with a worker token. No DB, no object storage, no tenant secrets |
| Job sandbox | Untrusted job process | Its own extracted snapshot and an output directory. No network in container modes |

## Authentication

- **Users:** OIDC access tokens, validated for signature, expiry, issuer and (when configured) audience. Tokens are routed by issuer; unknown issuers are rejected. Users are provisioned from `(issuer, subject)`; email is never used as identity.
- **Development login** issues RS256 tokens from a key generated at startup. It exists only when `quantumforge.auth.dev-login.enabled=true`, prints a warning, and the `prod` profile refuses to start with it on.
- **Personal access tokens** (`qf_pat_…`, 256-bit random) are stored as SHA-256 hashes, scoped to one organization, expire (max 365 days), can be revoked, and are revoked automatically when the member is removed. They cannot create organizations, accept invitations or mint other tokens. Their permissions are the owner's *current* permissions, evaluated live.
- **Workers** authenticate with their own tokens (hash stored in `workers`). Worker tokens are accepted only on the internal port.
- A presented but invalid credential is rejected with 401. It never degrades to an anonymous request.

## Authorization

- RBAC: built-in roles (owner > admin > member > viewer) map to permissions in `role_permissions`. Checks happen in the backend on every request (`AccessService.require`); the UI only hides controls for convenience.
- Resource-level checks resolve the owning organization of the resource (project, repository, snapshot, job), then require membership and permission there.
- **Non-members get 404**, so resource IDs cannot be probed across tenants.
- Role changes cannot grant or modify a role ranked above the actor's own, and an organization always keeps at least one owner (checked under a row lock).
- Invitations are single-use, expire after 7 days, and only work for the account whose email matches.

## Tenant isolation

1. The tenant is derived from the authenticated membership, never from input.
2. Every query filters by `tenant_id`.
3. Composite foreign keys `(tenant_id, parent_id)` make cross-tenant references impossible to write.
4. Object storage keys are prefixed `tenants/{tenantId}/` and are built only from validated segments.
5. Artifact and source downloads go through the API after authorization; there are no public blob URLs.
6. Planned: PostgreSQL row-level security as an additional layer (DECISIONS D-009).

The acceptance test checks that a second tenant gets 404 on every project, snapshot,
source, job, result, artifact, listing and dashboard endpoint of the first.

## Source ingestion

- Archives: size cap before reading, ZIP read in stream order (the central directory is not trusted), per-entry name normalisation, and rejection of absolute paths, drive letters, `..`, control characters, duplicate names (case-insensitive), symlinks, hard links, devices and FIFOs. Limits on entry count, per-file size (oversized files are skipped and reported), total size and **compression ratio** (zip bombs).
- Entries are never written under their own names: they are staged by index and repacked into a canonical tar.gz with fixed modes (0644) and timestamps.
- Git: `https://` only, allowlisted hosts, no embedded credentials, no custom ports, no query strings. The resolved address must not be private, loopback or link-local (blocks cloud metadata and internal services). Refs are validated so they cannot become options (`--upload-pack=…`). `git` runs via an argument vector with hooks disabled, all protocols except HTTPS disabled, no credential helpers or prompts, redirects off, submodules off, LFS smudging off, and a scrubbed environment. Symlinks in the checkout are skipped, never followed.
- Repository hooks are never executed.

## Execution isolation

The API refuses to dispatch to a worker whose isolation mode is not permitted.

| Mode | What it gives | Use |
|---|---|---|
| `dev-process` | Scrubbed environment, private HOME/TMPDIR, wall-clock timeout with process-tree kill, bounded output capture, `prlimit` (no core dumps, 256 MiB file size, 1024 files), private network namespace when user namespaces are available, V8 heap cap. **Not a security boundary.** | Local development and CI only; requires `allow-dev-isolation=true`, which `prod` forbids |
| `container` | Fresh container per job: non-root (65534), read-only root, read-only source, `--network none`, all capabilities dropped, `no-new-privileges`, pids/memory/CPU limits, 64 MiB `/tmp`. Shares the host kernel. | Staging, trusted code |
| `container-gvisor` | As above with the `runsc` runtime (user-space kernel). | Production minimum |
| `microvm` | Reserved for Firecracker/Kata-based workers. | Planned |

Controls in every mode:
- Jobs run **presets**, not arbitrary commands. Test patterns are validated so they cannot inject options or escape the snapshot.
- The worker verifies the snapshot SHA-256 against the lease before extraction, and extracts only regular files with safe names.
- Worker secrets (worker token, API URL) never enter the job environment. A unit test checks this.
- Timeouts are enforced by the worker. The API also stops extending leases past the deadline, and the reaper marks overrunning jobs `TIMED_OUT`.
- Output is capped per stream; overflow is drained and marked `truncated`.
- JUnit reports are parsed with DTDs and external entities disabled (XXE), and every field is bounded again by the API.
- The Docker socket is never mounted into a job container. Worker hosts must not run trusted services.

The environment record of every attempt states the isolation mode and whether it is
a hostile-code boundary. The UI shows a warning on runs that used `dev-process`.

## Transport and headers

- The API is stateless with bearer tokens only, so no cookies are issued. CSRF protection is therefore disabled deliberately.
- CORS allows only configured origins, without credentials.
- Security headers: `Content-Security-Policy: default-src 'none'; frame-ancestors 'none'` on the API, `X-Frame-Options: DENY`, `nosniff`, `Referrer-Policy`. The web console sets frame, sniffing, referrer and permissions policies.
- TLS terminates at the ingress (see `docs/DEPLOYMENT.md`). The internal port is never exposed through it; `PortSeparationFilter` also refuses `/internal` on the public port and user routes on the internal one.

## Audit and logging

- `audit_events` is append-only, enforced by a database trigger (tested). It records organization creation, membership and role changes, invitations, token creation and revocation, project and repository changes, snapshot imports, job submission and cancellation, worker completions, and permission denials.
- Logs carry the request id. Tokens, lease tokens and secrets are never logged. Git output is redacted before it is returned.

## Secrets

- No credentials are committed. `.env.example` contains placeholders only.
- The `prod` profile requires an OIDC issuer and S3 storage, forbids dev login and dev isolation, and rejects short worker tokens.
- Private Git credentials (M2) will be encrypted at rest with a KMS-held key, scoped to a repository, and never sent to workers.

## Known gaps (tracked in the backlog)

- Rate limiting is per API instance (in memory).
- No row-level security yet.
- Retention and deletion policies for snapshots and artifacts are not implemented yet.
- The container and gVisor sandboxes are implemented but were not executed in the development environment used to build M1, which has no Docker daemon. CI builds the images; an end-to-end container-mode test is in the backlog.
- Dependency vulnerability scanning in CI is not configured yet.
