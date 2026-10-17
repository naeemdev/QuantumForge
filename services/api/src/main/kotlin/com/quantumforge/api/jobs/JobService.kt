package com.quantumforge.api.jobs

import com.quantumforge.api.access.OrgAccess
import com.quantumforge.api.audit.AuditService
import com.quantumforge.api.common.BadRequestException
import com.quantumforge.api.common.ConflictException
import com.quantumforge.api.common.Hashing
import com.quantumforge.api.common.Ids
import com.quantumforge.api.common.Json
import com.quantumforge.api.common.NotFoundException
import com.quantumforge.api.common.Page
import com.quantumforge.api.common.PageRequest
import com.quantumforge.api.common.QuotaExceededException
import com.quantumforge.api.common.instant
import com.quantumforge.api.common.instantOrNull
import com.quantumforge.api.common.intOrNull
import com.quantumforge.api.common.longOrNull
import com.quantumforge.api.common.sql
import com.quantumforge.api.common.uuid
import com.quantumforge.api.common.uuidOrNull
import com.quantumforge.api.config.QuantumForgeProperties
import com.quantumforge.api.projects.ProjectService
import com.quantumforge.api.source.SnapshotService
import com.quantumforge.api.storage.BlobStore
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.InputStream
import java.sql.ResultSet
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class SubmitJobCommand(
    val jobType: JobType,
    val projectId: UUID,
    val snapshotId: UUID?,
    val config: ExecutionConfig,
    val priority: Int,
    val idempotencyKey: String?,
)

data class SubmitResult(val job: Job, val created: Boolean)

data class JobFilter(val projectId: UUID?, val states: List<JobState>, val jobType: JobType? = null)

