package com.quantumforge.api.analysis

import com.quantumforge.api.common.Ids
import com.quantumforge.api.common.Json
import com.quantumforge.api.common.sql
import com.quantumforge.api.common.uuid
import com.quantumforge.api.storage.BlobStore
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class IngestionTarget(
    val tenantId: UUID,
    val organizationId: UUID,
    val projectId: UUID,
    val snapshotId: UUID,
    val jobId: UUID,
    val attemptId: UUID,
)

data class IngestionSummary(val symbols: Int, val imports: Int, val calls: Int, val findings: Int, val newFindings: Int, val fixed: Int, val reopened: Int)

/**
 * Turns a validated analysis report into the code index and updates findings.
 * Runs inside the transaction that completes the job, so a job is SUCCEEDED
 * exactly when its index and findings are stored.
 *
 * Finding lifecycle per project:
 * - unseen fingerprint → new OPEN finding
 * - seen again → occurrence recorded; FIXED findings become REOPENED;
 *   FALSE_POSITIVE and ACCEPTED_RISK keep their human decision
 * - not seen in an analysis of a snapshot at least as new as where it was last
 *   seen, in a file that was analysed → FIXED (OPEN, CONFIRMED, REOPENED only)
 */
@Service
class ReportIngestion(
    private val jdbc: JdbcClient,
    private val template: JdbcTemplate,
    private val blobs: BlobStore,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun loadReport(attemptId: UUID, maxBytes: Long): AnalysisReport {
        val key = jdbc.sql("SELECT blob_key FROM job_artifacts WHERE attempt_id = :a AND kind = 'ANALYSIS_REPORT'")
            .param("a", attemptId).query(String::class.java).optional()
            .orElseThrow { InvalidReportException("The worker did not upload an analysis report") }
        val bytes = blobs.open(key).use { it.readNBytes((maxBytes + 1).toInt()) }
        if (bytes.size > maxBytes) throw InvalidReportException("Analysis report exceeds $maxBytes bytes")
        return AnalysisReports.parse(bytes)
    }

    fun ingest(target: IngestionTarget, report: AnalysisReport): IngestionSummary {
        val now = Instant.now(clock)
        val ts = Timestamp.from(now)
        registerRules(report.rules, now)

        val indexId = Ids.newId(clock)
        jdbc.sql(
            """
            INSERT INTO code_indexes (id, tenant_id, project_id, snapshot_id, job_id, adapter_id, adapter_version, toolchain,
                                      capabilities, limitations, stats, created_at)
            VALUES (:id, :tenant, :project, :snapshot, :job, :adapter, :adapterVersion, :toolchain,
                    CAST(:capabilities AS jsonb), CAST(:limitations AS jsonb), CAST(:stats AS jsonb), :now)
            """,
        ).param("id", indexId).param("tenant", target.tenantId).param("project", target.projectId).param("snapshot", target.snapshotId)
            .param("job", target.jobId).param("adapter", report.adapter.id).param("adapterVersion", report.adapter.version)
            .param("toolchain", report.adapter.toolchain).param("capabilities", Json.write(report.adapter.capabilities))
            .param("limitations", Json.write(report.adapter.limitations)).param("stats", Json.write(report.stats))
            .param("now", now.sql()).update()

        template.batchUpdate(
            "INSERT INTO code_files (tenant_id, index_id, path, language, parsed, line_count, diagnostics, skipped_reason) VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)",
            report.files.map { arrayOf(target.tenantId, indexId, it.path, it.language.take(50), it.parsed, it.lineCount, Json.write(it.diagnostics.take(50)), it.skippedReason?.take(200)) },
        )
        template.batchUpdate(
            """
            INSERT INTO code_symbols (tenant_id, index_id, symbol_key, kind, name, container, path, start_line, start_column, end_line, end_column, exported, complexity, signature)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
            """,
            report.symbols.map {
                arrayOf(target.tenantId, indexId, it.key, it.kind, it.name, it.container, it.path, it.range.startLine, it.range.startColumn,
                    it.range.endLine, it.range.endColumn, it.exported, it.complexity, it.signature)
            },
        )
        template.batchUpdate(
            "INSERT INTO code_imports (tenant_id, index_id, from_path, specifier, to_path, external, package_name, line) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            report.imports.map { arrayOf(target.tenantId, indexId, it.from, it.specifier.take(500), it.to, it.external, it.packageName?.take(200), it.line) },
        )
        template.batchUpdate(
            "INSERT INTO code_calls (tenant_id, index_id, from_key, to_key, path, line) VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
            report.calls.map { arrayOf(target.tenantId, indexId, it.from, it.to, it.path, it.line) },
        )

        val (created, reopened) = upsertFindings(target, report, now, ts)
        val fixed = markFixed(target, report, now)
        log.info("Ingested analysis {}: {} symbols, {} findings ({} new, {} reopened, {} fixed)", target.jobId, report.symbols.size, report.findings.size, created, reopened, fixed)
        return IngestionSummary(report.symbols.size, report.imports.size, report.calls.size, report.findings.size, created, fixed, reopened)
    }

    private fun registerRules(rules: List<RuleReport>, now: Instant) {
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

    private fun upsertFindings(target: IngestionTarget, report: AnalysisReport, now: Instant, ts: Timestamp): Pair<Int, Int> {
        // newer: this snapshot is at least as new as where the finding was last seen.
        // canReopen: at least as new as the snapshot whose analysis resolved it.
        data class Existing(val id: UUID, val status: String, val newer: Boolean, val canReopen: Boolean)
        val existing = if (report.findings.isEmpty()) emptyMap() else jdbc.sql(
            """
            SELECT f.id, f.fingerprint, f.status,
                   cur.created_at >= last.created_at AS newer,
                   (resolved.id IS NULL OR cur.created_at >= resolved.created_at) AS can_reopen
            FROM findings f
            JOIN source_snapshots cur ON cur.id = :snapshot
            JOIN source_snapshots last ON last.id = f.last_seen_snapshot_id
            LEFT JOIN source_snapshots resolved ON resolved.id = f.resolved_snapshot_id
            WHERE f.project_id = :project AND f.tenant_id = :tenant AND f.fingerprint IN (:fps)
            FOR UPDATE OF f
            """,
        ).param("snapshot", target.snapshotId).param("project", target.projectId).param("tenant", target.tenantId)
            .param("fps", report.findings.map { it.fingerprint })
            .query { rs, _ -> rs.getString("fingerprint") to Existing(rs.uuid("id"), rs.getString("status"), rs.getBoolean("newer"), rs.getBoolean("can_reopen")) }
            .list().toMap()

        var created = 0
        var reopened = 0
        val occurrences = ArrayList<Array<Any?>>()
        val history = ArrayList<Array<Any?>>()
        for (f in report.findings) {
            val current = existing[f.fingerprint]
            val findingId: UUID
            if (current == null) {
                findingId = Ids.newId(clock)
                jdbc.sql(
                    """
                    INSERT INTO findings (id, tenant_id, organization_id, project_id, fingerprint, rule_id, rule_version, severity, confidence, status,
                                          message, path, symbol_key, start_line, end_line, snippet, first_seen_job_id, first_seen_at,
                                          last_seen_job_id, last_seen_snapshot_id, last_seen_at, created_at, updated_at)
                    VALUES (:id, :tenant, :org, :project, :fp, :rule, :ruleVersion, :severity, :confidence, 'OPEN',
                            :message, :path, :symbol, :startLine, :endLine, :snippet, :job, :now, :job, :snapshot, :now, :now, :now)
                    """,
                ).param("id", findingId).param("tenant", target.tenantId).param("org", target.organizationId).param("project", target.projectId)
                    .param("fp", f.fingerprint).param("rule", f.ruleId).param("ruleVersion", f.ruleVersion).param("severity", f.severity)
                    .param("confidence", f.confidence).param("message", f.message).param("path", f.path).param("symbol", f.symbolKey)
                    .param("startLine", f.range.startLine).param("endLine", f.range.endLine).param("snippet", f.snippet)
                    .param("job", target.jobId).param("snapshot", target.snapshotId).param("now", now.sql()).update()
                history += arrayOf(Ids.newId(clock), target.tenantId, findingId, null, "OPEN", "Detected by analysis", target.jobId, ts)
                created++
            } else {
                findingId = current.id
                val reopen = current.status == "FIXED" && current.canReopen
                if (current.newer || reopen) jdbc.sql(
                    """
                    UPDATE findings SET rule_version = :ruleVersion, severity = :severity, confidence = :confidence, message = :message,
                           path = :path, symbol_key = :symbol, start_line = :startLine, end_line = :endLine, snippet = :snippet,
                           last_seen_job_id = :job, last_seen_snapshot_id = :snapshot, last_seen_at = :now,
                           status = CASE WHEN :reopen THEN 'REOPENED' ELSE status END,
                           resolved_at = CASE WHEN :reopen THEN NULL ELSE resolved_at END,
                           resolved_snapshot_id = CASE WHEN :reopen THEN NULL ELSE resolved_snapshot_id END,
                           updated_at = :now, version = version + 1
                    WHERE id = :id
                    """,
                ).param("ruleVersion", f.ruleVersion).param("severity", f.severity).param("confidence", f.confidence).param("message", f.message)
                    .param("path", f.path).param("symbol", f.symbolKey).param("startLine", f.range.startLine).param("endLine", f.range.endLine)
                    .param("snippet", f.snippet).param("job", target.jobId).param("snapshot", target.snapshotId).param("now", now.sql())
                    .param("reopen", reopen).param("id", findingId).update()
                if (reopen) {
                    history += arrayOf(Ids.newId(clock), target.tenantId, findingId, "FIXED", "REOPENED", "Detected again after being fixed", target.jobId, ts)
                    reopened++
                }
            }
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
        return created to reopened
    }

    private fun markFixed(target: IngestionTarget, report: AnalysisReport, now: Instant): Int {
        val analysedPaths = report.files.filter { it.parsed }.map { it.path }
        if (analysedPaths.isEmpty()) return 0
        val seen = report.findings.map { it.fingerprint }
        data class Candidate(val id: UUID, val status: String)
        val candidates = jdbc.sql(
            """
            SELECT f.id, f.status FROM findings f
            JOIN source_snapshots last ON last.id = f.last_seen_snapshot_id
            JOIN source_snapshots current ON current.id = :snapshot
            WHERE f.project_id = :project AND f.tenant_id = :tenant
              AND f.status IN ('OPEN', 'CONFIRMED', 'REOPENED')
              AND f.path IN (:paths)
              ${if (seen.isEmpty()) "" else "AND f.fingerprint NOT IN (:seen)"}
              AND current.created_at >= last.created_at
            FOR UPDATE OF f
            """,
        ).param("snapshot", target.snapshotId).param("project", target.projectId).param("tenant", target.tenantId)
            .param("paths", analysedPaths).apply { if (seen.isNotEmpty()) param("seen", seen) }
            .query { rs, _ -> Candidate(rs.uuid("id"), rs.getString("status")) }.list()
        if (candidates.isEmpty()) return 0
        jdbc.sql(
            "UPDATE findings SET status = 'FIXED', resolved_at = :now, resolved_snapshot_id = :snapshot, updated_at = :now, version = version + 1 WHERE id IN (:ids)",
        ).param("now", now.sql()).param("snapshot", target.snapshotId).param("ids", candidates.map { it.id }).update()
        val ts = Timestamp.from(now)
        insertHistory(candidates.map { arrayOf(Ids.newId(clock), target.tenantId, it.id, it.status, "FIXED", "No longer detected by analysis", target.jobId, ts) })
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
