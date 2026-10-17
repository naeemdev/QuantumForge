package com.quantumforge.api.analysis

import com.quantumforge.api.access.OrgAccess
import com.quantumforge.api.audit.AuditService
import com.quantumforge.api.common.BadRequestException
import com.quantumforge.api.common.ConflictException
import com.quantumforge.api.common.Ids
import com.quantumforge.api.common.Json
import com.quantumforge.api.common.NotFoundException
import com.quantumforge.api.common.Page
import com.quantumforge.api.common.PageRequest
import com.quantumforge.api.common.StaleVersionException
import com.quantumforge.api.common.instant
import com.quantumforge.api.common.instantOrNull
import com.quantumforge.api.common.sql
import com.quantumforge.api.common.uuid
import com.quantumforge.api.common.uuidOrNull
import com.quantumforge.api.security.AuthMethod
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.time.Clock
import java.time.Instant
import java.util.UUID

enum class FindingStatus { OPEN, CONFIRMED, FALSE_POSITIVE, ACCEPTED_RISK, FIXED, REOPENED }

data class FindingView(
    val id: UUID,
    val projectId: UUID,
    val ruleId: String,
    val ruleName: String,
    val ruleVersion: Int,
    val category: String,
    val severity: String,
    val confidence: String,
    val status: FindingStatus,
    val source: FindingSource,
    /**
     * SUSPECTED (static analysis pattern), OBSERVED (failure seen while fuzzing but
     * not every re-run reproduced it) or REPRODUCED (failed again in every fresh re-run).
     */
    val evidenceLevel: String,
    val message: String,
    val path: String,
    val symbolKey: String?,
    val startLine: Int,
    val endLine: Int,
    val snippet: String,
    val firstSeenAt: Instant,
    val lastSeenAt: Instant,
    val lastSeenJobId: UUID,
    val lastSeenSnapshotId: UUID,
    val resolvedAt: Instant?,
    val version: Long,
)

data class FindingOccurrenceView(
    val findingId: UUID,
    val jobId: UUID,
    val snapshotId: UUID,
    val ruleId: String,
    val ruleVersion: Int,
    val severity: String,
    val status: FindingStatus,
    val message: String,
    val path: String,
    val startLine: Int,
    val startColumn: Int,
    val endLine: Int,
    val endColumn: Int,
    val snippet: String,
    val createdAt: Instant,
)

data class StatusChange(
    val fromStatus: String?,
    val toStatus: String,
    val reason: String?,
    val actorType: String,
    val actorId: UUID?,
    val jobId: UUID?,
    val changedAt: Instant,
)

data class RuleView(
    val id: String,
    val version: Int,
    val name: String,
    val category: String,
    val languages: List<String>,
    val summary: String,
    val description: String,
    val remediation: String,
    val severity: String,
    val confidence: String,
)

/** A failure a fuzz campaign observed, with the minimal input and a test that replays it. */
data class ReproductionView(
    val id: UUID,
    val findingId: UUID,
    val jobId: UUID,
    val snapshotId: UUID,
    val path: String,
    val exportName: String,
    val oracle: String,
    val message: String,
    val errorName: String?,
    val stack: String?,
    /** Arguments in the campaign's JSON encoding: {"${'$'}qf": "NaN" | "Infinity" | "-Infinity" | "-0" | "undefined"} tags values JSON cannot carry. */
    val input: Any?,
    val originalInput: Any?,
    val shrinkSteps: Int,
    val attempts: Int,
    val reproduced: Int,
    val reproductionTest: String,
    val createdAt: Instant,
)

data class FindingDetail(
    val finding: FindingView,
    val rule: RuleView,
    val occurrences: List<FindingOccurrenceView>,
    val history: List<StatusChange>,
    val reproductions: List<ReproductionView>,
)

data class FindingFilter(
    val statuses: List<FindingStatus>,
    val severities: List<String>,
    val ruleId: String?,
    val path: String?,
    val query: String?,
    val source: FindingSource? = null,
)

data class FindingCounts(val bySeverity: Map<String, Long>, val byStatus: Map<String, Long>, val byRule: Map<String, Long>)

@Service
class FindingService(private val jdbc: JdbcClient, private val audit: AuditService, private val clock: Clock) {
    private val selectFinding = """
        SELECT f.*, r.name AS rule_name, r.category FROM findings f JOIN rules r ON r.id = f.rule_id
    """.trimIndent()

