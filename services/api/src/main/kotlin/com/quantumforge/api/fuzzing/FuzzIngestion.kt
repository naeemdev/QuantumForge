package com.quantumforge.api.fuzzing

import com.quantumforge.api.analysis.FindingRecorder
import com.quantumforge.api.analysis.FindingReport
import com.quantumforge.api.analysis.FindingSource
import com.quantumforge.api.analysis.IngestionTarget
import com.quantumforge.api.analysis.InvalidReportException
import com.quantumforge.api.analysis.RecordedFinding
import com.quantumforge.api.analysis.ResolutionScope
import com.quantumforge.api.analysis.RuleReport
import com.quantumforge.api.analysis.SourceRange
import com.quantumforge.api.common.Ids
import com.quantumforge.api.common.Json
import com.quantumforge.api.common.sql
import com.quantumforge.api.jobs.ExecutionConfig
import com.quantumforge.api.storage.BlobStore
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class FuzzSummary(
    val targetsTested: Int,
    val targetsSkipped: Int,
    val totalCalls: Long,
    val failures: Int,
    val newFindings: Int,
    val reopened: Int,
    val fixed: Int,
)

/**
 * Stores a campaign and turns its failures into findings with reproductions.
 * Runs inside the transaction that completes the job.
 *
 * A fuzzing finding is resolved only by evidence: the campaign replayed its
 * stored reproduction input (the job's corpus) and that input no longer fails
 * the same way. Not stumbling on a failure again by chance resolves nothing.
 */
