package com.quantumforge.api.analysis

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
 * The finding lifecycle is in [FindingRecorder].
 */
@Service
class ReportIngestion(
    private val jdbc: JdbcClient,
    private val template: JdbcTemplate,
    private val blobs: BlobStore,
    private val recorder: FindingRecorder,
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
        recorder.registerRules(report.rules, now)

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

        val recorded = recorder.record(target, FindingSource.STATIC_ANALYSIS, report.findings.map { RecordedFinding(it, "SUSPECTED") }, now)
        val created = recorded.created
        val reopened = recorded.reopened
        val analysedPaths = report.files.filter { it.parsed }.map { it.path }
        val fixed = recorder.resolveMissing(target, FindingSource.STATIC_ANALYSIS, ResolutionScope.Paths(analysedPaths), report.findings.map { it.fingerprint }, now)
        log.info("Ingested analysis {}: {} symbols, {} findings ({} new, {} reopened, {} fixed)", target.jobId, report.symbols.size, report.findings.size, created, reopened, fixed)
        return IngestionSummary(report.symbols.size, report.imports.size, report.calls.size, report.findings.size, created, fixed, reopened)
    }
}
