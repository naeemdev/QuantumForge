-- Job orchestration, execution evidence and parsed test results.

-- Registered execution workers. Workers are platform infrastructure, not tenant
-- data, so they carry no tenant_id. A worker authenticates with a token whose
-- SHA-256 is stored here.
CREATE TABLE workers (
    id             uuid PRIMARY KEY,
    name           text        NOT NULL UNIQUE,
    isolation_mode text        NOT NULL CHECK (isolation_mode IN ('dev-process', 'container', 'container-gvisor', 'microvm')),
    token_hash     bytea       NOT NULL UNIQUE,
    status         text        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    capabilities   jsonb       NOT NULL DEFAULT '{}'::jsonb,
    last_seen_at   timestamptz,
    created_at     timestamptz NOT NULL,
    updated_at     timestamptz NOT NULL
);

CREATE TABLE analysis_jobs (
    id               uuid PRIMARY KEY,
    tenant_id        uuid        NOT NULL,
    organization_id  uuid        NOT NULL,
    project_id       uuid        NOT NULL,
    snapshot_id      uuid        NOT NULL,
    job_type         text        NOT NULL CHECK (job_type IN ('TEST_EXECUTION')),
    state            text        NOT NULL CHECK (state IN ('QUEUED', 'LEASED', 'RUNNING', 'SUCCEEDED', 'FAILED',
                                                           'TIMED_OUT', 'CANCELLED', 'INFRA_ERROR')),
    priority         integer     NOT NULL DEFAULT 0 CHECK (priority BETWEEN -100 AND 100),
    config           jsonb       NOT NULL,
    idempotency_key  text CHECK (length(idempotency_key) BETWEEN 1 AND 200),
    request_hash     text,
    max_attempts     integer     NOT NULL DEFAULT 3 CHECK (max_attempts BETWEEN 1 AND 10),
    attempt_count    integer     NOT NULL DEFAULT 0,
    timeout_seconds  integer     NOT NULL CHECK (timeout_seconds BETWEEN 1 AND 3600),
    cancel_requested boolean     NOT NULL DEFAULT false,
    queued_at        timestamptz NOT NULL,
    started_at       timestamptz,
    finished_at      timestamptz,
    result_summary   jsonb,
    failure_reason   text,
    created_at       timestamptz NOT NULL,
    created_by       uuid        NOT NULL REFERENCES users (id),
    updated_at       timestamptz NOT NULL,
    updated_by       uuid,
    version          bigint      NOT NULL DEFAULT 0,
    FOREIGN KEY (tenant_id, organization_id) REFERENCES organizations (tenant_id, id),
    FOREIGN KEY (tenant_id, project_id) REFERENCES projects (tenant_id, id),
    FOREIGN KEY (tenant_id, snapshot_id) REFERENCES source_snapshots (tenant_id, id),
    UNIQUE (tenant_id, idempotency_key),
    UNIQUE (tenant_id, id)
);
-- The dispatcher scans this partial index only.
CREATE INDEX analysis_jobs_queue_idx ON analysis_jobs (priority DESC, queued_at) WHERE state = 'QUEUED';
CREATE INDEX analysis_jobs_project_idx ON analysis_jobs (project_id, created_at DESC);
CREATE INDEX analysis_jobs_org_idx ON analysis_jobs (organization_id, created_at DESC);
CREATE INDEX analysis_jobs_active_tenant_idx ON analysis_jobs (tenant_id) WHERE state IN ('QUEUED', 'LEASED', 'RUNNING');

