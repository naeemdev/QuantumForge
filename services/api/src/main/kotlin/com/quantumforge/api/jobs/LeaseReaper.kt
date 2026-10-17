package com.quantumforge.api.jobs

import com.quantumforge.api.common.Json
import com.quantumforge.api.common.instantOrNull
import com.quantumforge.api.common.sql
import com.quantumforge.api.common.uuid
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Recovers jobs whose worker vanished. An expired lease marks the attempt
 * LEASE_EXPIRED; the job is re-queued while attempts remain, or finishes as
 * CANCELLED (cancel was requested), TIMED_OUT (it ran past its deadline) or
 * INFRA_ERROR (attempts exhausted). Safe to run on several API instances at
 * once thanks to SKIP LOCKED.
 */
@Component
class LeaseReaper(private val jdbc: JdbcClient, private val events: JobEventWriter, private val clock: Clock) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${quantumforge.execution.reaper-interval:15s}")
    fun scheduled() {
        try {
            val n = reapExpired()
            if (n > 0) log.info("Recovered {} expired job leases", n)
        } catch (e: Exception) {
            log.error("Lease reaper failed", e)
        }
    }

    @Transactional
    fun reapExpired(limit: Int = 100): Int {
        data class Expired(
            val attemptId: UUID, val tenantId: UUID, val jobId: UUID, val attemptNumber: Int,
            val attemptCount: Int, val maxAttempts: Int, val cancelRequested: Boolean, val startedAt: Instant?, val timeoutSeconds: Int,
        )
        val now = Instant.now(clock)
        val expired = jdbc.sql(
            """
            SELECT a.id, a.tenant_id, a.job_id, a.attempt_number, j.attempt_count, j.max_attempts, j.cancel_requested, j.started_at, j.timeout_seconds
            FROM job_attempts a JOIN analysis_jobs j ON j.id = a.job_id AND j.tenant_id = a.tenant_id
            WHERE a.outcome IS NULL AND a.lease_expires_at < :now AND j.state IN ('LEASED', 'RUNNING')
            ORDER BY a.lease_expires_at
            LIMIT :limit
            FOR UPDATE OF j, a SKIP LOCKED
            """,
        ).param("now", now.sql()).param("limit", limit).query { rs, _ ->
            Expired(rs.uuid("id"), rs.uuid("tenant_id"), rs.uuid("job_id"), rs.getInt("attempt_number"), rs.getInt("attempt_count"),
                rs.getInt("max_attempts"), rs.getBoolean("cancel_requested"), rs.instantOrNull("started_at"), rs.getInt("timeout_seconds"))
        }.list()

        for (e in expired) {
            jdbc.sql("UPDATE job_attempts SET outcome = 'LEASE_EXPIRED', finished_at = :now, error_message = 'Worker stopped heartbeating' WHERE id = :id")
                .param("now", now.sql()).param("id", e.attemptId).update()
            val pastDeadline = e.startedAt != null && now.isAfter(e.startedAt.plusSeconds(e.timeoutSeconds.toLong()))
            val next = when {
                e.cancelRequested -> JobState.CANCELLED
                pastDeadline -> JobState.TIMED_OUT
                e.attemptCount < e.maxAttempts -> JobState.QUEUED
                else -> JobState.INFRA_ERROR
            }
            if (next == JobState.QUEUED) {
                jdbc.sql("UPDATE analysis_jobs SET state = 'QUEUED', queued_at = :now, updated_at = :now, version = version + 1 WHERE id = :id")
                    .param("now", now.sql()).param("id", e.jobId).update()
                events.append(e.tenantId, e.jobId, e.attemptId, "job.lease_expired", "Worker lease expired on attempt ${e.attemptNumber}; job re-queued")
            } else {
                val reason = when (next) {
                    JobState.CANCELLED -> "Cancelled; the worker stopped responding before confirming"
                    JobState.TIMED_OUT -> "Execution exceeded its time limit and the worker stopped responding"
                    else -> "Worker lease expired on the final attempt (${e.attemptCount}/${e.maxAttempts})"
                }
                jdbc.sql(
                    """
                    UPDATE analysis_jobs SET state = :state, finished_at = :now, failure_reason = :reason,
                           result_summary = CAST(:summary AS jsonb), updated_at = :now, version = version + 1
                    WHERE id = :id
                    """,
                ).param("state", next.name).param("now", now.sql()).param("reason", reason)
                    .param("summary", Json.write(mapOf("attempt" to e.attemptNumber, "outcome" to "LEASE_EXPIRED")))
                    .param("id", e.jobId).update()
                events.append(e.tenantId, e.jobId, e.attemptId, "job.lease_expired", reason)
            }
        }
        return expired.size
    }
}
