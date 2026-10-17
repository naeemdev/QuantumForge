package com.quantumforge.api.testing

import com.quantumforge.api.access.OrgAccess
import com.quantumforge.api.common.BadRequestException
import com.quantumforge.api.common.Ids
import com.quantumforge.api.common.instant
import com.quantumforge.api.common.longOrNull
import com.quantumforge.api.common.sql
import com.quantumforge.api.common.uuid
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.util.UUID

enum class TestStatus { PASSED, FAILED, SKIPPED, ERRORED }

data class TestCaseReport(
    val suite: String?,
    val name: String,
    val status: TestStatus,
    val durationMs: Long?,
    val failureMessage: String?,
    val failureDetail: String?,
)

data class TestRunReport(val framework: String, val durationMs: Long?, val cases: List<TestCaseReport>)

data class TestRunView(
    val id: UUID,
    val attemptId: UUID,
    val framework: String,
    val total: Int,
    val passed: Int,
    val failed: Int,
    val skipped: Int,
    val errored: Int,
    val durationMs: Long?,
    val createdAt: Instant,
    val cases: List<TestCaseView>,
)

data class TestCaseView(
    val ordinal: Int,
    val suite: String?,
    val name: String,
    val status: TestStatus,
    val durationMs: Long?,
    val failureMessage: String?,
    val failureDetail: String?,
)

/** Persists parsed test results reported by workers, after bounding every field. */
@Service
class TestResultStore(private val jdbc: JdbcClient, private val clock: Clock) {
    fun save(tenantId: UUID, jobId: UUID, attemptId: UUID, report: TestRunReport, maxCases: Int): Map<String, Int> {
        if (report.cases.size > maxCases) throw BadRequestException("too_many_results", "At most $maxCases test results can be reported")
        val counts = TestStatus.entries.associateWith { s -> report.cases.count { it.status == s } }
        val runId = Ids.newId(clock)
        jdbc.sql(
            """
            INSERT INTO test_runs (id, tenant_id, job_id, attempt_id, framework, total, passed, failed, skipped, errored, duration_ms, created_at)
            VALUES (:id, :tenant, :job, :attempt, :framework, :total, :passed, :failed, :skipped, :errored, :duration, :now)
            """,
        ).param("id", runId).param("tenant", tenantId).param("job", jobId).param("attempt", attemptId)
            .param("framework", report.framework.take(100)).param("total", report.cases.size)
            .param("passed", counts.getValue(TestStatus.PASSED)).param("failed", counts.getValue(TestStatus.FAILED))
            .param("skipped", counts.getValue(TestStatus.SKIPPED)).param("errored", counts.getValue(TestStatus.ERRORED))
            .param("duration", report.durationMs?.coerceAtLeast(0)).param("now", Instant.now(clock).sql()).update()

        report.cases.chunked(200).forEachIndexed { chunkIndex, chunk ->
            val values = chunk.indices.joinToString(",") { i -> "(:id$i, :tenant, :run, :ord$i, :suite$i, :name$i, :status$i, :dur$i, :msg$i, :detail$i)" }
            var stmt = jdbc.sql(
                "INSERT INTO test_results (id, tenant_id, test_run_id, ordinal, suite, name, status, duration_ms, failure_message, failure_detail) VALUES $values",
            ).param("tenant", tenantId).param("run", runId)
            chunk.forEachIndexed { i, c ->
                stmt = stmt.param("id$i", Ids.newId(clock)).param("ord$i", chunkIndex * 200 + i)
                    .param("suite$i", c.suite?.take(500)).param("name$i", c.name.take(1000).ifBlank { "(unnamed)" })
                    .param("status$i", c.status.name).param("dur$i", c.durationMs?.coerceAtLeast(0))
                    .param("msg$i", c.failureMessage?.take(4000)).param("detail$i", c.failureDetail?.take(16000))
            }
            stmt.update()
        }
        return counts.mapKeys { it.key.name.lowercase() } + ("total" to report.cases.size)
    }

    fun forJob(ctx: OrgAccess, jobId: UUID): List<TestRunView> {
        data class RunRow(val id: UUID, val attemptId: UUID, val framework: String, val total: Int, val passed: Int, val failed: Int, val skipped: Int, val errored: Int, val durationMs: Long?, val createdAt: Instant)
        val runs = jdbc.sql(
            """
            SELECT r.* FROM test_runs r JOIN analysis_jobs j ON j.id = r.job_id AND j.tenant_id = r.tenant_id
            WHERE r.job_id = :job AND r.tenant_id = :tenant AND j.organization_id = :org ORDER BY r.created_at
            """,
        ).param("job", jobId).param("tenant", ctx.tenantId).param("org", ctx.organizationId)
            .query { rs, _ ->
                RunRow(rs.uuid("id"), rs.uuid("attempt_id"), rs.getString("framework"), rs.getInt("total"), rs.getInt("passed"), rs.getInt("failed"),
                    rs.getInt("skipped"), rs.getInt("errored"), rs.longOrNull("duration_ms"), rs.instant("created_at"))
            }.list()
        return runs.map { r ->
            val cases = jdbc.sql("SELECT * FROM test_results WHERE test_run_id = :run AND tenant_id = :tenant ORDER BY ordinal")
                .param("run", r.id).param("tenant", ctx.tenantId)
                .query { rs, _ ->
                    TestCaseView(rs.getInt("ordinal"), rs.getString("suite"), rs.getString("name"), TestStatus.valueOf(rs.getString("status")),
                        rs.longOrNull("duration_ms"), rs.getString("failure_message"), rs.getString("failure_detail"))
                }.list()
            TestRunView(r.id, r.attemptId, r.framework, r.total, r.passed, r.failed, r.skipped, r.errored, r.durationMs, r.createdAt, cases)
        }
    }
}
