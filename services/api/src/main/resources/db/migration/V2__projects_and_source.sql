-- Projects, repositories and immutable source snapshots.

CREATE TABLE projects (
    id              uuid PRIMARY KEY,
    tenant_id       uuid        NOT NULL,
    organization_id uuid        NOT NULL,
    workspace_id    uuid        NOT NULL,
    name            text        NOT NULL CHECK (length(name) BETWEEN 1 AND 200),
    slug            text        NOT NULL CHECK (slug ~ '^[a-z0-9][a-z0-9-]{1,62}$'),
    description     text        CHECK (length(description) <= 2000),
    status          text        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    created_at      timestamptz NOT NULL,
    created_by      uuid        NOT NULL REFERENCES users (id),
    updated_at      timestamptz NOT NULL,
    updated_by      uuid        NOT NULL REFERENCES users (id),
    version         bigint      NOT NULL DEFAULT 0,
    FOREIGN KEY (tenant_id, organization_id) REFERENCES organizations (tenant_id, id),
    FOREIGN KEY (tenant_id, workspace_id) REFERENCES workspaces (tenant_id, id),
    UNIQUE (workspace_id, slug),
    UNIQUE (tenant_id, id)
);
CREATE INDEX projects_org_idx ON projects (organization_id, created_at DESC);

CREATE TABLE repositories (
    id             uuid PRIMARY KEY,
    tenant_id      uuid        NOT NULL,
    project_id     uuid        NOT NULL,
    name           text        NOT NULL CHECK (length(name) BETWEEN 1 AND 200),
    source_type    text        NOT NULL CHECK (source_type IN ('UPLOAD', 'GIT')),
    remote_url     text,
    default_branch text,
    created_at     timestamptz NOT NULL,
    created_by     uuid        NOT NULL REFERENCES users (id),
    updated_at     timestamptz NOT NULL,
    updated_by     uuid        NOT NULL REFERENCES users (id),
    version        bigint      NOT NULL DEFAULT 0,
    FOREIGN KEY (tenant_id, project_id) REFERENCES projects (tenant_id, id),
    UNIQUE (project_id, name),
    UNIQUE (tenant_id, id),
    CHECK ((source_type = 'GIT') = (remote_url IS NOT NULL))
);

-- A snapshot is immutable once written: no updated_* columns on purpose.
-- archive_digest is the SHA-256 of the canonical tar.gz the platform produced,
-- so uploading the same tree twice resolves to the same snapshot.
CREATE TABLE source_snapshots (
    id                uuid PRIMARY KEY,
    tenant_id         uuid        NOT NULL,
    repository_id     uuid        NOT NULL,
    origin            text        NOT NULL CHECK (origin IN ('UPLOAD', 'GIT')),
    git_ref           text,
    git_commit        text CHECK (git_commit IS NULL OR git_commit ~ '^[0-9a-f]{40}([0-9a-f]{24})?$'),
    original_filename text,
    content_digest    text        NOT NULL CHECK (content_digest ~ '^[0-9a-f]{64}$'),
    archive_blob_key  text        NOT NULL,
    archive_size      bigint      NOT NULL CHECK (archive_size >= 0),
    file_count        integer     NOT NULL CHECK (file_count >= 0),
    total_bytes       bigint      NOT NULL CHECK (total_bytes >= 0),
    created_at        timestamptz NOT NULL,
    created_by        uuid        NOT NULL REFERENCES users (id),
    FOREIGN KEY (tenant_id, repository_id) REFERENCES repositories (tenant_id, id),
    UNIQUE (repository_id, content_digest),
    UNIQUE (tenant_id, id)
);
CREATE INDEX source_snapshots_repo_idx ON source_snapshots (repository_id, created_at DESC);

CREATE TABLE source_files (
    id          uuid PRIMARY KEY,
    tenant_id   uuid    NOT NULL,
    snapshot_id uuid    NOT NULL,
    path        text    NOT NULL CHECK (length(path) BETWEEN 1 AND 1024),
    size_bytes  bigint  NOT NULL CHECK (size_bytes >= 0),
    sha256      text    NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    language    text,
    is_binary   boolean NOT NULL,
    line_count  integer,
    FOREIGN KEY (tenant_id, snapshot_id) REFERENCES source_snapshots (tenant_id, id),
    UNIQUE (snapshot_id, path)
);

CREATE TABLE language_detections (
    tenant_id   uuid    NOT NULL,
    snapshot_id uuid    NOT NULL,
    language    text    NOT NULL,
    file_count  integer NOT NULL,
    line_count  bigint  NOT NULL,
    byte_count  bigint  NOT NULL,
    PRIMARY KEY (snapshot_id, language),
    FOREIGN KEY (tenant_id, snapshot_id) REFERENCES source_snapshots (tenant_id, id)
);
