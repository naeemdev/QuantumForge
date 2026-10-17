-- Code intelligence: static analysis jobs, the code index they produce, the rule
-- catalogue and findings with version-aware history.

ALTER TABLE analysis_jobs DROP CONSTRAINT analysis_jobs_job_type_check;
ALTER TABLE analysis_jobs ADD CONSTRAINT analysis_jobs_job_type_check CHECK (job_type IN ('TEST_EXECUTION', 'STATIC_ANALYSIS'));

ALTER TABLE job_artifacts DROP CONSTRAINT job_artifacts_kind_check;
ALTER TABLE job_artifacts ADD CONSTRAINT job_artifacts_kind_check
    CHECK (kind IN ('STDOUT', 'STDERR', 'JUNIT_XML', 'ENVIRONMENT', 'ANALYSIS_REPORT', 'OTHER'));

-- Rules are defined by language adapters and registered the first time a report
-- mentions them. Every version a finding was produced by is kept.
CREATE TABLE rules (
    id             text PRIMARY KEY CHECK (id ~ '^[A-Z][A-Z0-9-]{2,40}$'),
    name           text        NOT NULL,
    category       text        NOT NULL,
    languages      text[]      NOT NULL,
    latest_version integer     NOT NULL,
    created_at     timestamptz NOT NULL,
    updated_at     timestamptz NOT NULL
);

CREATE TABLE rule_versions (
    rule_id     text        NOT NULL REFERENCES rules (id),
    version     integer     NOT NULL CHECK (version >= 1),
    summary     text        NOT NULL,
    description text        NOT NULL,
    remediation text        NOT NULL,
    severity    text        NOT NULL CHECK (severity IN ('critical', 'high', 'medium', 'low', 'info')),
    confidence  text        NOT NULL CHECK (confidence IN ('high', 'medium', 'low')),
    created_at  timestamptz NOT NULL,
    PRIMARY KEY (rule_id, version)
);

-- One code index per successful analysis job. It records which adapter, with
-- which declared capabilities and limitations, produced the data below it.
CREATE TABLE code_indexes (
    id              uuid PRIMARY KEY,
    tenant_id       uuid        NOT NULL,
    project_id      uuid        NOT NULL,
    snapshot_id     uuid        NOT NULL,
    job_id          uuid        NOT NULL UNIQUE,
    adapter_id      text        NOT NULL,
    adapter_version text        NOT NULL,
    toolchain       text        NOT NULL,
    capabilities    jsonb       NOT NULL,
    limitations     jsonb       NOT NULL,
    stats           jsonb       NOT NULL,
    created_at      timestamptz NOT NULL,
    FOREIGN KEY (tenant_id, project_id) REFERENCES projects (tenant_id, id),
    FOREIGN KEY (tenant_id, snapshot_id) REFERENCES source_snapshots (tenant_id, id),
    FOREIGN KEY (tenant_id, job_id) REFERENCES analysis_jobs (tenant_id, id),
    UNIQUE (tenant_id, id)
);
CREATE INDEX code_indexes_snapshot_idx ON code_indexes (snapshot_id, created_at DESC);

CREATE TABLE code_files (
    tenant_id      uuid    NOT NULL,
    index_id       uuid    NOT NULL,
    path           text    NOT NULL,
    language       text    NOT NULL,
    parsed         boolean NOT NULL,
    line_count     integer NOT NULL,
    diagnostics    jsonb   NOT NULL,
    skipped_reason text,
    PRIMARY KEY (index_id, path),
    FOREIGN KEY (tenant_id, index_id) REFERENCES code_indexes (tenant_id, id)
);

CREATE TABLE code_symbols (
    tenant_id    uuid    NOT NULL,
    index_id     uuid    NOT NULL,
    symbol_key   text    NOT NULL,
    kind         text    NOT NULL CHECK (kind IN ('function', 'class', 'method', 'interface', 'type', 'enum', 'variable')),
    name         text    NOT NULL,
    container    text,
    path         text    NOT NULL,
    start_line   integer NOT NULL,
    start_column integer NOT NULL,
    end_line     integer NOT NULL,
    end_column   integer NOT NULL,
    exported     boolean NOT NULL,
    complexity   integer,
    signature    text,
    PRIMARY KEY (index_id, symbol_key),
    FOREIGN KEY (tenant_id, index_id) REFERENCES code_indexes (tenant_id, id)
);
CREATE INDEX code_symbols_name_idx ON code_symbols (index_id, lower(name));
CREATE INDEX code_symbols_path_idx ON code_symbols (index_id, path);

CREATE TABLE code_imports (
    tenant_id    uuid    NOT NULL,
    index_id     uuid    NOT NULL,
    from_path    text    NOT NULL,
    specifier    text    NOT NULL,
    to_path      text,
    external     boolean NOT NULL,
    package_name text,
    line         integer NOT NULL,
    FOREIGN KEY (tenant_id, index_id) REFERENCES code_indexes (tenant_id, id)
);
CREATE INDEX code_imports_index_idx ON code_imports (index_id, from_path);

