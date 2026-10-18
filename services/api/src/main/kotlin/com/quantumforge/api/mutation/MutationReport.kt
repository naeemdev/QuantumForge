package com.quantumforge.api.mutation

import com.quantumforge.api.analysis.InvalidReportException
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

// Kotlin mirror of language-packs/typescript/src/mutate/types.ts (schema version 1),
// bounded by validate() because it comes out of a sandbox that ran project tests.

data class MutationOperatorInfo(val id: String, val description: String)

data class MutationEngine(val id: String, val version: String, val operators: List<MutationOperatorInfo>, val limitations: List<String>)

data class MutationConfigReport(val seed: Long, val maxMutants: Int, val testPatterns: List<String>, val concurrency: Int)

data class MutationBaseline(val tests: Int, val passed: Int, val failed: Int, val durationMs: Long, val failingTests: List<String>)

data class MutantResultReport(
    val id: String,
    val path: String,
    val line: Int,
    val column: Int,
    val operator: String,
    val original: String,
    val replacement: String,
    val symbolKey: String?,
    val status: String,
    val killedBy: String?,
    val durationMs: Long,
)

data class MutationStats(
    val total: Int,
    val killed: Int,
    val survived: Int,
    val timedOut: Int,
    val errors: Int,
    val score: Double?,
    val durationMs: Long,
)

data class MutationReport(
    val schemaVersion: Int,
    val engine: MutationEngine,
    val config: MutationConfigReport,
    val baseline: MutationBaseline,
    val notRunReason: String?,
    val mutantsFound: Int,
    val mutants: List<MutantResultReport>,
    val stats: MutationStats,
)

object MutationReports {
    private val mapper: JsonMapper = JsonMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build()

    val OPERATORS = setOf("comparison", "equality", "arithmetic", "logical", "negation", "condition", "constant", "call-removal")
    val STATUSES = setOf("KILLED", "SURVIVED", "TIMEOUT", "ERROR")
    private val mutantId = Regex("^[0-9a-f]{16}$")
    const val MAX_MUTANTS = 10_000

    fun parse(bytes: ByteArray): MutationReport {
        val report = try {
            mapper.readValue(bytes, MutationReport::class.java)
        } catch (e: Exception) {
            throw InvalidReportException("Mutation report is not valid JSON for schema 1: ${e.message?.take(200)}")
        }
        return validate(report)
    }

    fun validate(r: MutationReport): MutationReport {
        fun check(ok: Boolean, message: String) {
            if (!ok) throw InvalidReportException(message)
        }
        val validPath = { p: String -> p.length in 1..1024 && !p.startsWith("/") && p.split('/').none { it == ".." || it.isEmpty() || it == "." } }
        check(r.schemaVersion == 1, "Unsupported mutation report schema ${r.schemaVersion}")
        check(r.mutants.size <= MAX_MUTANTS && r.mutantsFound >= r.mutants.size, "Invalid mutant counts")
        check(r.baseline.tests >= 0 && r.baseline.passed + r.baseline.failed == r.baseline.tests, "Invalid baseline counts")
        r.mutants.forEach {
            check(mutantId.matches(it.id) && validPath(it.path) && it.line >= 1 && it.column >= 1, "Invalid mutant ${it.id.take(20)}")
            check(it.operator in OPERATORS && it.status in STATUSES, "Invalid mutant operator or status")
        }
        check(r.mutants.map { it.id }.toSet().size == r.mutants.size, "Duplicate mutant ids")
        val count = { s: String -> r.mutants.count { it.status == s } }
        check(r.stats.total == r.mutants.size && r.stats.killed == count("KILLED") && r.stats.survived == count("SURVIVED") &&
            r.stats.timedOut == count("TIMEOUT") && r.stats.errors == count("ERROR"), "Mutation stats do not match the mutants")
        check(r.stats.score == null || r.stats.score in 0.0..100.0, "Invalid mutation score")
        return r.copy(
            engine = r.engine.copy(id = r.engine.id.take(50), version = r.engine.version.take(50),
                operators = r.engine.operators.take(20).map { MutationOperatorInfo(it.id.take(40), it.description.take(500)) },
                limitations = r.engine.limitations.take(20).map { it.take(500) }),
            config = r.config.copy(testPatterns = r.config.testPatterns.take(20).map { it.take(200) }),
            baseline = r.baseline.copy(failingTests = r.baseline.failingTests.take(500).map { it.take(500) }),
            notRunReason = r.notRunReason?.take(500),
            mutants = r.mutants.map {
                it.copy(original = it.original.take(500), replacement = it.replacement.take(500), symbolKey = it.symbolKey?.take(1000), killedBy = it.killedBy?.take(500))
            },
        )
    }
}