@Service
class JobService(
    private val jdbc: JdbcClient,
    private val projects: ProjectService,
    private val snapshots: SnapshotService,
    private val events: JobEventWriter,
    private val audit: AuditService,
    private val blobs: BlobStore,
    private val properties: QuantumForgeProperties,
    private val clock: Clock,
) {
    /**
     * Queues a job. With an Idempotency-Key, retrying the same request returns the
     * job created the first time; reusing the key for a different request is a 409.
     * Admission takes a row lock on the tenant so concurrent submissions cannot
     * overshoot the active-job quota.
     */
    @Transactional
    fun submit(ctx: OrgAccess, command: SubmitJobCommand): SubmitResult {
        val project = projects.get(ctx, command.projectId)
        if (project.status != "ACTIVE") throw ConflictException("project_archived", "Archived projects cannot run jobs")
        val snapshot = command.snapshotId?.let { snapshots.get(ctx, it) } ?: snapshots.latestForProject(ctx, project.id)
            ?: throw BadRequestException("no_snapshot", "Import a source snapshot before running jobs")
        val belongs = jdbc.sql(
            "SELECT count(*) FROM source_snapshots s JOIN repositories r ON r.id = s.repository_id AND r.tenant_id = s.tenant_id WHERE s.id = :snap AND r.project_id = :project",
        ).param("snap", snapshot.id).param("project", project.id).query(Int::class.java).single()
        if (belongs == 0) throw BadRequestException("snapshot_not_in_project", "The snapshot does not belong to this project")
        if (command.priority !in -100..100) throw BadRequestException("invalid_priority", "priority must be between -100 and 100")

        val config = command.config.validated(properties.execution.maxTimeoutSeconds)
        if (config.preset.name !in command.jobType.presets) {
            throw BadRequestException("invalid_preset", "Preset ${config.preset} cannot run a ${command.jobType} job", mapOf("allowed" to command.jobType.presets))
        }
        val timeout = config.timeoutSeconds ?: properties.execution.defaultTimeoutSeconds
        val requestHash = Hashing.sha256Hex(Json.write(mapOf("type" to command.jobType, "project" to project.id, "snapshot" to snapshot.id, "config" to config, "priority" to command.priority)).toByteArray())

        val maxActive = jdbc.sql("SELECT max_active_jobs FROM tenants WHERE id = :id FOR UPDATE").param("id", ctx.tenantId).query(Int::class.java).single()

        command.idempotencyKey?.let { key ->
            val existing = jdbc.sql("SELECT * FROM analysis_jobs WHERE tenant_id = :tenant AND idempotency_key = :key")
                .param("tenant", ctx.tenantId).param("key", key).query(::mapJob).optional().orElse(null)
            if (existing != null) {
                val existingHash = jdbc.sql("SELECT request_hash FROM analysis_jobs WHERE id = :id").param("id", existing.id).query(String::class.java).single()
                if (existingHash != requestHash) {
                    throw ConflictException("idempotency_key_reused", "This Idempotency-Key was already used for a different request")
                }
                return SubmitResult(existing, false)
            }
        }

        val active = jdbc.sql("SELECT count(*) FROM analysis_jobs WHERE tenant_id = :tenant AND state IN ('QUEUED','LEASED','RUNNING')")
            .param("tenant", ctx.tenantId).query(Int::class.java).single()
        if (active >= maxActive) {
            throw QuotaExceededException("Your organization already has $active active jobs (limit $maxActive)", mapOf("limit" to maxActive, "active" to active))
        }

        val id = Ids.newId(clock)
        val now = Instant.now(clock)
        jdbc.sql(
            """
            INSERT INTO analysis_jobs (id, tenant_id, organization_id, project_id, snapshot_id, job_type, state, priority, config,
                                       idempotency_key, request_hash, max_attempts, timeout_seconds, queued_at,
                                       created_at, created_by, updated_at, updated_by)
            VALUES (:id, :tenant, :org, :project, :snap, :type, 'QUEUED', :priority, CAST(:config AS jsonb),
                    :key, :hash, 3, :timeout, :now, :now, :user, :now, :user)
            """,
        ).param("id", id).param("tenant", ctx.tenantId).param("org", ctx.organizationId).param("project", project.id)
            .param("snap", snapshot.id).param("type", command.jobType.name).param("priority", command.priority).param("config", Json.write(config))
            .param("key", command.idempotencyKey).param("hash", requestHash).param("timeout", timeout)
            .param("now", now.sql()).param("user", ctx.userId).update()
        events.append(ctx.tenantId, id, null, "job.queued", "Job queued", mapOf("snapshotId" to snapshot.id, "preset" to config.preset.name))
        audit.record(ctx, "job.submitted", "job", id, mapOf("projectId" to project.id, "snapshotId" to snapshot.id, "type" to command.jobType.name))
        return SubmitResult(get(ctx, id), true)
    }

    fun get(ctx: OrgAccess, id: UUID): Job =
        jdbc.sql("SELECT * FROM analysis_jobs WHERE id = :id AND tenant_id = :tenant AND organization_id = :org")
            .param("id", id).param("tenant", ctx.tenantId).param("org", ctx.organizationId)
            .query(::mapJob).optional().orElseThrow { NotFoundException("job", id) }

    fun list(ctx: OrgAccess, filter: JobFilter, page: PageRequest): Page<Job> {
        val where = buildString {
            append("tenant_id = :tenant AND organization_id = :org")
            if (filter.projectId != null) append(" AND project_id = :project")
            if (filter.states.isNotEmpty()) append(" AND state IN (:states)")
            if (filter.jobType != null) append(" AND job_type = :jobType")
        }
        fun bind(stmt: JdbcClient.StatementSpec) = stmt.param("tenant", ctx.tenantId).param("org", ctx.organizationId).apply {
            if (filter.projectId != null) param("project", filter.projectId)
            if (filter.states.isNotEmpty()) param("states", filter.states.map { it.name })
            if (filter.jobType != null) param("jobType", filter.jobType.name)
        }
        val items = bind(jdbc.sql("SELECT * FROM analysis_jobs WHERE $where ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset"))
            .param("limit", page.size).param("offset", page.offset).query(::mapJob).list()
        val total = bind(jdbc.sql("SELECT count(*) FROM analysis_jobs WHERE $where")).query(Long::class.java).single()
        return Page.of(items, page, total)
    }

    /**
     * Queued jobs are cancelled immediately. Leased or running jobs get a cancel
     * request that the worker picks up on its next heartbeat; the job becomes
     * CANCELLED when the worker confirms or its lease runs out.
     */
    @Transactional
    fun cancel(ctx: OrgAccess, id: UUID): Job {
        val job = jdbc.sql("SELECT * FROM analysis_jobs WHERE id = :id AND tenant_id = :tenant AND organization_id = :org FOR UPDATE")
            .param("id", id).param("tenant", ctx.tenantId).param("org", ctx.organizationId)
            .query(::mapJob).optional().orElseThrow { NotFoundException("job", id) }
        val now = Instant.now(clock).sql()
        when {
            job.state.terminal -> throw ConflictException("job_finished", "Job already finished with state ${job.state}")
            job.state == JobState.QUEUED -> {
                jdbc.sql(
                    """
                    UPDATE analysis_jobs SET state = 'CANCELLED', cancel_requested = true, finished_at = :now,
                           failure_reason = 'Cancelled before execution', updated_at = :now, updated_by = :user, version = version + 1
                    WHERE id = :id
                    """,
                ).param("now", now).param("user", ctx.userId).param("id", id).update()
                events.append(ctx.tenantId, id, null, "job.cancelled", "Cancelled before a worker picked it up")
            }
            job.cancelRequested -> return job
            else -> {
                jdbc.sql("UPDATE analysis_jobs SET cancel_requested = true, updated_at = :now, updated_by = :user, version = version + 1 WHERE id = :id")
                    .param("now", now).param("user", ctx.userId).param("id", id).update()
                events.append(ctx.tenantId, id, null, "job.cancel_requested", "Cancellation requested; waiting for the worker to stop")
            }
        }
        audit.record(ctx, "job.cancel_requested", "job", id, mapOf("previousState" to job.state.name))
        return get(ctx, id)
    }

    fun events(ctx: OrgAccess, jobId: UUID, afterId: Long?, limit: Int): List<JobEvent> {
        get(ctx, jobId)
        return eventsUnchecked(ctx.tenantId, jobId, afterId, limit)
    }

    /** Caller must have authorised access to the job already. */
    fun eventsUnchecked(tenantId: UUID, jobId: UUID, afterId: Long?, limit: Int): List<JobEvent> =
        jdbc.sql("SELECT * FROM job_events WHERE job_id = :job AND tenant_id = :tenant AND id > :after ORDER BY id LIMIT :limit")
            .param("job", jobId).param("tenant", tenantId).param("after", afterId ?: 0L).param("limit", limit.coerceIn(1, 1000))
            .query { rs, _ ->
                JobEvent(rs.getLong("id"), rs.uuidOrNull("attempt_id"), rs.getString("event_type"), rs.getString("message"), Json.readMap(rs.getString("data")), rs.instant("occurred_at"))
            }.list()

    fun stateUnchecked(tenantId: UUID, jobId: UUID): JobState =
        JobState.valueOf(jdbc.sql("SELECT state FROM analysis_jobs WHERE id = :id AND tenant_id = :tenant").param("id", jobId).param("tenant", tenantId).query(String::class.java).single())

    fun attempts(ctx: OrgAccess, jobId: UUID): List<JobAttempt> {
        get(ctx, jobId)
        return jdbc.sql(
            """
            SELECT a.*, w.name AS worker_name, w.isolation_mode FROM job_attempts a JOIN workers w ON w.id = a.worker_id
            WHERE a.job_id = :job AND a.tenant_id = :tenant ORDER BY a.attempt_number
            """,
        ).param("job", jobId).param("tenant", ctx.tenantId).query { rs, _ ->
            JobAttempt(
                id = rs.uuid("id"),
                attemptNumber = rs.getInt("attempt_number"),
                workerName = rs.getString("worker_name"),
                isolationMode = rs.getString("isolation_mode"),
                leasedAt = rs.instant("leased_at"),
                leaseExpiresAt = rs.instant("lease_expires_at"),
                heartbeatAt = rs.instantOrNull("heartbeat_at"),
                startedAt = rs.instantOrNull("started_at"),
                finishedAt = rs.instantOrNull("finished_at"),
                outcome = rs.getString("outcome"),
                exitCode = rs.intOrNull("exit_code"),
                durationMs = rs.longOrNull("duration_ms"),
                environment = rs.getString("environment")?.let { Json.readMap(it) },
                errorMessage = rs.getString("error_message"),
            )
        }.list()
    }

    fun artifacts(ctx: OrgAccess, jobId: UUID): List<JobArtifact> {
        get(ctx, jobId)
        return jdbc.sql("SELECT * FROM job_artifacts WHERE job_id = :job AND tenant_id = :tenant ORDER BY created_at, name")
            .param("job", jobId).param("tenant", ctx.tenantId).query(::mapArtifact).list()
    }

    fun openArtifact(ctx: OrgAccess, jobId: UUID, artifactId: UUID): Pair<JobArtifact, InputStream> {
        get(ctx, jobId)
        data class Row(val artifact: JobArtifact, val key: String)
        val row = jdbc.sql("SELECT * FROM job_artifacts WHERE id = :id AND job_id = :job AND tenant_id = :tenant")
            .param("id", artifactId).param("job", jobId).param("tenant", ctx.tenantId)
            .query { rs, i -> Row(mapArtifact(rs, i), rs.getString("blob_key")) }
            .optional().orElseThrow { NotFoundException("artifact", artifactId) }
        return row.artifact to blobs.open(row.key)
    }

    private fun mapArtifact(rs: ResultSet, @Suppress("UNUSED_PARAMETER") i: Int) = JobArtifact(
        id = rs.uuid("id"),
        attemptId = rs.uuid("attempt_id"),
        kind = rs.getString("kind"),
        name = rs.getString("name"),
        mediaType = rs.getString("media_type"),
        sizeBytes = rs.getLong("size_bytes"),
        sha256 = rs.getString("sha256"),
        truncated = rs.getBoolean("truncated"),
        createdAt = rs.instant("created_at"),
    )

    companion object {
        fun mapJob(rs: ResultSet, @Suppress("UNUSED_PARAMETER") i: Int) = Job(
            id = rs.uuid("id"),
            organizationId = rs.uuid("organization_id"),
            projectId = rs.uuid("project_id"),
            snapshotId = rs.uuid("snapshot_id"),
            jobType = JobType.valueOf(rs.getString("job_type")),
            state = JobState.valueOf(rs.getString("state")),
            priority = rs.getInt("priority"),
            config = Json.read<ExecutionConfig>(rs.getString("config")),
            idempotencyKey = rs.getString("idempotency_key"),
            maxAttempts = rs.getInt("max_attempts"),
            attemptCount = rs.getInt("attempt_count"),
            timeoutSeconds = rs.getInt("timeout_seconds"),
            cancelRequested = rs.getBoolean("cancel_requested"),
            queuedAt = rs.instant("queued_at"),
            startedAt = rs.instantOrNull("started_at"),
            finishedAt = rs.instantOrNull("finished_at"),
            resultSummary = rs.getString("result_summary")?.let { Json.readMap(it) },
            failureReason = rs.getString("failure_reason"),
            createdAt = rs.instant("created_at"),
            createdBy = rs.uuid("created_by"),
            updatedAt = rs.instant("updated_at"),
            version = rs.getLong("version"),
        )
    }
}
