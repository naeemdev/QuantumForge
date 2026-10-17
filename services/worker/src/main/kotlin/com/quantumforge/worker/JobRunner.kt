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
        if (preset != "NODE_TEST") {
            return client.complete(job, CompletionReport(AttemptOutcome.INFRA_ERROR, null, null, "Preset $preset is not supported by this worker", null))
        }
        val timeout = Duration.ofSeconds(job.timeoutSeconds.toLong())
        val spec = ExecutionSpec(
            sourceDir = src,
            outputDir = out,
            logDir = logs,
            command = { p ->
                listOf(
                    if (sandbox.mode == "dev-process") config.nodeExecutable else "node",
                    "--experimental-strip-types",
                    "--test",
                    "--test-reporter=spec", "--test-reporter-destination=stdout",
                    "--test-reporter=junit", "--test-reporter-destination=${p.output}/junit.xml",
                ) + job.testPatterns
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
        )

        val environment = linkedMapOf<String, Any?>(
            "worker" to toolVersions["workerVersion"],
            "isolation" to sandbox.describe(),
            "runtime" to mapOf("node" to toolVersions["node"], "preset" to preset),
            "os" to "${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})",
            "limits" to mapOf(
                "timeoutSeconds" to job.timeoutSeconds,
                "memoryMb" to job.config.memoryMb,
                "cpuMillis" to job.config.cpuMillis,
                "maxOutputBytes" to job.config.maxOutputBytes,
            ),
            "snapshot" to mapOf("sha256" to job.snapshotDigest, "files" to prepared, "verified" to true),
            "testPatterns" to job.testPatterns,
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

        // Evidence first, verdict second: a crash between the two leaves the attempt
        // open, the lease expires and the job is retried rather than misreported.
        client.uploadArtifact(job, "stdout.log", "STDOUT", "text/plain", result.stdout.truncated, result.stdout.file)
        client.uploadArtifact(job, "stderr.log", "STDERR", "text/plain", result.stderr.truncated, result.stderr.file)
        val envFile = logs.resolve("environment.json")
        Files.writeString(envFile, json.writerWithDefaultPrettyPrinter().writeValueAsString(environment + mapOf("command" to result.command, "exitCode" to result.exitCode, "durationMs" to result.durationMs)))
        client.uploadArtifact(job, "environment.json", "ENVIRONMENT", "application/json", false, envFile)

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

    companion object {
        const val MAX_JUNIT_BYTES = 4L * 1024 * 1024
    }
}
