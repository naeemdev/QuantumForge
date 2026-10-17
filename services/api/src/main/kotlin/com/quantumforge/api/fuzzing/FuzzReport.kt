package com.quantumforge.api.fuzzing

import com.quantumforge.api.analysis.InvalidReportException
import com.quantumforge.api.common.Json
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

// Kotlin mirror of language-packs/typescript/src/fuzz/types.ts (schema version 1).
// The report comes out of a sandbox that ran untrusted code, so it is bounded by
// validate() before anything is stored.

data class FuzzOracle(val id: String, val description: String)

data class FuzzEngine(val id: String, val version: String, val oracles: List<FuzzOracle>, val limitations: List<String>)

data class FuzzConfigReport(val seed: Long, val iterationsPerTarget: Int, val callTimeoutMs: Int, val corpusEntries: Int = 0)

data class Reproductions(val attempts: Int, val reproduced: Int)

data class FuzzFailure(
    val oracle: String,
    val message: String,
    val errorName: String?,
    val stack: String?,
    val input: List<Any?>,
    val originalInput: List<Any?>,
    val shrinkSteps: Int,
    val reproductions: Reproductions,
    val reproductionTest: String,
    val fingerprint: String,
)

data class FuzzTargetReport(
    val path: String,
    val exportName: String,
    val signature: String,
    val line: Int,
    val iterations: Int,
    val replayed: Int = 0,
    val rejectedInputs: Int,
    val durationMs: Long,
    val failures: List<FuzzFailure>,
)

data class SkippedTargetReport(val path: String, val exportName: String, val line: Int, val reason: String)

data class FuzzStats(val targetsFound: Int, val targetsTested: Int, val totalCalls: Long, val failures: Int, val durationMs: Long)

data class FuzzReport(
    val schemaVersion: Int,
    val engine: FuzzEngine,
    val config: FuzzConfigReport,
    val targets: List<FuzzTargetReport>,
    val skipped: List<SkippedTargetReport>,
    val stats: FuzzStats,
)

object FuzzReports {
    private val mapper: JsonMapper = JsonMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build()

    val ORACLES = setOf("runtime-error", "non-finite-result", "timeout", "crash")
    private val fingerprint = Regex("^[0-9a-f]{64}$")
    private val identifier = Regex("^[A-Za-z_$][A-Za-z0-9_$]{0,199}$")

    const val MAX_TARGETS = 20_000
    const val MAX_FAILURES_PER_TARGET = 20
    const val MAX_INPUT_JSON = 64 * 1024

    fun parse(bytes: ByteArray): FuzzReport {
        val report = try {
            mapper.readValue(bytes, FuzzReport::class.java)
        } catch (e: Exception) {
            throw InvalidReportException("Fuzz report is not valid JSON for schema 1: ${e.message?.take(200)}")
        }
        return validate(report)
    }

    fun validate(r: FuzzReport): FuzzReport {
        fun check(ok: Boolean, message: String) {
            if (!ok) throw InvalidReportException(message)
        }
        val validPath = { p: String -> p.length in 1..1024 && !p.startsWith("/") && p.split('/').none { it == ".." || it.isEmpty() || it == "." } }
        check(r.schemaVersion == 1, "Unsupported fuzz report schema ${r.schemaVersion}")
        check(r.targets.size + r.skipped.size <= MAX_TARGETS, "Fuzz report has too many targets")
        val keys = HashSet<String>()
        for (t in r.targets) {
            check(validPath(t.path) && identifier.matches(t.exportName), "Invalid fuzz target ${t.path.take(80)}#${t.exportName.take(80)}")
            check(keys.add("${t.path}#${t.exportName}"), "Duplicate fuzz target ${t.path.take(80)}#${t.exportName.take(80)}")
            check(t.line >= 1 && t.iterations >= 0 && t.replayed >= 0 && t.rejectedInputs >= 0 && t.durationMs >= 0, "Invalid counters for ${t.exportName}")
            check(t.failures.size <= MAX_FAILURES_PER_TARGET, "Too many failures for ${t.exportName}")
            for (f in t.failures) {
                check(f.oracle in ORACLES, "Unknown oracle ${f.oracle.take(40)}")
                check(fingerprint.matches(f.fingerprint), "Invalid failure fingerprint")
                check(f.reproductions.attempts in 1..20 && f.reproductions.reproduced in 0..f.reproductions.attempts, "Invalid reproduction counts")
                check(f.shrinkSteps >= 0, "Invalid shrink steps")
                check(Json.write(f.input).length <= MAX_INPUT_JSON && Json.write(f.originalInput).length <= MAX_INPUT_JSON, "Failure input is too large")
            }
        }
        for (s in r.skipped) check(validPath(s.path) && s.exportName.length in 1..200 && s.line >= 0, "Invalid skipped target")
        val fingerprints = r.targets.flatMap { t -> t.failures.map { it.fingerprint } }
        check(fingerprints.toSet().size == fingerprints.size, "Duplicate failure fingerprints")
        return r.copy(
            engine = r.engine.copy(id = r.engine.id.take(50), version = r.engine.version.take(50),
                oracles = r.engine.oracles.take(10).map { FuzzOracle(it.id.take(40), it.description.take(500)) },
                limitations = r.engine.limitations.take(20).map { it.take(500) }),
            targets = r.targets.map { t ->
                t.copy(signature = t.signature.take(500), failures = t.failures.map {
                    it.copy(message = it.message.take(1000), errorName = it.errorName?.take(100), stack = it.stack?.take(4000),
                        reproductionTest = it.reproductionTest.take(20_000))
                })
            },
            skipped = r.skipped.map { it.copy(reason = it.reason.take(500)) },
        )
    }
}