CREATE TABLE code_calls (
    tenant_id uuid    NOT NULL,
    index_id  uuid    NOT NULL,
    from_key  text    NOT NULL,
    to_key    text    NOT NULL,
    path      text    NOT NULL,
    line      integer NOT NULL,
    PRIMARY KEY (index_id, from_key, to_key),
    FOREIGN KEY (tenant_id, index_id) REFERENCES code_indexes (tenant_id, id)
);

-- A finding is one issue in one project, identified across analyses by the
-- adapter's fingerprint (rule + file + owning symbol + normalised code), so it
-- keeps its identity, status and history when unrelated lines move.
CREATE TABLE findings (
    id                    uuid PRIMARY KEY,
    tenant_id             uuid        NOT NULL,
    organization_id       uuid        NOT NULL,
    project_id            uuid        NOT NULL,
    fingerprint           text        NOT NULL CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
    rule_id               text        NOT NULL REFERENCES rules (id),
    rule_version          integer     NOT NULL,
    severity              text        NOT NULL CHECK (severity IN ('critical', 'high', 'medium', 'low', 'info')),
    confidence            text        NOT NULL CHECK (confidence IN ('high', 'medium', 'low')),
    status                text        NOT NULL CHECK (status IN ('OPEN', 'CONFIRMED', 'FALSE_POSITIVE', 'ACCEPTED_RISK', 'FIXED', 'REOPENED')),
    message               text        NOT NULL,
    path                  text        NOT NULL,
    symbol_key            text,
    start_line            integer     NOT NULL,
    end_line              integer     NOT NULL,
    snippet               text        NOT NULL,
    first_seen_job_id     uuid        NOT NULL,
    first_seen_at         timestamptz NOT NULL,
    last_seen_job_id      uuid        NOT NULL,
    last_seen_snapshot_id uuid        NOT NULL,
    last_seen_at          timestamptz NOT NULL,
    resolved_at           timestamptz,
    -- Snapshot whose analysis no longer found the issue; older snapshots cannot reopen it.
    resolved_snapshot_id  uuid,
    created_at            timestamptz NOT NULL,
    created_by            uuid,
    updated_at            timestamptz NOT NULL,
    updated_by            uuid,
    version               bigint      NOT NULL DEFAULT 0,
    FOREIGN KEY (tenant_id, organization_id) REFERENCES organizations (tenant_id, id),
    FOREIGN KEY (tenant_id, project_id) REFERENCES projects (tenant_id, id),
    FOREIGN KEY (rule_id, rule_version) REFERENCES rule_versions (rule_id, version),
    UNIQUE (project_id, fingerprint),
    UNIQUE (tenant_id, id)
);
CREATE INDEX findings_project_status_idx ON findings (project_id, status, severity);

-- Evidence: where and how each analysis saw the finding. Never updated.
CREATE TABLE finding_occurrences (
    id           uuid PRIMARY KEY,
    tenant_id    uuid        NOT NULL,
    finding_id   uuid        NOT NULL,
    job_id       uuid        NOT NULL,
    snapshot_id  uuid        NOT NULL,
    rule_version integer     NOT NULL,
    severity     text        NOT NULL,
    message      text        NOT NULL,
    path         text        NOT NULL,
    start_line   integer     NOT NULL,
    start_column integer     NOT NULL,
    end_line     integer     NOT NULL,
    end_column   integer     NOT NULL,
    snippet      text        NOT NULL,
    created_at   timestamptz NOT NULL,
    FOREIGN KEY (tenant_id, finding_id) REFERENCES findings (tenant_id, id),
    FOREIGN KEY (tenant_id, job_id) REFERENCES analysis_jobs (tenant_id, id),
    FOREIGN KEY (tenant_id, snapshot_id) REFERENCES source_snapshots (tenant_id, id),
    UNIQUE (finding_id, job_id)
);
CREATE INDEX finding_occurrences_job_idx ON finding_occurrences (job_id);
CREATE INDEX finding_occurrences_snapshot_path_idx ON finding_occurrences (snapshot_id, path);

CREATE TABLE finding_status_history (
    id          uuid PRIMARY KEY,
    tenant_id   uuid        NOT NULL,
    finding_id  uuid        NOT NULL,
    from_status text,
    to_status   text        NOT NULL,
    reason      text,
    actor_type  text        NOT NULL CHECK (actor_type IN ('USER', 'API_TOKEN', 'SYSTEM')),
    actor_id    uuid,
    job_id      uuid,
    changed_at  timestamptz NOT NULL,
    FOREIGN KEY (tenant_id, finding_id) REFERENCES findings (tenant_id, id)
);
CREATE INDEX finding_status_history_finding_idx ON finding_status_history (finding_id, changed_at);

INSERT INTO permissions (code, description) VALUES ('finding.triage', 'Confirm, dismiss or accept findings');
INSERT INTO role_permissions (role_id, permission_code) VALUES
    ('org_owner', 'finding.triage'),
    ('org_admin', 'finding.triage'),
    ('org_member', 'finding.triage');
