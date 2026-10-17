package com.quantumforge.api.jobs

import com.quantumforge.api.analysis.IngestionSummary
import com.quantumforge.api.analysis.IngestionTarget
import com.quantumforge.api.analysis.InvalidReportException
import com.quantumforge.api.analysis.ReportIngestion
import com.quantumforge.api.audit.AuditActor
import com.quantumforge.api.audit.AuditService
import com.quantumforge.api.common.BadRequestException
import com.quantumforge.api.common.ConflictException
import com.quantumforge.api.common.Hashing
import com.quantumforge.api.common.Ids
import com.quantumforge.api.common.Json
import com.quantumforge.api.common.NotFoundException
import com.quantumforge.api.common.PayloadTooLargeException
import com.quantumforge.api.common.instant
import com.quantumforge.api.common.instantOrNull
import com.quantumforge.api.common.sql
import com.quantumforge.api.common.uuid
import com.quantumforge.api.config.QuantumForgeProperties
import com.quantumforge.api.fuzzing.FuzzIngestion
import com.quantumforge.api.fuzzing.FuzzSummary
import com.quantumforge.api.security.WorkerIdentity
import com.quantumforge.api.storage.BlobKeys
import com.quantumforge.api.storage.BlobStore
import com.quantumforge.api.testing.TestResultStore
import com.quantumforge.api.testing.TestRunReport
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.InputStream
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class LeasedJob(
    val attemptId: UUID,
    val attemptNumber: Int,
    val leaseToken: String,
    val leaseExpiresAt: Instant,
    val heartbeatIntervalSeconds: Long,
    val jobId: UUID,
    val jobType: JobType,
    val config: ExecutionConfig,
    val testPatterns: List<String>,
    val timeoutSeconds: Int,
    val snapshotDigest: String,
    val snapshotSizeBytes: Long,
)

data class HeartbeatResult(val cancelRequested: Boolean, val leaseExpiresAt: Instant)

data class CompletionReport(
    val outcome: AttemptOutcome,
    val exitCode: Int?,
    val durationMs: Long?,
    val errorMessage: String?,
    val testRun: TestRunReport?,
)

data class CompletionResult(val jobState: JobState, val retried: Boolean)

/**
 * The worker-facing side of the job engine. Every call after leasing proves
 * ownership of the attempt with the lease token; only the job's current,
 * unfinished attempt is accepted, which makes stale or duplicate reports from a
 * worker that lost its lease harmless.
 */
