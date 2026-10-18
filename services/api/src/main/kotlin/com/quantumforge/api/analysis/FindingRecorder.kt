package com.quantumforge.api.analysis

import com.quantumforge.api.common.Ids
import com.quantumforge.api.common.sql
import com.quantumforge.api.common.uuid
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID

enum class FindingSource(val detected: String, val redetected: String, val resolved: String) {
    STATIC_ANALYSIS("Detected by analysis", "Detected again after being fixed", "No longer detected by analysis"),
    FUZZING("Reproduced by fuzzing", "Reproduced again after being fixed", "Its reproduction input no longer fails"),
}

/** A finding as reported by an engine, with the strength of its evidence (SUSPECTED, OBSERVED, REPRODUCED). */
data class RecordedFinding(val report: FindingReport, val evidenceLevel: String)

data class RecordResult(val created: Int, val reopened: Int, val findingIds: Map<String, UUID>)

sealed interface ResolutionScope {
    /** Static analysis: every file the analysis parsed. */
    data class Paths(val paths: List<String>) : ResolutionScope
    /** Fuzzing: only findings whose reproduction input this campaign replayed. */
    data class Fingerprints(val fingerprints: List<String>) : ResolutionScope
}

/**
 * Finding lifecycle per project and source, shared by every engine:
 * - unseen fingerprint → new OPEN finding
 * - seen again → occurrence recorded; FIXED findings become REOPENED;
 *   FALSE_POSITIVE and ACCEPTED_RISK keep their human decision
 * - not seen by a job of the same source whose scope covered it, on a snapshot at
 *   least as new as where it was last seen → FIXED (OPEN, CONFIRMED, REOPENED only)
 *
 * Callers run inside the transaction that completes the job.
 */
