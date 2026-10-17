package com.quantumforge.api.analysis

import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

// Kotlin mirror of packages/language-sdk (schema version 1). The report comes out
// of a sandbox, so it is parsed into these types and then bounded by validate().

data class SourceRange(val startLine: Int, val startColumn: Int, val endLine: Int, val endColumn: Int)

data class AdapterInfo(
    val id: String,
    val version: String,
    val languages: List<String>,
    val capabilities: Map<String, String>,
    val toolchain: String,
    val limitations: List<String>,
)

data class FileDiagnostic(val range: SourceRange, val message: String, val category: String)

data class FileReport(
    val path: String,
    val language: String,
    val parsed: Boolean,
    val lineCount: Int,
    val diagnostics: List<FileDiagnostic>,
    val skippedReason: String? = null,
)

data class SymbolReport(
    val key: String,
    val kind: String,
    val name: String,
    val container: String?,
    val path: String,
    val range: SourceRange,
    val exported: Boolean,
    val complexity: Int?,
    val signature: String?,
)

data class ImportReport(val from: String, val specifier: String, val to: String?, val external: Boolean, val packageName: String?, val line: Int)

data class CallReport(val from: String, val to: String, val path: String, val line: Int)

data class RuleReport(
    val id: String,
    val version: Int,
    val name: String,
    val summary: String,
    val description: String,
    val remediation: String,
    val category: String,
    val severity: String,
    val confidence: String,
    val languages: List<String>,
)

data class FindingReport(
    val ruleId: String,
    val ruleVersion: Int,
    val severity: String,
    val confidence: String,
    val message: String,
    val path: String,
    val range: SourceRange,
    val snippet: String,
    val symbolKey: String?,
    val fingerprint: String,
)

data class ReportStats(val filesSeen: Int, val filesAnalysed: Int, val durationMs: Long, val truncated: Boolean)

data class AnalysisReport(
    val schemaVersion: Int,
    val adapter: AdapterInfo,
    val files: List<FileReport>,
    val symbols: List<SymbolReport>,
    val imports: List<ImportReport>,
    val calls: List<CallReport>,
    val rules: List<RuleReport>,
    val findings: List<FindingReport>,
    val stats: ReportStats,
)

class InvalidReportException(message: String) : RuntimeException(message)

object AnalysisReports {
    private val mapper: JsonMapper = JsonMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build()

    private val severities = setOf("critical", "high", "medium", "low", "info")
    private val confidences = setOf("high", "medium", "low")
    private val kinds = setOf("function", "class", "method", "interface", "type", "enum", "variable")
    private val ruleId = Regex("^[A-Z][A-Z0-9-]{2,40}$")
    private val fingerprint = Regex("^[0-9a-f]{64}$")

    const val MAX_FILES = 50_000
    const val MAX_SYMBOLS = 500_000
    const val MAX_EDGES = 1_000_000
    const val MAX_FINDINGS = 20_000

    fun parse(bytes: ByteArray): AnalysisReport {
        val report = try {
            mapper.readValue(bytes, AnalysisReport::class.java)
        } catch (e: Exception) {
            throw InvalidReportException("Analysis report is not valid JSON for schema 1: ${e.message?.take(200)}")
        }
        return validate(report)
    }

    /** Rejects reports that break the contract and truncates free text to safe lengths. */
    fun validate(r: AnalysisReport): AnalysisReport {
        fun check(ok: Boolean, message: String) {
            if (!ok) throw InvalidReportException(message)
        }
        check(r.schemaVersion == 1, "Unsupported report schema ${r.schemaVersion}")
        check(r.files.size <= MAX_FILES && r.symbols.size <= MAX_SYMBOLS, "Report has too many files or symbols")
        check(r.imports.size <= MAX_EDGES && r.calls.size <= MAX_EDGES, "Report has too many edges")
        check(r.findings.size <= MAX_FINDINGS, "Report has too many findings")
        val paths = r.files.map { it.path }.toSet()
        val validPath = { p: String -> p.length in 1..1024 && !p.startsWith("/") && p.split('/').none { it == ".." || it.isEmpty() } }
        check(paths.all(validPath), "Report contains an invalid file path")
        val rules = r.rules.associateBy { it.id }
        r.rules.forEach {
            check(ruleId.matches(it.id) && it.version >= 1, "Invalid rule ${it.id}")
            check(it.severity in severities && it.confidence in confidences, "Invalid rule severity or confidence for ${it.id}")
        }
        r.symbols.forEach { check(it.kind in kinds && it.path in paths && it.key.length <= 2048, "Invalid symbol ${it.key.take(80)}") }
        r.findings.forEach {
            val rule = rules[it.ruleId]
            check(rule != null && rule.version == it.ruleVersion, "Finding references an unknown rule ${it.ruleId}")
            check(fingerprint.matches(it.fingerprint), "Invalid finding fingerprint")
            check(it.path in paths, "Finding references a file that was not analysed")
            check(it.severity in severities && it.confidence in confidences, "Invalid finding severity")
        }
        check(r.findings.map { it.fingerprint }.toSet().size == r.findings.size, "Duplicate finding fingerprints")
        return r.copy(
            adapter = r.adapter.copy(id = r.adapter.id.take(50), version = r.adapter.version.take(50), toolchain = r.adapter.toolchain.take(100),
                limitations = r.adapter.limitations.take(20).map { it.take(500) }),
            findings = r.findings.map { it.copy(message = it.message.take(1000), snippet = it.snippet.take(500)) },
            symbols = r.symbols.map { it.copy(name = it.name.take(300), container = it.container?.take(300), signature = it.signature?.take(500)) },
        )
    }
}