-- One row per lease. The lease lives on the attempt (rather than a separate
-- worker_leases table) because an attempt and its lease have the same lifetime.
CREATE TABLE job_attempts (
    id               uuid PRIMARY KEY,
    tenant_id        uuid        NOT NULL,
    job_id           uuid        NOT NULL,
    attempt_number   integer     NOT NULL CHECK (attempt_number >= 1),
    worker_id        uuid        NOT NULL REFERENCES workers (id),
    lease_token_hash bytea       NOT NULL,
    leased_at        timestamptz NOT NULL,
    lease_expires_at timestamptz NOT NULL,
    heartbeat_at     timestamptz,
    started_at       timestamptz,
    finished_at      timestamptz,
    outcome          text CHECK (outcome IN ('SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELLED', 'INFRA_ERROR', 'LEASE_EXPIRED')),
    exit_code        integer,
    duration_ms      bigint,
    environment      jsonb,
    error_message    text,
    FOREIGN KEY (tenant_id, job_id) REFERENCES analysis_jobs (tenant_id, id),
    UNIQUE (job_id, attempt_number),
    UNIQUE (tenant_id, id)
);
CREATE INDEX job_attempts_open_lease_idx ON job_attempts (lease_expires_at) WHERE outcome IS NULL;

-- Ordered, append-only job timeline. The bigserial id doubles as the cursor for
-- event streaming.
CREATE TABLE job_events (
    id          bigserial PRIMARY KEY,
    tenant_id   uuid        NOT NULL,
    job_id      uuid        NOT NULL,
    attempt_id  uuid,
    event_type  text        NOT NULL,
    message     text        NOT NULL,
    data        jsonb       NOT NULL DEFAULT '{}'::jsonb,
    occurred_at timestamptz NOT NULL,
    FOREIGN KEY (tenant_id, job_id) REFERENCES analysis_jobs (tenant_id, id)
);
CREATE INDEX job_events_job_idx ON job_events (job_id, id);

CREATE TABLE job_artifacts (
    id          uuid PRIMARY KEY,
    tenant_id   uuid        NOT NULL,
    job_id      uuid        NOT NULL,
    attempt_id  uuid        NOT NULL,
    kind        text        NOT NULL CHECK (kind IN ('STDOUT', 'STDERR', 'JUNIT_XML', 'ENVIRONMENT', 'OTHER')),
    name        text        NOT NULL CHECK (name ~ '^[A-Za-z0-9._-]{1,100}$'),
    blob_key    text        NOT NULL,
    media_type  text        NOT NULL,
    size_bytes  bigint      NOT NULL CHECK (size_bytes >= 0),
    sha256      text        NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    truncated   boolean     NOT NULL DEFAULT false,
    created_at  timestamptz NOT NULL,
    FOREIGN KEY (tenant_id, job_id) REFERENCES analysis_jobs (tenant_id, id),
    FOREIGN KEY (tenant_id, attempt_id) REFERENCES job_attempts (tenant_id, id),
    UNIQUE (attempt_id, name)
);
CREATE INDEX job_artifacts_job_idx ON job_artifacts (job_id);

CREATE TABLE test_runs (
    id          uuid PRIMARY KEY,
    tenant_id   uuid        NOT NULL,
    job_id      uuid        NOT NULL,
    attempt_id  uuid        NOT NULL,
    framework   text        NOT NULL,
    total       integer     NOT NULL,
    passed      integer     NOT NULL,
    failed      integer     NOT NULL,
    skipped     integer     NOT NULL,
    errored     integer     NOT NULL,
    duration_ms bigint,
    created_at  timestamptz NOT NULL,
    FOREIGN KEY (tenant_id, job_id) REFERENCES analysis_jobs (tenant_id, id),
    FOREIGN KEY (tenant_id, attempt_id) REFERENCES job_attempts (tenant_id, id),
    UNIQUE (attempt_id),
    UNIQUE (tenant_id, id),
    CHECK (total = passed + failed + skipped + errored)
);

CREATE TABLE test_results (
    id              uuid PRIMARY KEY,
    tenant_id       uuid    NOT NULL,
    test_run_id     uuid    NOT NULL,
    ordinal         integer NOT NULL,
    suite           text,
    name            text    NOT NULL,
    status          text    NOT NULL CHECK (status IN ('PASSED', 'FAILED', 'SKIPPED', 'ERRORED')),
    duration_ms     bigint,
    failure_message text,
    failure_detail  text,
    FOREIGN KEY (tenant_id, test_run_id) REFERENCES test_runs (tenant_id, id),
    UNIQUE (test_run_id, ordinal)
);
