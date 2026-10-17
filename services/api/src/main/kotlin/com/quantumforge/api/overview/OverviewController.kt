package com.quantumforge.api.overview

import com.quantumforge.api.access.AccessService
import com.quantumforge.api.access.Permission
import com.quantumforge.api.common.instant
import com.quantumforge.api.common.instantOrNull
import com.quantumforge.api.common.uuid
import com.quantumforge.api.common.Json
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

data class OrganizationOverview(
    val projectCount: Long,
    val snapshotCount: Long,
    val jobsByState: Map<String, Long>,
    val activeJobs: Long,
    val activeJobLimit: Int,
    val testTotals: TestTotals,
    val recentJobs: List<RecentJob>,
    val recentActivity: List<ActivityItem>,
)

data class TestTotals(val runs: Long, val passed: Long, val failed: Long, val skipped: Long, val errored: Long)

data class RecentJob(
    val id: UUID,
    val projectId: UUID,
    val projectName: String,
    val state: String,
    val createdAt: Instant,
    val finishedAt: Instant?,
    val summary: Map<String, Any?>?,
)

data class ActivityItem(val action: String, val outcome: String, val actorType: String, val targetType: String?, val occurredAt: Instant)

/** Dashboard numbers. Every value is a live query over the organization's own rows. */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/overview")
@Tag(name = "Overview")
class OverviewController(private val access: AccessService, private val jdbc: JdbcClient) {
    @GetMapping
    fun overview(@PathVariable organizationId: UUID): OrganizationOverview {
        val ctx = access.require(organizationId, Permission.PROJECT_READ)
        fun count(sql: String) = jdbc.sql(sql).param("tenant", ctx.tenantId).param("org", organizationId).query(Long::class.java).single()

        val projects = count("SELECT count(*) FROM projects WHERE tenant_id = :tenant AND organization_id = :org")
        val snapshots = count(
            """
            SELECT count(*) FROM source_snapshots s JOIN repositories r ON r.id = s.repository_id AND r.tenant_id = s.tenant_id
            JOIN projects p ON p.id = r.project_id AND p.tenant_id = r.tenant_id WHERE s.tenant_id = :tenant AND p.organization_id = :org
            """,
        )
        val byState = jdbc.sql("SELECT state, count(*) AS n FROM analysis_jobs WHERE tenant_id = :tenant AND organization_id = :org GROUP BY state")
            .param("tenant", ctx.tenantId).param("org", organizationId)
            .query { rs, _ -> rs.getString("state") to rs.getLong("n") }.list().toMap()
        val active = jdbc.sql("SELECT count(*) FROM analysis_jobs WHERE tenant_id = :tenant AND state IN ('QUEUED','LEASED','RUNNING')")
            .param("tenant", ctx.tenantId).query(Long::class.java).single()
        val limit = jdbc.sql("SELECT max_active_jobs FROM tenants WHERE id = :tenant").param("tenant", ctx.tenantId).query(Int::class.java).single()
        val totals = jdbc.sql(
            """
            SELECT count(*) AS runs, coalesce(sum(r.passed),0) AS passed, coalesce(sum(r.failed),0) AS failed,
                   coalesce(sum(r.skipped),0) AS skipped, coalesce(sum(r.errored),0) AS errored
            FROM test_runs r JOIN analysis_jobs j ON j.id = r.job_id AND j.tenant_id = r.tenant_id
            WHERE r.tenant_id = :tenant AND j.organization_id = :org
            """,
        ).param("tenant", ctx.tenantId).param("org", organizationId)
            .query { rs, _ -> TestTotals(rs.getLong("runs"), rs.getLong("passed"), rs.getLong("failed"), rs.getLong("skipped"), rs.getLong("errored")) }.single()
        val recent = jdbc.sql(
            """
            SELECT j.id, j.project_id, p.name, j.state, j.created_at, j.finished_at, j.result_summary
            FROM analysis_jobs j JOIN projects p ON p.id = j.project_id AND p.tenant_id = j.tenant_id
            WHERE j.tenant_id = :tenant AND j.organization_id = :org ORDER BY j.created_at DESC LIMIT 10
            """,
        ).param("tenant", ctx.tenantId).param("org", organizationId).query { rs, _ ->
            RecentJob(rs.uuid("id"), rs.uuid("project_id"), rs.getString("name"), rs.getString("state"), rs.instant("created_at"),
                rs.instantOrNull("finished_at"), rs.getString("result_summary")?.let { Json.readMap(it) })
        }.list()
        val activity = jdbc.sql(
            "SELECT action, outcome, actor_type, target_type, occurred_at FROM audit_events WHERE tenant_id = :tenant AND organization_id = :org ORDER BY occurred_at DESC, id DESC LIMIT 10",
        ).param("tenant", ctx.tenantId).param("org", organizationId).query { rs, _ ->
            ActivityItem(rs.getString("action"), rs.getString("outcome"), rs.getString("actor_type"), rs.getString("target_type"), rs.instant("occurred_at"))
        }.list()
        return OrganizationOverview(projects, snapshots, byState, active, limit, totals, recent, activity)
    }
}