@Service
class FindingRecorder(
    private val jdbc: JdbcClient,
    private val template: JdbcTemplate,
    private val clock: Clock,
) {
    fun registerRules(rules: List<RuleReport>, now: Instant) {
        for (r in rules) {
            jdbc.sql(
                """
                INSERT INTO rules (id, name, category, languages, latest_version, created_at, updated_at)
                VALUES (:id, :name, :category, :languages, :version, :now, :now)
                ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, category = EXCLUDED.category, languages = EXCLUDED.languages,
                    latest_version = GREATEST(rules.latest_version, EXCLUDED.latest_version), updated_at = EXCLUDED.updated_at
                """,
            ).param("id", r.id).param("name", r.name.take(100)).param("category", r.category.take(50))
                .param("languages", r.languages.take(10).toTypedArray()).param("version", r.version).param("now", now.sql()).update()
            jdbc.sql(
                """
                INSERT INTO rule_versions (rule_id, version, summary, description, remediation, severity, confidence, created_at)
                VALUES (:id, :version, :summary, :description, :remediation, :severity, :confidence, :now)
                ON CONFLICT (rule_id, version) DO NOTHING
                """,
            ).param("id", r.id).param("version", r.version).param("summary", r.summary.take(300)).param("description", r.description.take(4000))
                .param("remediation", r.remediation.take(2000)).param("severity", r.severity).param("confidence", r.confidence)
                .param("now", now.sql()).update()
        }
    }

    fun record(target: IngestionTarget, source: FindingSource, findings: List<RecordedFinding>, now: Instant): RecordResult {
        val ts = Timestamp.from(now)
        // newer: this snapshot is at least as new as where the finding was last seen.
        // canReopen: at least as new as the snapshot whose analysis resolved it.
        data class Existing(val id: UUID, val status: String, val newer: Boolean, val canReopen: Boolean)
        val existing = if (findings.isEmpty()) emptyMap() else jdbc.sql(
            """
            SELECT f.id, f.fingerprint, f.status,
                   cur.created_at >= last.created_at AS newer,
                   (resolved.id IS NULL OR cur.created_at >= resolved.created_at) AS can_reopen
            FROM findings f
            JOIN source_snapshots cur ON cur.id = :snapshot
            JOIN source_snapshots last ON last.id = f.last_seen_snapshot_id
            LEFT JOIN source_snapshots resolved ON resolved.id = f.resolved_snapshot_id
            WHERE f.project_id = :project AND f.tenant_id = :tenant AND f.source = :source AND f.fingerprint IN (:fps)
            FOR UPDATE OF f
            """,
        ).param("snapshot", target.snapshotId).param("project", target.projectId).param("tenant", target.tenantId)
            .param("source", source.name).param("fps", findings.map { it.report.fingerprint })
            .query { rs, _ -> rs.getString("fingerprint") to Existing(rs.uuid("id"), rs.getString("status"), rs.getBoolean("newer"), rs.getBoolean("can_reopen")) }
            .list().toMap()

        var created = 0
        var reopened = 0
        val ids = LinkedHashMap<String, UUID>()
        val occurrences = ArrayList<Array<Any?>>()
        val history = ArrayList<Array<Any?>>()
        for (recorded in findings) {
            val f = recorded.report
            val current = existing[f.fingerprint]
            val findingId: UUID
            if (current == null) {
                findingId = Ids.newId(clock)
                jdbc.sql(
                    """
                    INSERT INTO findings (id, tenant_id, organization_id, project_id, fingerprint, rule_id, rule_version, severity, confidence, status,
                                          message, path, symbol_key, start_line, end_line, snippet, first_seen_job_id, first_seen_at,
                                          last_seen_job_id, last_seen_snapshot_id, last_seen_at, created_at, updated_at, source, evidence_level)
                    VALUES (:id, :tenant, :org, :project, :fp, :rule, :ruleVersion, :severity, :confidence, 'OPEN',
                            :message, :path, :symbol, :startLine, :endLine, :snippet, :job, :now, :job, :snapshot, :now, :now, :now,
                            :source, :evidence)
                    """,
                ).param("id", findingId).param("tenant", target.tenantId).param("org", target.organizationId).param("project", target.projectId)
                    .param("fp", f.fingerprint).param("rule", f.ruleId).param("ruleVersion", f.ruleVersion).param("severity", f.severity)
                    .param("confidence", f.confidence).param("message", f.message).param("path", f.path).param("symbol", f.symbolKey)
                    .param("startLine", f.range.startLine).param("endLine", f.range.endLine).param("snippet", f.snippet)
                    .param("job", target.jobId).param("snapshot", target.snapshotId).param("now", now.sql())
                    .param("source", source.name).param("evidence", recorded.evidenceLevel).update()
                history += arrayOf(Ids.newId(clock), target.tenantId, findingId, null, "OPEN", source.detected, target.jobId, ts)
                created++
            } else {
                findingId = current.id
                val reopen = current.status == "FIXED" && current.canReopen
                if (current.newer || reopen) jdbc.sql(
                    """
                    UPDATE findings SET rule_version = :ruleVersion, severity = :severity, confidence = :confidence, message = :message,
                           path = :path, symbol_key = :symbol, start_line = :startLine, end_line = :endLine, snippet = :snippet,
                           last_seen_job_id = :job, last_seen_snapshot_id = :snapshot, last_seen_at = :now, evidence_level = :evidence,
                           status = CASE WHEN :reopen THEN 'REOPENED' ELSE status END,
                           resolved_at = CASE WHEN :reopen THEN NULL ELSE resolved_at END,
                           resolved_snapshot_id = CASE WHEN :reopen THEN NULL ELSE resolved_snapshot_id END,
                           updated_at = :now, version = version + 1
                    WHERE id = :id
                    """,
                ).param("ruleVersion", f.ruleVersion).param("severity", f.severity).param("confidence", f.confidence).param("message", f.message)
                    .param("path", f.path).param("symbol", f.symbolKey).param("startLine", f.range.startLine).param("endLine", f.range.endLine)
                    .param("snippet", f.snippet).param("job", target.jobId).param("snapshot", target.snapshotId).param("now", now.sql())
                    .param("reopen", reopen).param("evidence", recorded.evidenceLevel).param("id", findingId).update()
                if (reopen) {
                    history += arrayOf(Ids.newId(clock), target.tenantId, findingId, "FIXED", "REOPENED", source.redetected, target.jobId, ts)
                    reopened++
                }
            }
            ids[f.fingerprint] = findingId
            occurrences += arrayOf(
                Ids.newId(clock), target.tenantId, findingId, target.jobId, target.snapshotId, f.ruleVersion, f.severity, f.message, f.path,
                f.range.startLine, f.range.startColumn, f.range.endLine, f.range.endColumn, f.snippet, ts,
            )
        }
        template.batchUpdate(
            """
            INSERT INTO finding_occurrences (id, tenant_id, finding_id, job_id, snapshot_id, rule_version, severity, message, path,
                                             start_line, start_column, end_line, end_column, snippet, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (finding_id, job_id) DO NOTHING
            """,
            occurrences,
        )
        insertHistory(history)
        return RecordResult(created, reopened, ids)
    }

    /**
     * Resolves active findings of [source] within [scope] whose fingerprints were not
     * [seen] by this job, provided the job's snapshot is at least as new as where
     * each was last seen.
     */
    fun resolveMissing(target: IngestionTarget, source: FindingSource, scope: ResolutionScope, seen: List<String>, now: Instant): Int {
        val (scopeSql, scopeValues) = when (scope) {
            is ResolutionScope.Paths -> "f.path IN (:scope)" to scope.paths
            is ResolutionScope.Fingerprints -> "f.fingerprint IN (:scope)" to scope.fingerprints
        }
        if (scopeValues.isEmpty()) return 0
        data class Candidate(val id: UUID, val status: String)
        val candidates = jdbc.sql(
            """
            SELECT f.id, f.status FROM findings f
            JOIN source_snapshots last ON last.id = f.last_seen_snapshot_id
            JOIN source_snapshots current ON current.id = :snapshot
            WHERE f.project_id = :project AND f.tenant_id = :tenant
              AND f.status IN ('OPEN', 'CONFIRMED', 'REOPENED')
              AND f.source = :source AND $scopeSql
              ${if (seen.isEmpty()) "" else "AND f.fingerprint NOT IN (:seen)"}
              AND current.created_at >= last.created_at
            FOR UPDATE OF f
            """,
        ).param("snapshot", target.snapshotId).param("project", target.projectId).param("tenant", target.tenantId)
            .param("source", source.name).param("scope", scopeValues).apply { if (seen.isNotEmpty()) param("seen", seen) }
            .query { rs, _ -> Candidate(rs.uuid("id"), rs.getString("status")) }.list()
        if (candidates.isEmpty()) return 0
        jdbc.sql(
            "UPDATE findings SET status = 'FIXED', resolved_at = :now, resolved_snapshot_id = :snapshot, updated_at = :now, version = version + 1 WHERE id IN (:ids)",
        ).param("now", now.sql()).param("snapshot", target.snapshotId).param("ids", candidates.map { it.id }).update()
        val ts = Timestamp.from(now)
        insertHistory(candidates.map { arrayOf(Ids.newId(clock), target.tenantId, it.id, it.status, "FIXED", source.resolved, target.jobId, ts) })
        return candidates.size
    }

    private fun insertHistory(rows: List<Array<Any?>>) {
        if (rows.isEmpty()) return
        template.batchUpdate(
            """
            INSERT INTO finding_status_history (id, tenant_id, finding_id, from_status, to_status, reason, actor_type, job_id, changed_at)
            VALUES (?, ?, ?, ?, ?, ?, 'SYSTEM', ?, ?)
            """,
            rows,
        )
    }
}
