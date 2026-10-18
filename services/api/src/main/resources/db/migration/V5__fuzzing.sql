-- Milestone 3: fuzz campaigns, their targets, and reproductions of the defects they find.

ALTER TABLE analysis_jobs DROP CONSTRAINT analysis_jobs_job_type_check;
ALTER TABLE analysis_jobs ADD CONSTRAINT analysis_jobs_job_type_check
    CHECK (job_type IN ('TEST_EXECUTION', 'STATIC_ANALYSIS', 'FUZZ_CAMPAIGN'));

ALTER TABLE job_artifacts DROP CONSTRAINT job_artifacts_kind_check;
ALTER TABLE job_artifacts ADD CONSTRAINT job_artifacts_kind_check
    CHECK (kind IN ('STDOUT', 'STDERR', 'JUNIT_XML', 'ENVIRONMENT', 'ANALYSIS_REPORT', 'FUZZ_REPORT', 'OTHER'));

-- Where a finding comes from and how strong its evidence is. Static analysis only
-- ever suspects; fuzzing has executed the code and observed the failure.
ALTER TABLE findings
    ADD COLUMN source         text NOT NULL DEFAULT 'STATIC_ANALYSIS' CHECK (source IN ('STATIC_ANALYSIS', 'FUZZING')),
    ADD COLUMN evidence_level text NOT NULL DEFAULT 'SUSPECTED' CHECK (evidence_level IN ('SUSPECTED', 'OBSERVED', 'REPRODUCED'));
CREATE INDEX findings_project_source_idx ON findings (project_id, source, status);

CREATE TABLE fuzz_campaigns (
    id                     uuid PRIMARY KEY,
    tenant_id              uuid        NOT NULL,
    project_id             uuid        NOT NULL,
    snapshot_id            uuid        NOT NULL,
    job_id                 uuid        NOT NULL UNIQUE,
    engine_id              text        NOT NULL,
    engine_version         text        NOT NULL,
    seed                   bigint      NOT NULL,
    iterations_per_target  integer     NOT NULL,
    call_timeout_ms        integer     NOT NULL,
    corpus_entries         integer     NOT NULL,
    targets_tested         integer     NOT NULL,
    targets_skipped        integer     NOT NULL,
    total_calls            bigint      NOT NULL,
    failures               integer     NOT NULL,
    duration_ms            bigint      NOT NULL,
    oracles                jsonb       NOT NULL,
    limitations            jsonb       NOT NULL,
    created_at             timestamptz NOT NULL,
    FOREIGN KEY (tenant_id, project_id) REFERENCES projects (tenant_id, id),
    FOREIGN KEY (tenant_id, snapshot_id) REFERENCES source_snapshots (tenant_id, id),
    FOREIGN KEY (tenant_id, job_id) REFERENCES analysis_jobs (tenant_id, id),
    UNIQUE (tenant_id, id)
);
CREATE INDEX fuzz_campaigns_project_idx ON fuzz_campaigns (project_id, created_at DESC);

CREATE TABLE fuzz_targets (
    tenant_id       uuid    NOT NULL,
    campaign_id     uuid    NOT NULL,
    path            text    NOT NULL,
    export_name     text    NOT NULL,
    line            integer NOT NULL,
    signature       text,
    status          text    NOT NULL CHECK (status IN ('TESTED', 'SKIPPED')),
    skip_reason     text,
    iterations      integer NOT NULL DEFAULT 0,
    replayed        integer NOT NULL DEFAULT 0,
    rejected_inputs integer NOT NULL DEFAULT 0,
    failures        integer NOT NULL DEFAULT 0,
    duration_ms     bigint  NOT NULL DEFAULT 0,
    FOREIGN KEY (tenant_id, campaign_id) REFERENCES fuzz_campaigns (tenant_id, id),
    PRIMARY KEY (campaign_id, path, export_name)
);

-- One row per failure a campaign observed: the minimal input, how it was found,
-- whether it reproduced in fresh processes, and a test that replays it.
CREATE TABLE defect_reproductions (
    id                uuid PRIMARY KEY,
    tenant_id         uuid        NOT NULL,
    finding_id        uuid        NOT NULL,
    campaign_id       uuid        NOT NULL,
    job_id            uuid        NOT NULL,
    snapshot_id       uuid        NOT NULL,
    path              text        NOT NULL,
    export_name       text        NOT NULL,
    oracle            text        NOT NULL CHECK (oracle IN ('runtime-error', 'non-finite-result', 'timeout', 'crash')),
    message           text        NOT NULL,
    error_name        text,
    stack             text,
    input             jsonb       NOT NULL,
    original_input    jsonb       NOT NULL,
    shrink_steps      integer     NOT NULL,
    attempts          integer     NOT NULL CHECK (attempts > 0),
    reproduced        integer     NOT NULL CHECK (reproduced >= 0 AND reproduced <= attempts),
    reproduction_test text        NOT NULL,
    created_at        timestamptz NOT NULL,
    FOREIGN KEY (tenant_id, finding_id) REFERENCES findings (tenant_id, id),
    FOREIGN KEY (tenant_id, campaign_id) REFERENCES fuzz_campaigns (tenant_id, id),
    FOREIGN KEY (tenant_id, job_id) REFERENCES analysis_jobs (tenant_id, id),
    FOREIGN KEY (tenant_id, snapshot_id) REFERENCES source_snapshots (tenant_id, id),
    UNIQUE (finding_id, job_id)
);
CREATE INDEX defect_reproductions_finding_idx ON defect_reproductions (finding_id, created_at DESC);