@Service
class DispatchService(
    private val jdbc: JdbcClient,
    private val workers: WorkerRegistry,
    private val events: JobEventWriter,
    private val tests: TestResultStore,
    private val ingestion: ReportIngestion,
    private val fuzzIngestion: FuzzIngestion,
    private val blobs: BlobStore,
    private val audit: AuditService,
    private val properties: QuantumForgeProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private data class AttemptContext(
        val attemptId: UUID,
        val tenantId: UUID,
        val organizationId: UUID,
        val jobId: UUID,
        val attemptNumber: Int,
        val outcome: String?,
        val jobState: JobState,
        val attemptCount: Int,
        val maxAttempts: Int,
        val cancelRequested: Boolean,
        val startedAt: Instant?,
        val timeoutSeconds: Int,
        val jobType: JobType,
        val projectId: UUID,
        val snapshotId: UUID,
    ) {
        val isCurrent get() = attemptNumber == attemptCount
    }

    @Transactional
    fun lease(worker: WorkerIdentity): LeasedJob? {
        if (!workers.isolationAllowed(worker.isolationMode)) {
            throw ConflictException(
                "isolation_not_permitted",
                "This installation does not dispatch jobs to '${worker.isolationMode}' workers. " +
                    "Set quantumforge.execution.allow-dev-isolation=true only on development machines.",
            )
        }
        data class Candidate(val id: UUID, val tenantId: UUID, val attemptCount: Int, val config: String, val timeout: Int, val snapshotId: UUID, val type: String)
        val candidate = jdbc.sql(
            """
            SELECT j.id, j.tenant_id, j.attempt_count, j.config, j.timeout_seconds, j.snapshot_id, j.job_type
            FROM analysis_jobs j
            WHERE j.state = 'QUEUED'
            ORDER BY j.priority DESC, j.queued_at
            FOR UPDATE SKIP LOCKED
            LIMIT 1
            """,
        ).query { rs, _ ->
            Candidate(rs.uuid("id"), rs.uuid("tenant_id"), rs.getInt("attempt_count"), rs.getString("config"), rs.getInt("timeout_seconds"), rs.uuid("snapshot_id"), rs.getString("job_type"))
        }.optional().orElse(null) ?: return null

        val token = "qf_lease_" + Hashing.randomSecret()
        val attemptId = Ids.newId(clock)
        val attemptNumber = candidate.attemptCount + 1
        val now = Instant.now(clock)
        val expires = now.plus(properties.execution.leaseDuration)
        jdbc.sql(
            """
            INSERT INTO job_attempts (id, tenant_id, job_id, attempt_number, worker_id, lease_token_hash, leased_at, lease_expires_at)
            VALUES (:id, :tenant, :job, :n, :worker, :hash, :now, :expires)
            """,
        ).param("id", attemptId).param("tenant", candidate.tenantId).param("job", candidate.id).param("n", attemptNumber)
            .param("worker", worker.workerId).param("hash", Hashing.sha256(token)).param("now", now.sql()).param("expires", expires.sql()).update()
        jdbc.sql("UPDATE analysis_jobs SET state = 'LEASED', attempt_count = :n, updated_at = :now, version = version + 1 WHERE id = :id")
            .param("n", attemptNumber).param("now", now.sql()).param("id", candidate.id).update()
        events.append(candidate.tenantId, candidate.id, attemptId, "job.leased", "Leased by worker ${worker.name} (attempt $attemptNumber)",
            mapOf("worker" to worker.name, "isolationMode" to worker.isolationMode, "attempt" to attemptNumber))

        data class Snap(val digest: String, val size: Long)
        val snap = jdbc.sql("SELECT content_digest, archive_size FROM source_snapshots WHERE id = :id AND tenant_id = :tenant")
            .param("id", candidate.snapshotId).param("tenant", candidate.tenantId)
            .query { rs, _ -> Snap(rs.getString("content_digest"), rs.getLong("archive_size")) }.single()
        val config = Json.read<ExecutionConfig>(candidate.config)
        log.info("Job {} leased to {} as attempt {}", candidate.id, worker.name, attemptNumber)
        return LeasedJob(
            attemptId = attemptId,
            attemptNumber = attemptNumber,
            leaseToken = token,
            leaseExpiresAt = expires,
            heartbeatIntervalSeconds = (properties.execution.leaseDuration.seconds / 3).coerceAtLeast(1),
            jobId = candidate.id,
            jobType = JobType.valueOf(candidate.type),
            config = config,
            testPatterns = config.effectivePatterns(),
            timeoutSeconds = candidate.timeout,
            snapshotDigest = snap.digest,
            snapshotSizeBytes = snap.size,
        )
    }

    @Transactional
    fun heartbeat(worker: WorkerIdentity, attemptId: UUID, token: String, message: String?): HeartbeatResult {
        val ctx = requireLease(worker, attemptId, token)
        val now = Instant.now(clock)
        val pastDeadline = ctx.startedAt != null && now.isAfter(ctx.startedAt.plusSeconds(ctx.timeoutSeconds.toLong() + DEADLINE_GRACE_SECONDS))
        val expires = now.plus(properties.execution.leaseDuration)
        jdbc.sql("UPDATE job_attempts SET heartbeat_at = :now, lease_expires_at = :expires WHERE id = :id")
            .param("now", now.sql()).param("expires", expires.sql()).param("id", attemptId).update()
        if (!message.isNullOrBlank()) {
            val progressEvents = jdbc.sql("SELECT count(*) FROM job_events WHERE attempt_id = :a AND event_type = 'job.progress'").param("a", attemptId).query(Int::class.java).single()
            if (progressEvents < MAX_PROGRESS_EVENTS) events.append(ctx.tenantId, ctx.jobId, attemptId, "job.progress", message.take(500))
        }
        // A worker that keeps heartbeating past the deadline is told to stop.
        return HeartbeatResult(ctx.cancelRequested || pastDeadline, expires)
    }

    @Transactional
    fun start(worker: WorkerIdentity, attemptId: UUID, token: String, environment: Map<String, Any?>) {
        val ctx = requireLease(worker, attemptId, token)
        val envJson = Json.write(environment)
        if (envJson.length > 64 * 1024) throw PayloadTooLargeException("Environment report is too large")
        if (ctx.jobState == JobState.RUNNING) return // duplicate start after a retried request
        if (ctx.jobState != JobState.LEASED) throw ConflictException("invalid_state", "Job is ${ctx.jobState}, cannot start")
        val now = Instant.now(clock).sql()
        jdbc.sql("UPDATE job_attempts SET started_at = :now, environment = CAST(:env AS jsonb) WHERE id = :id")
            .param("now", now).param("env", envJson).param("id", attemptId).update()
        jdbc.sql("UPDATE analysis_jobs SET state = 'RUNNING', started_at = COALESCE(started_at, :now), updated_at = :now, version = version + 1 WHERE id = :id")
            .param("now", now).param("id", ctx.jobId).update()
        events.append(ctx.tenantId, ctx.jobId, attemptId, "job.started", "Execution started", mapOf("environment" to environment))
    }

    fun snapshotStream(worker: WorkerIdentity, attemptId: UUID, token: String): InputStream {
        val ctx = requireLease(worker, attemptId, token, lock = false)
        val key = jdbc.sql("SELECT s.archive_blob_key FROM analysis_jobs j JOIN source_snapshots s ON s.id = j.snapshot_id AND s.tenant_id = j.tenant_id WHERE j.id = :job")
            .param("job", ctx.jobId).query(String::class.java).single()
        return blobs.open(key)
    }

    /** Uploads are idempotent per (attempt, name): a retried upload keeps the first copy. */
    fun uploadArtifact(
        worker: WorkerIdentity,
        attemptId: UUID,
        token: String,
        name: String,
        kind: String,
        mediaType: String,
        truncated: Boolean,
        content: InputStream,
    ): UUID {
        if (!Regex("^[A-Za-z0-9._-]{1,100}$").matches(name) || name.startsWith(".")) throw BadRequestException("invalid_name", "Invalid artifact name")
        if (kind !in setOf("STDOUT", "STDERR", "JUNIT_XML", "ENVIRONMENT", "ANALYSIS_REPORT", "FUZZ_REPORT", "OTHER")) throw BadRequestException("invalid_kind", "Invalid artifact kind")
        if (!Regex("^[a-z]+/[a-z0-9.+-]+$").matches(mediaType)) throw BadRequestException("invalid_media_type", "Invalid media type")
        val ctx = requireLease(worker, attemptId, token, lock = false)
        val existing = jdbc.sql("SELECT id FROM job_artifacts WHERE attempt_id = :a AND name = :n").param("a", attemptId).param("n", name)
            .query(UUID::class.java).optional().orElse(null)
        if (existing != null) return existing

        val tmp = Files.createTempFile("qf-artifact-", ".bin")
        try {
            Files.newOutputStream(tmp).use { out ->
                val limit = properties.execution.maxArtifactBytes
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = content.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > limit) throw PayloadTooLargeException("Artifact exceeds $limit bytes; workers must truncate before upload")
                    out.write(buffer, 0, n)
                }
            }
            val key = BlobKeys.artifact(ctx.tenantId, ctx.jobId, attemptId, name)
            val stored = blobs.put(key, tmp, mediaType)
            val id = Ids.newId(clock)
            val inserted = jdbc.sql(
                """
                INSERT INTO job_artifacts (id, tenant_id, job_id, attempt_id, kind, name, blob_key, media_type, size_bytes, sha256, truncated, created_at)
                VALUES (:id, :tenant, :job, :attempt, :kind, :name, :key, :media, :size, :sha, :truncated, :now)
                ON CONFLICT (attempt_id, name) DO NOTHING
                """,
            ).param("id", id).param("tenant", ctx.tenantId).param("job", ctx.jobId).param("attempt", attemptId)
                .param("kind", kind).param("name", name).param("key", key).param("media", mediaType)
                .param("size", stored.sizeBytes).param("sha", stored.sha256).param("truncated", truncated)
                .param("now", Instant.now(clock).sql()).update()
            if (inserted == 0) {
                return jdbc.sql("SELECT id FROM job_artifacts WHERE attempt_id = :a AND name = :n").param("a", attemptId).param("n", name).query(UUID::class.java).single()
            }
            jdbc.sql(
                """
                INSERT INTO job_events (tenant_id, job_id, attempt_id, event_type, message, data, occurred_at)
                VALUES (:tenant, :job, :attempt, 'job.artifact', :msg, CAST(:data AS jsonb), :now)
                """,
            ).param("tenant", ctx.tenantId).param("job", ctx.jobId).param("attempt", attemptId)
                .param("msg", "Stored artifact $name (${stored.sizeBytes} bytes)")
                .param("data", Json.write(mapOf("artifactId" to id, "kind" to kind, "sha256" to stored.sha256, "truncated" to truncated)))
                .param("now", Instant.now(clock).sql()).update()
            return id
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    @Transactional
    fun complete(worker: WorkerIdentity, attemptId: UUID, token: String, workerReport: CompletionReport): CompletionResult {
        var report = workerReport
        if (report.outcome == AttemptOutcome.LEASE_EXPIRED) throw BadRequestException("invalid_outcome", "Workers cannot report LEASE_EXPIRED")
        val ctx = requireLease(worker, attemptId, token, allowFinished = true)
        if (ctx.outcome != null) {
            // Retried completion: accept if it says the same thing, otherwise refuse.
            if (ctx.outcome == report.outcome.name) return CompletionResult(ctx.jobState, retried = false)
            throw ConflictException("attempt_finished", "Attempt already completed with ${ctx.outcome}")
        }
        if (!ctx.isCurrent) throw ConflictException("lease_lost", "This attempt is no longer current")

        val now = Instant.now(clock)
        val testCounts = report.testRun?.let { tests.save(ctx.tenantId, ctx.jobId, attemptId, it, properties.execution.maxTestResults) }

        // An analysis only succeeds if its report is valid and stored with the job.
        var analysisSummary: IngestionSummary? = null
        if (ctx.jobType == JobType.STATIC_ANALYSIS && report.outcome == AttemptOutcome.SUCCEEDED) {
            try {
                val analysisReport = ingestion.loadReport(attemptId, properties.execution.maxArtifactBytes)
                analysisSummary = ingestion.ingest(
                    IngestionTarget(ctx.tenantId, ctx.organizationId, ctx.projectId, ctx.snapshotId, ctx.jobId, attemptId),
                    analysisReport,
                )
            } catch (e: InvalidReportException) {
                log.warn("Rejected analysis report for job {}: {}", ctx.jobId, e.message)
                report = report.copy(outcome = AttemptOutcome.FAILED, errorMessage = "Invalid analysis report: ${e.message}")
            }
        }
        var fuzzSummary: FuzzSummary? = null
        if (ctx.jobType == JobType.FUZZ_CAMPAIGN && report.outcome == AttemptOutcome.SUCCEEDED) {
            try {
                val fuzzReport = fuzzIngestion.loadReport(attemptId, properties.execution.maxArtifactBytes)
                fuzzSummary = fuzzIngestion.ingest(
                    IngestionTarget(ctx.tenantId, ctx.organizationId, ctx.projectId, ctx.snapshotId, ctx.jobId, attemptId),
                    fuzzReport,
                )
            } catch (e: InvalidReportException) {
                log.warn("Rejected fuzz report for job {}: {}", ctx.jobId, e.message)
                report = report.copy(outcome = AttemptOutcome.FAILED, errorMessage = "Invalid fuzz report: ${e.message}")
            }
        }

        jdbc.sql(
            """
            UPDATE job_attempts SET outcome = :outcome, exit_code = :exit, duration_ms = :duration, error_message = :error, finished_at = :now
            WHERE id = :id
            """,
        ).param("outcome", report.outcome.name).param("exit", report.exitCode).param("duration", report.durationMs?.coerceAtLeast(0))
            .param("error", report.errorMessage?.take(4000)).param("now", now.sql()).param("id", attemptId).update()

        val retry = report.outcome == AttemptOutcome.INFRA_ERROR && ctx.attemptCount < ctx.maxAttempts && !ctx.cancelRequested
        val newState = when {
            retry -> JobState.QUEUED
            else -> JobState.valueOf(report.outcome.name)
        }
        val summary = mapOf(
            "attempt" to ctx.attemptNumber,
            "outcome" to report.outcome.name,
            "exitCode" to report.exitCode,
            "durationMs" to report.durationMs,
            "tests" to testCounts,
            "analysis" to analysisSummary,
            "fuzz" to fuzzSummary,
            "worker" to worker.name,
            "isolationMode" to worker.isolationMode,
        )
        if (retry) {
            jdbc.sql(
                "UPDATE analysis_jobs SET state = 'QUEUED', queued_at = :now, failure_reason = :reason, updated_at = :now, version = version + 1 WHERE id = :id",
            ).param("now", now.sql()).param("reason", report.errorMessage?.take(1000)).param("id", ctx.jobId).update()
            events.append(ctx.tenantId, ctx.jobId, attemptId, "job.retry_scheduled",
                "Attempt ${ctx.attemptNumber} hit an infrastructure error; re-queued (${ctx.attemptCount}/${ctx.maxAttempts})", summary)
        } else {
            jdbc.sql(
                """
                UPDATE analysis_jobs SET state = :state, finished_at = :now, result_summary = CAST(:summary AS jsonb),
                       failure_reason = :reason, updated_at = :now, version = version + 1
                WHERE id = :id
                """,
            ).param("state", newState.name).param("now", now.sql()).param("summary", Json.write(summary))
                .param("reason", if (newState == JobState.SUCCEEDED) null else (report.errorMessage ?: describe(newState, report)).take(1000))
                .param("id", ctx.jobId).update()
            events.append(ctx.tenantId, ctx.jobId, attemptId, "job.completed", "Finished with state $newState", summary)
        }
        audit.write(ctx.tenantId, ctx.organizationId, AuditActor.worker(worker.workerId), "job.attempt_completed", "job", ctx.jobId, "SUCCESS",
            mapOf("attempt" to ctx.attemptNumber, "outcome" to report.outcome.name, "jobState" to newState.name))
        log.info("Job {} attempt {} completed: outcome={} jobState={}", ctx.jobId, ctx.attemptNumber, report.outcome, newState)
        return CompletionResult(newState, retry)
    }

    private fun describe(state: JobState, report: CompletionReport) = when (state) {
        JobState.FAILED -> "Tests failed or the command exited with code ${report.exitCode}"
        JobState.TIMED_OUT -> "Execution exceeded its time limit"
        JobState.CANCELLED -> "Cancelled during execution"
        else -> "Infrastructure error"
    }

    private fun requireLease(worker: WorkerIdentity, attemptId: UUID, token: String, lock: Boolean = true, allowFinished: Boolean = false): AttemptContext {
        data class Row(val ctx: AttemptContext, val workerId: UUID, val hash: ByteArray)
        val lockClause = if (lock) "FOR UPDATE OF j" else ""
        val row = jdbc.sql(
            """
            SELECT a.id, a.tenant_id, a.job_id, a.attempt_number, a.outcome, a.worker_id, a.lease_token_hash,
                   j.organization_id, j.state, j.attempt_count, j.max_attempts, j.cancel_requested, j.started_at, j.timeout_seconds,
                   j.job_type, j.project_id, j.snapshot_id
            FROM job_attempts a JOIN analysis_jobs j ON j.id = a.job_id AND j.tenant_id = a.tenant_id
            WHERE a.id = :id $lockClause
            """,
        ).param("id", attemptId).query { rs, _ ->
            Row(
                AttemptContext(
                    attemptId = rs.uuid("id"),
                    tenantId = rs.uuid("tenant_id"),
                    organizationId = rs.uuid("organization_id"),
                    jobId = rs.uuid("job_id"),
                    attemptNumber = rs.getInt("attempt_number"),
                    outcome = rs.getString("outcome"),
                    jobState = JobState.valueOf(rs.getString("state")),
                    attemptCount = rs.getInt("attempt_count"),
                    maxAttempts = rs.getInt("max_attempts"),
                    cancelRequested = rs.getBoolean("cancel_requested"),
                    startedAt = rs.instantOrNull("started_at"),
                    timeoutSeconds = rs.getInt("timeout_seconds"),
                    jobType = JobType.valueOf(rs.getString("job_type")),
                    projectId = rs.uuid("project_id"),
                    snapshotId = rs.uuid("snapshot_id"),
                ),
                rs.uuid("worker_id"),
                rs.getBytes("lease_token_hash"),
            )
        }.optional().orElseThrow { NotFoundException("attempt", attemptId) }
        if (row.workerId != worker.workerId || !Hashing.constantTimeEquals(row.hash, Hashing.sha256(token))) {
            throw NotFoundException("attempt", attemptId)
        }
        if (!allowFinished && (row.ctx.outcome != null || !row.ctx.isCurrent || row.ctx.jobState.terminal)) {
            throw ConflictException("lease_lost", "This attempt is no longer current; stop work and discard results")
        }
        return row.ctx
    }

    companion object {
        const val MAX_PROGRESS_EVENTS = 200
        const val DEADLINE_GRACE_SECONDS = 30L
    }
}
