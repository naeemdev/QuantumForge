package com.quantumforge.api.mutation

import com.quantumforge.api.access.OrgAccess
import com.quantumforge.api.common.Json
import com.quantumforge.api.common.instant
import com.quantumforge.api.common.uuid
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

data class MutationRunView(
    val id: UUID,
    val jobId: UUID,
    val projectId: UUID,
    val snapshotId: UUID,
    val engineId: String,
    val engineVersion: String,
    val seed: Long,
    val maxMutants: Int,
    val testPatterns: List<String>,
    val baseline: MutationBaseline,
    val notRunReason: String?,
    val mutantsFound: Int,
    val total: Int,
    val killed: Int,
    val survived: Int,
    val timedOut: Int,
    val errors: Int,
    /** (killed + timed out) / (total - errors) in percent; null when no mutant was scored. */
    val score: Double?,
    val durationMs: Long,
    val operators: List<MutationOperatorInfo>,
    val limitations: List<String>,
    val createdAt: Instant,
)

data class MutantView(
    val mutantId: String,
    val path: String,
    val line: Int,
    val column: Int,
    val operator: String,
    val original: String,
    val replacement: String,
    val symbolKey: String?,
    val status: String,
    val killedBy: String?,
    val durationMs: Long,
)

@Service
class MutationService(private val jdbc: JdbcClient) {
    fun runForJob(ctx: OrgAccess, jobId: UUID): MutationRunView? =
        jdbc.sql("SELECT * FROM mutation_runs WHERE job_id = :job AND tenant_id = :tenant")
            .param("job", jobId).param("tenant", ctx.tenantId).query { rs, _ -> mapRun(rs) }.optional().orElse(null)

    fun runsForJobs(ctx: OrgAccess, jobIds: List<UUID>): Map<UUID, MutationRunView> =
        if (jobIds.isEmpty()) emptyMap()
        else jdbc.sql("SELECT * FROM mutation_runs WHERE job_id IN (:jobs) AND tenant_id = :tenant")
            .param("jobs", jobIds).param("tenant", ctx.tenantId).query { rs, _ -> mapRun(rs) }.list().associateBy { it.jobId }

    /** Survivors first, then by location. */
    fun mutants(ctx: OrgAccess, runId: UUID): List<MutantView> =
        jdbc.sql(
            """
            SELECT * FROM mutation_results WHERE run_id = :run AND tenant_id = :tenant
            ORDER BY CASE status WHEN 'SURVIVED' THEN 0 WHEN 'ERROR' THEN 1 WHEN 'TIMEOUT' THEN 2 ELSE 3 END, path, line, column_no, replacement
            """,
        ).param("run", runId).param("tenant", ctx.tenantId).query { rs, _ ->
            MutantView(rs.getString("mutant_id"), rs.getString("path"), rs.getInt("line"), rs.getInt("column_no"), rs.getString("operator"),
                rs.getString("original"), rs.getString("replacement"), rs.getString("symbol_key"), rs.getString("status"),
                rs.getString("killed_by"), rs.getLong("duration_ms"))
        }.list()

    private fun mapRun(rs: ResultSet) = MutationRunView(
        id = rs.uuid("id"),
        jobId = rs.uuid("job_id"),
        projectId = rs.uuid("project_id"),
        snapshotId = rs.uuid("snapshot_id"),
        engineId = rs.getString("engine_id"),
        engineVersion = rs.getString("engine_version"),
        seed = rs.getLong("seed"),
        maxMutants = rs.getInt("max_mutants"),
        testPatterns = Json.read<List<Any?>>(rs.getString("test_patterns")).map { it.toString() },
        baseline = Json.read<MutationBaseline>(rs.getString("baseline")),
        notRunReason = rs.getString("not_run_reason"),
        mutantsFound = rs.getInt("mutants_found"),
        total = rs.getInt("total"),
        killed = rs.getInt("killed"),
        survived = rs.getInt("survived"),
        timedOut = rs.getInt("timed_out"),
        errors = rs.getInt("errors"),
        score = rs.getBigDecimal("score")?.toDouble(),
        durationMs = rs.getLong("duration_ms"),
        operators = Json.mapper.readValue(rs.getString("operators"), Array<MutationOperatorInfo>::class.java).toList(),
        limitations = Json.read<List<Any?>>(rs.getString("limitations")).map { it.toString() },
        createdAt = rs.instant("created_at"),
    )
}
