package com.quantumforge.api.unit

import com.quantumforge.api.analysis.InvalidReportException
import com.quantumforge.api.fuzzing.FuzzIngestion
import com.quantumforge.api.fuzzing.FuzzReports
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FuzzReportTest {
    private val fingerprint = "a".repeat(64)

    private fun report(failure: String = failure(), target: String = """"path": "src/cart.ts", "exportName": "averageUnitPrice"""") = """
        {"schemaVersion": 1,
         "engine": {"id": "typescript-fuzz", "version": "0.1.0", "oracles": [], "limitations": []},
         "config": {"seed": 1, "iterationsPerTarget": 10, "callTimeoutMs": 1000, "corpusEntries": 0},
         "targets": [{$target, "signature": "averageUnitPrice(cart: Cart): number", "line": 50,
                      "iterations": 10, "replayed": 0, "rejectedInputs": 0, "durationMs": 5, "failures": [$failure]}],
         "skipped": [{"path": "src/retry.ts", "exportName": "withRetry", "line": 28, "reason": "generic function"}],
         "stats": {"targetsFound": 2, "targetsTested": 1, "totalCalls": 10, "failures": 1, "durationMs": 9}}
    """.trimIndent().toByteArray()

    private fun failure(oracle: String = "non-finite-result", fp: String = fingerprint, reproduced: Int = 3) = """
        {"oracle": "$oracle", "message": "returned NaN", "errorName": null, "stack": null,
         "input": [{"lines": []}], "originalInput": [{"lines": []}], "shrinkSteps": 0,
         "reproductions": {"attempts": 3, "reproduced": $reproduced}, "reproductionTest": "test", "fingerprint": "$fp"}
    """.trimIndent()

    @Test
    fun `a well-formed report parses`() {
        val r = FuzzReports.parse(report())
        assertEquals("averageUnitPrice", r.targets.single().exportName)
        assertEquals("generic function", r.skipped.single().reason)
    }

    @Test
    fun `reports that break the contract are rejected`() {
        assertFailsWith<InvalidReportException> { FuzzReports.parse(report(failure(oracle = "segfault"))) }
        assertFailsWith<InvalidReportException> { FuzzReports.parse(report(failure(fp = "not-a-fingerprint"))) }
        assertFailsWith<InvalidReportException> { FuzzReports.parse(report(failure(reproduced = 4))) }
        assertFailsWith<InvalidReportException> { FuzzReports.parse(report(target = """"path": "../etc/passwd", "exportName": "x"""")) }
        assertFailsWith<InvalidReportException> { FuzzReports.parse(report(target = """"path": "src/a.ts", "exportName": "not an identifier"""")) }
        assertFailsWith<InvalidReportException> { FuzzReports.parse(report(failure() + "," + failure())) }
        assertFailsWith<InvalidReportException> { FuzzReports.parse("""{"schemaVersion": 2}""".toByteArray()) }
    }

    @Test
    fun `failing calls are shown as JavaScript, including values JSON cannot carry`() {
        assertEquals("averageUnitPrice({ lines: [] })", FuzzIngestion.callLiteral("averageUnitPrice", listOf(mapOf("lines" to emptyList<Any>()))))
        val tagged = FuzzIngestion.callLiteral("f", listOf(mapOf("\$qf" to "NaN"), mapOf("\$qf" to "undefined"), 2.0, 1.5, "a\"b", mapOf("my-key" to null)))
        assertEquals("""f(NaN, undefined, 2, 1.5, "a\"b", { "my-key": null })""", tagged)
        assertTrue(FuzzIngestion.RULES.keys.containsAll(FuzzReports.ORACLES))
    }
}
