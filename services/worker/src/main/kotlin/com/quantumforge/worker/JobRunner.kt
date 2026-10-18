package com.quantumforge.worker

import com.quantumforge.worker.sandbox.ExecutionResult
import com.quantumforge.worker.sandbox.ExecutionSpec
import com.quantumforge.worker.sandbox.Sandbox
import org.slf4j.LoggerFactory
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Executes one leased job: download and verify the snapshot, run the preset in
 * the sandbox while heartbeating, then upload evidence and report the outcome.
 * Every outcome is reported except a lost lease, where the results belong to
 * nobody and are discarded.
 */
class JobRunner(
    private val client: ControlPlaneClient,
    private val sandbox: Sandbox,
    private val config: WorkerConfig,
    private val toolVersions: Map<String, String?>,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val json = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

    fun run(job: LeasedJob): CompletionResult? {
        val workDir = Files.createDirectories(config.workRoot).let { Files.createTempDirectory(it, "attempt-${job.attemptId}-") }
        val cancel = AtomicBoolean(false)
        val leaseLost = AtomicBoolean(false)
        val heartbeats = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "heartbeat-${job.attemptId}").apply { isDaemon = true } }
        heartbeats.scheduleAtFixedRate({
            try {
                if (client.heartbeat(job, null).cancelRequested) cancel.set(true)
            } catch (e: LeaseLostException) {
                log.warn("Lease lost for attempt {}: {}", job.attemptId, e.message)
                leaseLost.set(true)
                cancel.set(true)
            } catch (e: Exception) {
                log.warn("Heartbeat failed for attempt {}: {}", job.attemptId, e.message)
            }
        }, job.heartbeatIntervalSeconds, job.heartbeatIntervalSeconds, TimeUnit.SECONDS)

        try {
            return execute(job, workDir, cancel, leaseLost)
        } catch (e: LeaseLostException) {
            log.warn("Abandoning attempt {}: {}", job.attemptId, e.message)
            return null
        } finally {
            heartbeats.shutdownNow()
            workDir.toFile().deleteRecursively()
        }
    }

    private fun execute(job: LeasedJob, workDir: Path, cancel: AtomicBoolean, leaseLost: AtomicBoolean): CompletionResult? {
        val src = workDir.resolve("src")
        val out = Files.createDirectories(workDir.resolve("out"))
        val logs = Files.createDirectories(workDir.resolve("logs"))

        val prepared = try {
            val archive = workDir.resolve("snapshot.tar.gz")
            client.downloadSnapshot(job, archive, config.maxSnapshotBytes)
            SnapshotExtractor.verifyDigest(archive, job.snapshotDigest)
            SnapshotExtractor.extract(archive, src)
        } catch (e: LeaseLostException) {
            throw e
        } catch (e: Exception) {
            log.error("Failed to prepare snapshot for attempt {}", job.attemptId, e)
            return client.complete(job, CompletionReport(AttemptOutcome.INFRA_ERROR, null, null, "Snapshot preparation failed: ${e.message}", null))
        }
        if (sandbox.mode.startsWith("container")) {
            // The job runs as an unprivileged uid inside the container.
            Files.setPosixFilePermissions(out, PosixFilePermissions.fromString("rwxrwxrwx"))
        }

        val preset = job.config.preset
        val analysis = job.jobType == "STATIC_ANALYSIS"
        val fuzz = job.jobType == "FUZZ_CAMPAIGN"
        val mutation = job.jobType == "MUTATION_TEST"
        val supported = when (job.jobType) {
            "TEST_EXECUTION" -> preset == "NODE_TEST"
            "STATIC_ANALYSIS" -> preset == "TS_ANALYSIS" && config.tsAnalyzerDir != null
            "FUZZ_CAMPAIGN" -> preset == "TS_FUZZ" && config.tsAnalyzerDir != null
            "MUTATION_TEST" -> preset == "TS_MUTATION" && config.tsAnalyzerDir != null
            else -> false
        }
        if (!supported) {
            return client.complete(job, CompletionReport(AttemptOutcome.INFRA_ERROR, null, null, "Preset $preset is not available on this worker", null))
        }
        val timeout = Duration.ofSeconds(job.timeoutSeconds.toLong())
        val settings = job.config.fuzz ?: FuzzSettings()
        if (fuzz) Files.writeString(out.resolve("corpus.json"), json.writeValueAsString(settings.corpus))
        val mutationSettings = job.config.mutation ?: MutationSettings()
        // One test suite per CPU the job was granted.
        val mutationConcurrency = (job.config.cpuMillis / 1000).coerceIn(1, 8)
        // Leave room to write the report before the sandbox's hard limit.
        val budgetMs = (job.timeoutSeconds * 1000L * 3 / 4).coerceAtLeast(1000)
        val node = if (sandbox.mode == "dev-process") config.nodeExecutable else "node"
        val spec = ExecutionSpec(
            sourceDir = src,
            outputDir = out,
            logDir = logs,
            command = { p ->
                if (analysis) {
                    listOf(node, "--experimental-strip-types", "${p.tools.getValue(TS_ANALYZER)}/src/cli.ts", "--root", p.source, "--out", "${p.output}/analysis.json")
                } else if (fuzz) {
                    listOf(
                        node, "--experimental-strip-types", "${p.tools.getValue(TS_ANALYZER)}/src/fuzz/cli.ts",
                        "--root", p.source, "--out", "${p.output}/fuzz.json", "--corpus", "${p.output}/corpus.json",
                        "--seed", settings.seed.toString(), "--iterations", settings.iterationsPerTarget.toString(),
                        "--call-timeout-ms", settings.callTimeoutMs.toString(), "--budget-ms", budgetMs.toString(),
                    )
                } else if (mutation) {
                    listOf(
                        node, "--experimental-strip-types", "${p.tools.getValue(TS_ANALYZER)}/src/mutate/cli.ts",
                        "--root", p.source, "--out", "${p.output}/mutation.json", "--work", "${p.output}/work",
                        "--seed", mutationSettings.seed.toString(), "--max-mutants", mutationSettings.maxMutants.toString(),
                        "--concurrency", mutationConcurrency.toString(), "--budget-ms", budgetMs.toString(),
                    ) + job.testPatterns.flatMap { listOf("--tests", it) }
                } else {
                    listOf(
                        node,
                        "--experimental-strip-types",
                        "--test",
                        "--test-reporter=spec", "--test-reporter-destination=stdout",
                        "--test-reporter=junit", "--test-reporter-destination=${p.output}/junit.xml",
                    ) + job.testPatterns
                }
            },
            environment = mapOf(
                "CI" to "true",
                "NODE_ENV" to "test",
                "NO_COLOR" to "1",
                "NODE_OPTIONS" to "--max-old-space-size=${(job.config.memoryMb * 3 / 4).coerceAtLeast(32)}",
            ),
            timeout = timeout,
            memoryMb = job.config.memoryMb,
            cpuMillis = job.config.cpuMillis,
            maxOutputBytes = job.config.maxOutputBytes,
            tools = if (analysis || fuzz || mutation) mapOf(TS_ANALYZER to config.tsAnalyzerDir!!) else emptyMap(),
        )

        val environment = linkedMapOf<String, Any?>(
            "worker" to toolVersions["workerVersion"],
            "isolation" to sandbox.describe(),
            "runtime" to mapOf("node" to toolVersions["node"], "preset" to preset, "jobType" to job.jobType),
            "os" to "${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})",
            "limits" to mapOf(
                "timeoutSeconds" to job.timeoutSeconds,
                "memoryMb" to job.config.memoryMb,
                "cpuMillis" to job.config.cpuMillis,
                "maxOutputBytes" to job.config.maxOutputBytes,
            ),
            "snapshot" to mapOf("sha256" to job.snapshotDigest, "files" to prepared, "verified" to true),
            "testPatterns" to if (analysis || fuzz) null else job.testPatterns,
            "mutation" to if (mutation) mapOf("seed" to mutationSettings.seed, "maxMutants" to mutationSettings.maxMutants,
                "concurrency" to mutationConcurrency, "budgetMs" to budgetMs) else null,
            "fuzz" to if (fuzz) mapOf("seed" to settings.seed, "iterationsPerTarget" to settings.iterationsPerTarget,
                "callTimeoutMs" to settings.callTimeoutMs, "corpusEntries" to settings.corpus.size, "budgetMs" to budgetMs) else null,
            "startedAt" to Instant.now().toString(),
        )
        client.start(job, environment)

        val result: ExecutionResult = try {
            sandbox.run(spec) { cancel.get() }
        } catch (e: Exception) {
            if (leaseLost.get()) throw LeaseLostException("Lease lost during execution")
            log.error("Sandbox failed to run attempt {}", job.attemptId, e)
            return client.complete(job, CompletionReport(AttemptOutcome.INFRA_ERROR, null, null, "Sandbox failure: ${e.message}", null))
        }
        if (leaseLost.get()) throw LeaseLostException("Lease lost during execution")
        result.sandboxError?.let { error ->
            log.error("Sandbox failed for attempt {}: {}", job.attemptId, error)
            client.uploadArtifact(job, "stderr.log", "STDERR", "text/plain", result.stderr.truncated, result.stderr.file)
            return client.complete(job, CompletionReport(AttemptOutcome.INFRA_ERROR, result.exitCode, result.durationMs, error, null))
        }

        // Evidence first, verdict second: a crash between the two leaves the attempt
        // open, the lease expires and the job is retried rather than misreported.
        client.uploadArtifact(job, "stdout.log", "STDOUT", "text/plain", result.stdout.truncated, result.stdout.file)
        client.uploadArtifact(job, "stderr.log", "STDERR", "text/plain", result.stderr.truncated, result.stderr.file)
        val envFile = logs.resolve("environment.json")
        Files.writeString(envFile, json.writerWithDefaultPrettyPrinter().writeValueAsString(environment + mapOf("command" to result.command, "exitCode" to result.exitCode, "durationMs" to result.durationMs)))
        client.uploadArtifact(job, "environment.json", "ENVIRONMENT", "application/json", false, envFile)

        if (analysis) return completeReport(job, result, out.resolve("analysis.json"), "ANALYSIS_REPORT", "analyzer")
        if (fuzz) return completeReport(job, result, out.resolve("fuzz.json"), "FUZZ_REPORT", "fuzzer")
        if (mutation) return completeReport(job, result, out.resolve("mutation.json"), "MUTATION_REPORT", "mutation runner")

        val junit = out.resolve("junit.xml")
        var testRun: TestRunReport? = null
        var reportProblem: String? = null
        if (Files.isRegularFile(junit)) {
            val size = Files.size(junit)
            if (size <= MAX_JUNIT_BYTES) {
                client.uploadArtifact(job, "junit.xml", "JUNIT_XML", "application/xml", false, junit)
                testRun = runCatching { JunitParser.parse(junit, "node:test") }
                    .onFailure { reportProblem = "JUnit report could not be parsed: ${it.message}" }
                    .getOrNull()
            } else {
                reportProblem = "JUnit report exceeds $MAX_JUNIT_BYTES bytes and was not processed"
            }
        } else if (!result.timedOut && !result.cancelled) {
            reportProblem = "The test run produced no JUnit report"
        }

        val failedTests = testRun?.cases?.count { it.status == TestStatus.FAILED || it.status == TestStatus.ERRORED } ?: 0
        val outcome = when {
            result.cancelled -> AttemptOutcome.CANCELLED
            result.timedOut -> AttemptOutcome.TIMED_OUT
            result.exitCode == 0 && failedTests == 0 && testRun != null -> AttemptOutcome.SUCCEEDED
            else -> AttemptOutcome.FAILED
        }
        val message = when (outcome) {
            AttemptOutcome.SUCCEEDED -> null
            AttemptOutcome.FAILED -> listOfNotNull(
                if (failedTests > 0) "$failedTests of ${testRun?.cases?.size} tests failed" else null,
                result.exitCode?.let { "exit code $it" },
                reportProblem,
            ).joinToString("; ")
            AttemptOutcome.TIMED_OUT -> "Exceeded the ${job.timeoutSeconds}s time limit"
            AttemptOutcome.CANCELLED -> "Stopped on cancellation request"
            AttemptOutcome.INFRA_ERROR -> reportProblem
        }
        return client.complete(job, CompletionReport(outcome, result.exitCode, result.durationMs, message, testRun))
    }

    /**
     * An analysis or fuzz campaign succeeds when the tool exits cleanly and wrote a
     * report; findings and failures it reports are data, not job failures.
     */
    private fun completeReport(job: LeasedJob, result: ExecutionResult, report: Path, kind: String, tool: String): CompletionResult {
        val outcome = when {
            result.cancelled -> AttemptOutcome.CANCELLED
            result.timedOut -> AttemptOutcome.TIMED_OUT
            result.exitCode == 0 && Files.isRegularFile(report) && Files.size(report) <= MAX_REPORT_BYTES -> AttemptOutcome.SUCCEEDED
            else -> AttemptOutcome.FAILED
        }
        if (outcome == AttemptOutcome.SUCCEEDED) {
            client.uploadArtifact(job, report.fileName.toString(), kind, "application/json", false, report)
        }
        val message = when (outcome) {
            AttemptOutcome.SUCCEEDED -> null
            AttemptOutcome.TIMED_OUT -> "Exceeded the ${job.timeoutSeconds}s time limit"
            AttemptOutcome.CANCELLED -> "Stopped on cancellation request"
            else -> when {
                result.exitCode != 0 -> "The $tool exited with code ${result.exitCode}"
                !Files.isRegularFile(report) -> "The $tool produced no report"
                else -> "The $tool report exceeds $MAX_REPORT_BYTES bytes"
            }
        }
        return client.complete(job, CompletionReport(outcome, result.exitCode, result.durationMs, message, null))
    }

    companion object {
        const val MAX_JUNIT_BYTES = 4L * 1024 * 1024
        const val MAX_REPORT_BYTES = 32L * 1024 * 1024
        const val TS_ANALYZER = "ts-analyzer"
    }
}
