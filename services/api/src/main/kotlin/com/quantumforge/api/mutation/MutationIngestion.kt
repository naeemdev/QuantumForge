package com.quantumforge.api.mutation

import com.quantumforge.api.analysis.IngestionTarget
import com.quantumforge.api.analysis.InvalidReportException
import com.quantumforge.api.common.Ids
import com.quantumforge.api.common.Json
import com.quantumforge.api.common.sql
import com.quantumforge.api.storage.BlobStore
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class MutationSummary(val total: Int, val killed: Int, val survived: Int, val timedOut: Int, val errors: Int, val score: Double?, val notRunReason: String?)

/**
 * Stores a mutation run and every mutant's outcome. Surviving mutants describe
 * the tests, not defects in the code, so they are not turned into findings.
 * Runs inside the transaction that completes the job.
 */
@Service
class MutationIngestion(
    private val jdbc: JdbcClient,
    private val template: JdbcTemplate,
    private val blobs: BlobStore,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun loadReport(attemptId: UUID, maxBytes: Long): MutationReport {
        val key = jdbc.sql("SELECT blob_key FROM job_artifacts WHERE attempt_id = :a AND kind = 'MUTATION_REPORT'")
            .param("a", attemptId).query(String::class.java).optional()
            .orElseThrow { InvalidReportException("The worker did not upload a mutation report") }
        val bytes = blobs.open(key).use { it.readNBytes((maxBytes + 1).toInt()) }
        if (bytes.size > maxBytes) throw InvalidReportException("Mutation report exceeds $maxBytes bytes")
        return MutationReports.parse(bytes)
    }

    fun ingest(target: IngestionTarget, report: MutationReport): MutationSummary {
        val now = Instant.now(clock)
        val runId = Ids.newId(clock)
        val s = report.stats
        jdbc.sql(
            """
            INSERT INTO mutation_runs (id, tenant_id, project_id, snapshot_id, job_id, engine_id, engine_version, seed, max_mutants, test_patterns,
                                       baseline, not_run_reason, mutants_found, total, killed, survived, timed_out, errors, score, duration_ms,
                                       operators, limitations, created_at)
            VALUES (:id, :tenant, :project, :snapshot, :job, :engine, :engineVersion, :seed, :maxMutants, CAST(:patterns AS jsonb),
                    CAST(:baseline AS jsonb), :notRun, :found, :total, :killed, :survived, :timedOut, :errors, :score, :duration,
                    CAST(:operators AS jsonb), CAST(:limitations AS jsonb), :now)
            """,
        ).param("id", runId).param("tenant", target.tenantId).param("project", target.projectId).param("snapshot", target.snapshotId)
            .param("job", target.jobId).param("engine", report.engine.id).param("engineVersion", report.engine.version)
            .param("seed", report.config.seed).param("maxMutants", report.config.maxMutants).param("patterns", Json.write(report.config.testPatterns))
            .param("baseline", Json.write(report.baseline)).param("notRun", report.notRunReason).param("found", report.mutantsFound)
            .param("total", s.total).param("killed", s.killed).param("survived", s.survived).param("timedOut", s.timedOut).param("errors", s.errors)
            .param("score", s.score).param("duration", s.durationMs).param("operators", Json.write(report.engine.operators))
            .param("limitations", Json.write(report.engine.limitations)).param("now", now.sql()).update()
        template.batchUpdate(
            """
            INSERT INTO mutation_results (tenant_id, run_id, mutant_id, path, line, column_no, operator, original, replacement, symbol_key,
                                          status, killed_by, duration_ms)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            report.mutants.map {
                arrayOf<Any?>(target.tenantId, runId, it.id, it.path, it.line, it.column, it.operator, it.original, it.replacement, it.symbolKey,
                    it.status, it.killedBy, it.durationMs)
            },
        )
        log.info("Ingested mutation run {}: {} mutants, {} killed, {} survived, score {}", target.jobId, s.total, s.killed, s.survived, s.score)
        return MutationSummary(s.total, s.killed, s.survived, s.timedOut, s.errors, s.score, report.notRunReason)
    }
}
