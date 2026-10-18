package com.quantumforge.api.fuzzing

import com.quantumforge.api.access.OrgAccess
import com.quantumforge.api.analysis.FindingService
import com.quantumforge.api.analysis.ReproductionView
import com.quantumforge.api.common.Json
import com.quantumforge.api.common.instant
import com.quantumforge.api.common.uuid
import com.quantumforge.api.jobs.FuzzCorpusEntry
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

data class FuzzCampaignView(
    val id: UUID,
    val jobId: UUID,
    val projectId: UUID,
    val snapshotId: UUID,
    val engineId: String,
    val engineVersion: String,
    val seed: Long,
    val iterationsPerTarget: Int,
    val callTimeoutMs: Int,
    val corpusEntries: Int,
    val targetsTested: Int,
    val targetsSkipped: Int,
    val totalCalls: Long,
    val failures: Int,
    val durationMs: Long,
    val oracles: List<Map<String, Any?>>,
    val limitations: List<String>,
    val createdAt: Instant,
)

data class FuzzTargetView(
    val path: String,
    val exportName: String,
    val line: Int,
    val signature: String?,
    val status: String,
    val skipReason: String?,
    val iterations: Int,
    val replayed: Int,
    val rejectedInputs: Int,
    val failures: Int,
    val durationMs: Long,
)

@Service
class FuzzingService(private val jdbc: JdbcClient, private val findings: FindingService) {
    fun campaignForJob(ctx: OrgAccess, jobId: UUID): FuzzCampaignView? =
        jdbc.sql("SELECT * FROM fuzz_campaigns WHERE job_id = :job AND tenant_id = :tenant")
            .param("job", jobId).param("tenant", ctx.tenantId).query { rs, _ -> mapCampaign(rs) }.optional().orElse(null)

    fun campaignsForJobs(ctx: OrgAccess, jobIds: List<UUID>): Map<UUID, FuzzCampaignView> =
        if (jobIds.isEmpty()) emptyMap()
        else jdbc.sql("SELECT * FROM fuzz_campaigns WHERE job_id IN (:jobs) AND tenant_id = :tenant")
            .param("jobs", jobIds).param("tenant", ctx.tenantId).query { rs, _ -> mapCampaign(rs) }.list().associateBy { it.jobId }

    fun targets(ctx: OrgAccess, campaignId: UUID): List<FuzzTargetView> =
        jdbc.sql(
            """
            SELECT * FROM fuzz_targets WHERE campaign_id = :c AND tenant_id = :tenant
            ORDER BY failures DESC, status DESC, path, line, export_name
            """,
        ).param("c", campaignId).param("tenant", ctx.tenantId).query { rs, _ ->
            FuzzTargetView(rs.getString("path"), rs.getString("export_name"), rs.getInt("line"), rs.getString("signature"), rs.getString("status"),
                rs.getString("skip_reason"), rs.getInt("iterations"), rs.getInt("replayed"), rs.getInt("rejected_inputs"), rs.getInt("failures"),
                rs.getLong("duration_ms"))
        }.list()

    fun reproductionsForJob(ctx: OrgAccess, jobId: UUID): List<ReproductionView> = findings.reproductionsForJob(ctx, jobId)

    /**
     * The latest reproduction input of every active fuzzing finding in the project.
     * A campaign replays these first, which is what lets it confirm a fix.
     */
    fun corpusFor(ctx: OrgAccess, projectId: UUID): List<FuzzCorpusEntry> =
        jdbc.sql(
            """
            SELECT DISTINCT ON (r.finding_id) f.fingerprint, r.path, r.export_name, r.input
            FROM defect_reproductions r JOIN findings f ON f.id = r.finding_id AND f.tenant_id = r.tenant_id
            WHERE f.project_id = :project AND f.tenant_id = :tenant AND f.organization_id = :org
              AND f.source = 'FUZZING' AND f.status IN ('OPEN', 'CONFIRMED', 'REOPENED')
            ORDER BY r.finding_id, r.created_at DESC
            LIMIT $MAX_CORPUS
            """,
        ).param("project", projectId).param("tenant", ctx.tenantId).param("org", ctx.organizationId).query { rs, _ ->
            FuzzCorpusEntry(rs.getString("fingerprint"), rs.getString("path"), rs.getString("export_name"), Json.read<List<Any?>>(rs.getString("input")))
        }.list()

    private fun mapCampaign(rs: ResultSet): FuzzCampaignView {
        @Suppress("UNCHECKED_CAST")
        val oracles = Json.read<List<Any?>>(rs.getString("oracles")).map { it as Map<String, Any?> }
        return FuzzCampaignView(
            id = rs.uuid("id"),
            jobId = rs.uuid("job_id"),
            projectId = rs.uuid("project_id"),
            snapshotId = rs.uuid("snapshot_id"),
            engineId = rs.getString("engine_id"),
            engineVersion = rs.getString("engine_version"),
            seed = rs.getLong("seed"),
            iterationsPerTarget = rs.getInt("iterations_per_target"),
            callTimeoutMs = rs.getInt("call_timeout_ms"),
            corpusEntries = rs.getInt("corpus_entries"),
            targetsTested = rs.getInt("targets_tested"),
            targetsSkipped = rs.getInt("targets_skipped"),
            totalCalls = rs.getLong("total_calls"),
            failures = rs.getInt("failures"),
            durationMs = rs.getLong("duration_ms"),
            oracles = oracles,
            limitations = Json.read<List<Any?>>(rs.getString("limitations")).map { it.toString() },
            createdAt = rs.instant("created_at"),
        )
    }

    companion object {
        const val MAX_CORPUS = 500
    }
}
