package com.quantumforge.api.unit

import com.quantumforge.api.analysis.InvalidReportException
import com.quantumforge.api.mutation.MutationReports
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MutationReportTest {
    private fun mutant(id: String = "0123456789abcdef", status: String = "SURVIVED", operator: String = "comparison", path: String = "src/a.ts") = """
        {"id": "$id", "path": "$path", "line": 3, "column": 9, "operator": "$operator", "original": "<", "replacement": "<=",
         "symbolKey": "src/a.ts#clamp", "status": "$status", "killedBy": null, "durationMs": 120}
    """.trimIndent()

    private fun report(mutants: List<String> = listOf(mutant()), survived: Int = 1, baseline: String = """{"tests": 2, "passed": 2, "failed": 0, "durationMs": 300, "failingTests": []}""") = """
        {"schemaVersion": 1,
         "engine": {"id": "typescript-mutation", "version": "0.1.0", "operators": [], "limitations": []},
         "config": {"seed": 1, "maxMutants": 500, "testPatterns": ["**/*.test.ts"], "concurrency": 2},
         "baseline": $baseline, "notRunReason": null, "mutantsFound": ${mutants.size},
         "mutants": [${mutants.joinToString(",")}],
         "stats": {"total": ${mutants.size}, "killed": 0, "survived": $survived, "timedOut": 0, "errors": 0, "score": 0.0, "durationMs": 400}}
    """.trimIndent().toByteArray()

    @Test
    fun `a well-formed report parses`() {
        val r = MutationReports.parse(report())
        assertEquals("<=", r.mutants.single().replacement)
        assertEquals(2, r.baseline.passed)
    }

    @Test
    fun `reports that break the contract are rejected`() {
        assertFailsWith<InvalidReportException> { MutationReports.parse(report(listOf(mutant(status = "MAYBE")))) }
        assertFailsWith<InvalidReportException> { MutationReports.parse(report(listOf(mutant(operator = "rewrite-everything")))) }
        assertFailsWith<InvalidReportException> { MutationReports.parse(report(listOf(mutant(id = "x")))) }
        assertFailsWith<InvalidReportException> { MutationReports.parse(report(listOf(mutant(path = "/etc/passwd")))) }
        assertFailsWith<InvalidReportException> { MutationReports.parse(report(listOf(mutant(), mutant()), survived = 2)) }
        assertFailsWith<InvalidReportException> { MutationReports.parse(report(survived = 0)) }
        assertFailsWith<InvalidReportException> { MutationReports.parse(report(baseline = """{"tests": 5, "passed": 2, "failed": 0, "durationMs": 1, "failingTests": []}""")) }
    }
}