    fun list(ctx: OrgAccess, projectId: UUID, filter: FindingFilter, page: PageRequest): Page<FindingView> {
        val like = filter.query?.trim()?.takeIf { it.isNotEmpty() }?.let { "%" + it.lowercase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%" }
        val where = buildString {
            append("f.project_id = :project AND f.tenant_id = :tenant AND f.organization_id = :org")
            if (filter.statuses.isNotEmpty()) append(" AND f.status IN (:statuses)")
            if (filter.severities.isNotEmpty()) append(" AND f.severity IN (:severities)")
            if (filter.ruleId != null) append(" AND f.rule_id = :rule")
            if (filter.path != null) append(" AND f.path = :path")
            if (filter.source != null) append(" AND f.source = :source")
            if (like != null) append(" AND (lower(f.message) LIKE :q OR lower(f.path) LIKE :q OR lower(f.snippet) LIKE :q)")
        }
        fun bind(s: JdbcClient.StatementSpec) = s.param("project", projectId).param("tenant", ctx.tenantId).param("org", ctx.organizationId).apply {
            if (filter.statuses.isNotEmpty()) param("statuses", filter.statuses.map { it.name })
            if (filter.severities.isNotEmpty()) param("severities", filter.severities)
            if (filter.ruleId != null) param("rule", filter.ruleId)
            if (filter.path != null) param("path", filter.path)
            if (filter.source != null) param("source", filter.source.name)
            if (like != null) param("q", like)
        }
        val order = "CASE f.severity WHEN 'critical' THEN 0 WHEN 'high' THEN 1 WHEN 'medium' THEN 2 WHEN 'low' THEN 3 ELSE 4 END, f.path, f.start_line"
        val items = bind(jdbc.sql("$selectFinding WHERE $where ORDER BY $order LIMIT :limit OFFSET :offset"))
            .param("limit", page.size).param("offset", page.offset).query(::mapFinding).list()
        val total = bind(jdbc.sql("SELECT count(*) FROM findings f WHERE $where")).query(Long::class.java).single()
        return Page.of(items, page, total)
    }

    fun counts(ctx: OrgAccess, projectId: UUID?, active: Boolean): FindingCounts {
        val where = buildString {
            append("tenant_id = :tenant AND organization_id = :org")
            if (projectId != null) append(" AND project_id = :project")
            if (active) append(" AND status IN ('OPEN', 'CONFIRMED', 'REOPENED')")
        }
        fun group(column: String) = jdbc.sql("SELECT $column AS k, count(*) AS n FROM findings WHERE $where GROUP BY $column")
            .param("tenant", ctx.tenantId).param("org", ctx.organizationId).apply { if (projectId != null) param("project", projectId) }
            .query { rs, _ -> rs.getString("k") to rs.getLong("n") }.list().toMap()
        return FindingCounts(group("severity"), group("status"), group("rule_id"))
    }

    fun get(ctx: OrgAccess, id: UUID): FindingDetail {
        val finding = jdbc.sql("$selectFinding WHERE f.id = :id AND f.tenant_id = :tenant AND f.organization_id = :org")
            .param("id", id).param("tenant", ctx.tenantId).param("org", ctx.organizationId)
            .query(::mapFinding).optional().orElseThrow { NotFoundException("finding", id) }
        val rule = rule(finding.ruleId, finding.ruleVersion)
        val occurrences = jdbc.sql(
            """
            SELECT o.*, f.rule_id, f.status FROM finding_occurrences o JOIN findings f ON f.id = o.finding_id
            WHERE o.finding_id = :id AND o.tenant_id = :tenant ORDER BY o.created_at DESC LIMIT 50
            """,
        ).param("id", id).param("tenant", ctx.tenantId).query(::mapOccurrence).list()
        val history = jdbc.sql("SELECT * FROM finding_status_history WHERE finding_id = :id AND tenant_id = :tenant ORDER BY changed_at, id")
            .param("id", id).param("tenant", ctx.tenantId)
            .query { rs, _ ->
                StatusChange(rs.getString("from_status"), rs.getString("to_status"), rs.getString("reason"), rs.getString("actor_type"),
                    rs.uuidOrNull("actor_id"), rs.uuidOrNull("job_id"), rs.instant("changed_at"))
            }.list()
        val reproductions = jdbc.sql("SELECT * FROM defect_reproductions WHERE finding_id = :id AND tenant_id = :tenant ORDER BY created_at DESC LIMIT 20")
            .param("id", id).param("tenant", ctx.tenantId).query { rs, _ -> mapReproduction(rs) }.list()
        return FindingDetail(finding, rule, occurrences, history, reproductions)
    }

    /**
     * Human triage. FIXED is reserved for the system, because it must be backed by
     * an analysis that no longer detects the issue. Dismissals need a reason.
     */
    @Transactional
    fun changeStatus(ctx: OrgAccess, id: UUID, target: FindingStatus, reason: String?, expectedVersion: Long): FindingDetail {
        if (target == FindingStatus.FIXED || target == FindingStatus.REOPENED) {
            throw BadRequestException("status_not_settable", "$target is set by analyses, not by hand")
        }
        val cleanReason = reason?.trim()?.takeIf { it.isNotEmpty() }?.take(2000)
        if ((target == FindingStatus.FALSE_POSITIVE || target == FindingStatus.ACCEPTED_RISK) && cleanReason == null) {
            throw BadRequestException("reason_required", "A reason is required to mark a finding as ${target.name.lowercase().replace('_', ' ')}")
        }
        val current = jdbc.sql("SELECT status FROM findings WHERE id = :id AND tenant_id = :tenant AND organization_id = :org FOR UPDATE")
            .param("id", id).param("tenant", ctx.tenantId).param("org", ctx.organizationId)
            .query(String::class.java).optional().orElseThrow { NotFoundException("finding", id) }
        if (current == target.name) throw ConflictException("status_unchanged", "Finding is already ${target.name}")
        if (current == FindingStatus.FIXED.name) throw ConflictException("finding_fixed", "Fixed findings change status only when an analysis detects them again")
        val now = Instant.now(clock)
        val updated = jdbc.sql(
            "UPDATE findings SET status = :status, updated_at = :now, updated_by = :user, version = version + 1 WHERE id = :id AND version = :version",
        ).param("status", target.name).param("now", now.sql()).param("user", ctx.userId).param("id", id).param("version", expectedVersion).update()
        if (updated == 0) throw StaleVersionException("finding")
        jdbc.sql(
            """
            INSERT INTO finding_status_history (id, tenant_id, finding_id, from_status, to_status, reason, actor_type, actor_id, changed_at)
            VALUES (:id, :tenant, :finding, :from, :to, :reason, :actorType, :actor, :now)
            """,
        ).param("id", Ids.newId(clock)).param("tenant", ctx.tenantId).param("finding", id).param("from", current).param("to", target.name)
            .param("reason", cleanReason).param("actorType", if (ctx.caller.authMethod == AuthMethod.API_TOKEN) "API_TOKEN" else "USER")
            .param("actor", ctx.userId).param("now", now.sql()).update()
        audit.record(ctx, "finding.status_changed", "finding", id, mapOf("from" to current, "to" to target.name))
        return get(ctx, id)
    }

    fun occurrencesForJob(ctx: OrgAccess, jobId: UUID, page: PageRequest): Page<FindingOccurrenceView> {
        val items = jdbc.sql(
            """
            SELECT o.*, f.rule_id, f.status FROM finding_occurrences o JOIN findings f ON f.id = o.finding_id
            WHERE o.job_id = :job AND o.tenant_id = :tenant AND f.organization_id = :org
            ORDER BY CASE o.severity WHEN 'critical' THEN 0 WHEN 'high' THEN 1 WHEN 'medium' THEN 2 WHEN 'low' THEN 3 ELSE 4 END, o.path, o.start_line
            LIMIT :limit OFFSET :offset
            """,
        ).param("job", jobId).param("tenant", ctx.tenantId).param("org", ctx.organizationId)
            .param("limit", page.size).param("offset", page.offset).query(::mapOccurrence).list()
        val total = jdbc.sql("SELECT count(*) FROM finding_occurrences WHERE job_id = :job AND tenant_id = :tenant")
            .param("job", jobId).param("tenant", ctx.tenantId).query(Long::class.java).single()
        return Page.of(items, page, total)
    }

    fun reproductionsForJob(ctx: OrgAccess, jobId: UUID): List<ReproductionView> =
        jdbc.sql(
            """
            SELECT r.* FROM defect_reproductions r JOIN findings f ON f.id = r.finding_id AND f.tenant_id = r.tenant_id
            WHERE r.job_id = :job AND r.tenant_id = :tenant AND f.organization_id = :org ORDER BY r.path, r.export_name, r.created_at
            """,
        ).param("job", jobId).param("tenant", ctx.tenantId).param("org", ctx.organizationId).query { rs, _ -> mapReproduction(rs) }.list()

    fun occurrencesForFile(ctx: OrgAccess, jobId: UUID, path: String): List<FindingOccurrenceView> =
        jdbc.sql(
            """
            SELECT o.*, f.rule_id, f.status FROM finding_occurrences o JOIN findings f ON f.id = o.finding_id
            WHERE o.job_id = :job AND o.tenant_id = :tenant AND o.path = :path ORDER BY o.start_line
            """,
        ).param("job", jobId).param("tenant", ctx.tenantId).param("path", path).query(::mapOccurrence).list()

    fun rules(): List<RuleView> =
        jdbc.sql("SELECT r.*, v.* FROM rules r JOIN rule_versions v ON v.rule_id = r.id AND v.version = r.latest_version ORDER BY r.id")
            .query { rs, _ -> mapRule(rs) }.list()

    private fun rule(id: String, version: Int): RuleView =
        jdbc.sql("SELECT r.*, v.* FROM rules r JOIN rule_versions v ON v.rule_id = r.id WHERE r.id = :id AND v.version = :version")
            .param("id", id).param("version", version).query { rs, _ -> mapRule(rs) }.single()

    private fun mapRule(rs: ResultSet) = RuleView(
        id = rs.getString("rule_id"),
        version = rs.getInt("version"),
        name = rs.getString("name"),
        category = rs.getString("category"),
        languages = (rs.getArray("languages").array as Array<*>).map { it.toString() },
        summary = rs.getString("summary"),
        description = rs.getString("description"),
        remediation = rs.getString("remediation"),
        severity = rs.getString("severity"),
        confidence = rs.getString("confidence"),
    )

    private fun mapFinding(rs: ResultSet, @Suppress("UNUSED_PARAMETER") i: Int) = FindingView(
        id = rs.uuid("id"),
        projectId = rs.uuid("project_id"),
        ruleId = rs.getString("rule_id"),
        ruleName = rs.getString("rule_name"),
        ruleVersion = rs.getInt("rule_version"),
        category = rs.getString("category"),
        severity = rs.getString("severity"),
        confidence = rs.getString("confidence"),
        status = FindingStatus.valueOf(rs.getString("status")),
        source = FindingSource.valueOf(rs.getString("source")),
        evidenceLevel = rs.getString("evidence_level"),
        message = rs.getString("message"),
        path = rs.getString("path"),
        symbolKey = rs.getString("symbol_key"),
        startLine = rs.getInt("start_line"),
        endLine = rs.getInt("end_line"),
        snippet = rs.getString("snippet"),
        firstSeenAt = rs.instant("first_seen_at"),
        lastSeenAt = rs.instant("last_seen_at"),
        lastSeenJobId = rs.uuid("last_seen_job_id"),
        lastSeenSnapshotId = rs.uuid("last_seen_snapshot_id"),
        resolvedAt = rs.instantOrNull("resolved_at"),
        version = rs.getLong("version"),
    )

    private fun mapReproduction(rs: ResultSet) = ReproductionView(
        id = rs.uuid("id"),
        findingId = rs.uuid("finding_id"),
        jobId = rs.uuid("job_id"),
        snapshotId = rs.uuid("snapshot_id"),
        path = rs.getString("path"),
        exportName = rs.getString("export_name"),
        oracle = rs.getString("oracle"),
        message = rs.getString("message"),
        errorName = rs.getString("error_name"),
        stack = rs.getString("stack"),
        input = Json.read<Any>(rs.getString("input")),
        originalInput = Json.read<Any>(rs.getString("original_input")),
        shrinkSteps = rs.getInt("shrink_steps"),
        attempts = rs.getInt("attempts"),
        reproduced = rs.getInt("reproduced"),
        reproductionTest = rs.getString("reproduction_test"),
        createdAt = rs.instant("created_at"),
    )

    private fun mapOccurrence(rs: ResultSet, @Suppress("UNUSED_PARAMETER") i: Int) = FindingOccurrenceView(
        findingId = rs.uuid("finding_id"),
        jobId = rs.uuid("job_id"),
        snapshotId = rs.uuid("snapshot_id"),
        ruleId = rs.getString("rule_id"),
        ruleVersion = rs.getInt("rule_version"),
        severity = rs.getString("severity"),
        status = FindingStatus.valueOf(rs.getString("status")),
        message = rs.getString("message"),
        path = rs.getString("path"),
        startLine = rs.getInt("start_line"),
        startColumn = rs.getInt("start_column"),
        endLine = rs.getInt("end_line"),
        endColumn = rs.getInt("end_column"),
        snippet = rs.getString("snippet"),
        createdAt = rs.instant("created_at"),
    )
}
