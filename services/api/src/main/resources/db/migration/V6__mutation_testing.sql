-- Milestone 3: mutation testing runs and the outcome of every mutant.

ALTER TABLE analysis_jobs DROP CONSTRAINT analysis_jobs_job_type_check;
ALTER TABLE analysis_jobs ADD CONSTRAINT analysis_jobs_job_type_check
    CHECK (job_type IN ('TEST_EXECUTION', 'STATIC_ANALYSIS', 'FUZZ_CAMPAIGN', 'MUTATION_TEST'));

ALTER TABLE job_artifacts DROP CONSTRAINT job_artifacts_kind_check;
ALTER TABLE job_artifacts ADD CONSTRAINT job_artifacts_kind_check
    CHECK (kind IN ('STDOUT', 'STDERR', 'JUNIT_XML', 'ENVIRONMENT', 'ANALYSIS_REPORT', 'FUZZ_REPORT', 'MUTATION_REPORT', 'OTHER'));

CREATE TABLE mutation_runs (
    id              uuid PRIMARY KEY,
    tenant_id       uuid          NOT NULL,
    project_id      uuid          NOT NULL,
    snapshot_id     uuid          NOT NULL,
    job_id          uuid          NOT NULL UNIQUE,
    engine_id       text          NOT NULL,
    engine_version  text          NOT NULL,
    seed            bigint        NOT NULL,
    max_mutants     integer       NOT NULL,
    test_patterns   jsonb         NOT NULL,
    -- Tests on the unmutated code; failing ones cannot kill a mutant.
    baseline        jsonb         NOT NULL,
    not_run_reason  text,
    mutants_found   integer       NOT NULL,
    total           integer       NOT NULL,
    killed          integer       NOT NULL,
    survived        integer       NOT NULL,
    timed_out       integer       NOT NULL,
    errors          integer       NOT NULL,
    -- (killed + timed out) / (total - errors), in percent; null when nothing was scored.
    score           numeric(5, 1),
    duration_ms     bigint        NOT NULL,
    operators       jsonb         NOT NULL,
    limitations     jsonb         NOT NULL,
    created_at      timestamptz   NOT NULL,
    FOREIGN KEY (tenant_id, project_id) REFERENCES projects (tenant_id, id),
    FOREIGN KEY (tenant_id, snapshot_id) REFERENCES source_snapshots (tenant_id, id),
    FOREIGN KEY (tenant_id, job_id) REFERENCES analysis_jobs (tenant_id, id),
    UNIQUE (tenant_id, id)
);
CREATE INDEX mutation_runs_project_idx ON mutation_runs (project_id, created_at DESC);

CREATE TABLE mutation_results (
    tenant_id    uuid    NOT NULL,
    run_id       uuid    NOT NULL,
    mutant_id    text    NOT NULL CHECK (mutant_id ~ '^[0-9a-f]{16}$'),
    path         text    NOT NULL,
    line         integer NOT NULL,
    column_no    integer NOT NULL,
    operator     text    NOT NULL CHECK (operator IN ('comparison', 'equality', 'arithmetic', 'logical', 'negation', 'condition', 'constant', 'call-removal')),
    original     text    NOT NULL,
    replacement  text    NOT NULL,
    symbol_key   text,
    status       text    NOT NULL CHECK (status IN ('KILLED', 'SURVIVED', 'TIMEOUT', 'ERROR')),
    killed_by    text,
    duration_ms  bigint  NOT NULL,
    FOREIGN KEY (tenant_id, run_id) REFERENCES mutation_runs (tenant_id, id),
    PRIMARY KEY (run_id, mutant_id)
);
CREATE INDEX mutation_results_status_idx ON mutation_results (run_id, status);