@Service
class FuzzIngestion(
    private val jdbc: JdbcClient,
    private val template: JdbcTemplate,
    private val blobs: BlobStore,
    private val recorder: FindingRecorder,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun loadReport(attemptId: UUID, maxBytes: Long): FuzzReport {
        val key = jdbc.sql("SELECT blob_key FROM job_artifacts WHERE attempt_id = :a AND kind = 'FUZZ_REPORT'")
            .param("a", attemptId).query(String::class.java).optional()
            .orElseThrow { InvalidReportException("The worker did not upload a fuzz report") }
        val bytes = blobs.open(key).use { it.readNBytes((maxBytes + 1).toInt()) }
        if (bytes.size > maxBytes) throw InvalidReportException("Fuzz report exceeds $maxBytes bytes")
        return FuzzReports.parse(bytes)
    }

    fun ingest(target: IngestionTarget, report: FuzzReport): FuzzSummary {
        val now = Instant.now(clock)
        val ts = Timestamp.from(now)
        recorder.registerRules(RULES.values.toList(), now)

        val campaignId = Ids.newId(clock)
        jdbc.sql(
            """
            INSERT INTO fuzz_campaigns (id, tenant_id, project_id, snapshot_id, job_id, engine_id, engine_version, seed, iterations_per_target,
                                        call_timeout_ms, corpus_entries, targets_tested, targets_skipped, total_calls, failures, duration_ms,
                                        oracles, limitations, created_at)
            VALUES (:id, :tenant, :project, :snapshot, :job, :engine, :engineVersion, :seed, :iterations, :callTimeout, :corpus, :tested,
                    :skipped, :calls, :failures, :duration, CAST(:oracles AS jsonb), CAST(:limitations AS jsonb), :now)
            """,
        ).param("id", campaignId).param("tenant", target.tenantId).param("project", target.projectId).param("snapshot", target.snapshotId)
            .param("job", target.jobId).param("engine", report.engine.id).param("engineVersion", report.engine.version)
            .param("seed", report.config.seed).param("iterations", report.config.iterationsPerTarget).param("callTimeout", report.config.callTimeoutMs)
            .param("corpus", report.config.corpusEntries).param("tested", report.targets.size).param("skipped", report.skipped.size)
            .param("calls", report.stats.totalCalls).param("failures", report.stats.failures).param("duration", report.stats.durationMs)
            .param("oracles", Json.write(report.engine.oracles)).param("limitations", Json.write(report.engine.limitations))
            .param("now", now.sql()).update()

        template.batchUpdate(
            """
            INSERT INTO fuzz_targets (tenant_id, campaign_id, path, export_name, line, signature, status, skip_reason, iterations, replayed,
                                      rejected_inputs, failures, duration_ms)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
            """,
            report.targets.map {
                arrayOf<Any?>(target.tenantId, campaignId, it.path, it.exportName, it.line, it.signature, "TESTED", null, it.iterations,
                    it.replayed, it.rejectedInputs, it.failures.size, it.durationMs)
            } + report.skipped.map {
                arrayOf<Any?>(target.tenantId, campaignId, it.path, it.exportName.take(200), it.line, null, "SKIPPED", it.reason, 0, 0, 0, 0, 0L)
            },
        )

        val failures = report.targets.flatMap { t -> t.failures.map { t to it } }
        val recorded = recorder.record(target, FindingSource.FUZZING, failures.map { (t, f) -> RecordedFinding(toFinding(t, f), evidenceOf(f)) }, now)
        template.batchUpdate(
            """
            INSERT INTO defect_reproductions (id, tenant_id, finding_id, campaign_id, job_id, snapshot_id, path, export_name, oracle, message,
                                              error_name, stack, input, original_input, shrink_steps, attempts, reproduced, reproduction_test, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, ?, ?, ?) ON CONFLICT (finding_id, job_id) DO NOTHING
            """,
            failures.map { (t, f) ->
                arrayOf<Any?>(Ids.newId(clock), target.tenantId, recorded.findingIds.getValue(f.fingerprint), campaignId, target.jobId, target.snapshotId,
                    t.path, t.exportName, f.oracle, f.message, f.errorName, f.stack, Json.write(f.input), Json.write(f.originalInput), f.shrinkSteps,
                    f.reproductions.attempts, f.reproductions.reproduced, f.reproductionTest, ts)
            },
        )

        // Only corpus entries for functions this campaign actually tested were replayed.
        val tested = report.targets.map { "${it.path}#${it.exportName}" }.toSet()
        val replayed = corpusOf(target.jobId).filter { "${it.path}#${it.exportName}" in tested }.map { it.fingerprint }
        val fixed = recorder.resolveMissing(target, FindingSource.FUZZING, ResolutionScope.Fingerprints(replayed), failures.map { it.second.fingerprint }, now)
        log.info("Ingested fuzz campaign {}: {} targets, {} failures ({} new, {} reopened, {} fixed)",
            target.jobId, report.targets.size, failures.size, recorded.created, recorded.reopened, fixed)
        return FuzzSummary(report.targets.size, report.skipped.size, report.stats.totalCalls, failures.size, recorded.created, recorded.reopened, fixed)
    }

    private fun corpusOf(jobId: UUID) =
        jdbc.sql("SELECT config FROM analysis_jobs WHERE id = :id").param("id", jobId).query(String::class.java).single()
            .let { Json.read<ExecutionConfig>(it).fuzz?.corpus.orEmpty() }

    private fun evidenceOf(f: FuzzFailure) = if (f.reproductions.reproduced == f.reproductions.attempts) "REPRODUCED" else "OBSERVED"

    private fun toFinding(t: FuzzTargetReport, f: FuzzFailure): FindingReport {
        val rule = RULES.getValue(f.oracle)
        val call = callLiteral(t.exportName, f.input).let { if (it.length > 200) it.take(197) + "..." else it }
        val message = when {
            f.oracle == "runtime-error" -> "$call threw ${f.errorName ?: "an error"}: ${f.message}"
            f.oracle == "non-finite-result" && !f.message.startsWith("returned ") -> "$call: ${f.message}"
            else -> "$call ${f.message}"
        }
        return FindingReport(
            ruleId = rule.id,
            ruleVersion = rule.version,
            severity = rule.severity,
            confidence = if (evidenceOf(f) == "REPRODUCED") "high" else "medium",
            message = message,
            path = t.path,
            range = SourceRange(t.line, 1, t.line, 1),
            snippet = t.signature,
            symbolKey = "${t.path}#${t.exportName}",
            fingerprint = f.fingerprint,
        )
    }

    companion object {
        /** The failing call as JavaScript, e.g. `averageUnitPrice({ lines: [] })`; reads the campaign's tagged encoding. */
        fun callLiteral(name: String, args: List<Any?>): String = "$name(${args.joinToString(", ") { literal(it) }})"

        private fun literal(value: Any?): String = when (value) {
            null -> "null"
            is String -> Json.write(value)
            is List<*> -> value.joinToString(", ", "[", "]") { literal(it) }
            is Map<*, *> -> (value["${'$'}qf"] as? String)
                ?: if (value.isEmpty()) "{}" else value.entries.joinToString(", ", "{ ", " }") { (k, v) ->
                    val key = k.toString()
                    "${if (IDENTIFIER.matches(key)) key else Json.write(key)}: ${literal(v)}"
                }
            is Double -> if (value % 1.0 == 0.0 && kotlin.math.abs(value) < 1e15) value.toLong().toString() else value.toString()
            else -> value.toString()
        }

        private val IDENTIFIER = Regex("^[A-Za-z_$][A-Za-z0-9_$]*$")

        private fun rule(id: String, name: String, severity: String, summary: String, description: String, remediation: String) =
            RuleReport(id, 1, name, summary, description, remediation, "fuzzing", severity, "high", listOf("TypeScript", "JavaScript"))

        /** One rule per oracle, so fuzzing findings share the rule catalogue and triage with static analysis. */
        val RULES: Map<String, RuleReport> = mapOf(
            "runtime-error" to rule(
                "QF-FUZZ-001", "runtime-error-on-valid-input", "high",
                "The function crashes with an engine error for inputs that match its declared types.",
                "A call with arguments that satisfy the parameter types threw an error raised by the JavaScript engine itself, such as reading a property of undefined. Errors the code throws deliberately are not reported.",
                "Handle the case the minimal input exposes, or narrow the parameter type so callers cannot pass it. Add the generated reproduction test to the project's suite.",
            ),
            "non-finite-result" to rule(
                "QF-FUZZ-002", "non-finite-result", "medium",
                "The function returns NaN or Infinity although every numeric input is finite.",
                "The result, or a numeric field of it, was NaN or ±Infinity for finite inputs. Such values propagate silently through later arithmetic and comparisons.",
                "Guard the edge case that produces it (often an empty collection or a zero divisor) and return a defined value or throw a clear error.",
            ),
            "timeout" to rule(
                "QF-FUZZ-003", "call-timeout", "medium",
                "A single call did not return within the per-call time limit.",
                "The call was stopped after the campaign's per-call limit. This usually means an unbounded loop or recursion for that input, but it can also be slow but terminating work.",
                "Check the loop or recursion for that input, or bound the work the function accepts.",
            ),
            "crash" to rule(
                "QF-FUZZ-004", "process-crash", "high",
                "The call exhausted its memory limit or ended the process.",
                "The call ran out of its heap limit or terminated the process that hosted it.",
                "Bound memory growth for the input shown, and avoid exiting the process from library code.",
            ),
        )
    }
}
