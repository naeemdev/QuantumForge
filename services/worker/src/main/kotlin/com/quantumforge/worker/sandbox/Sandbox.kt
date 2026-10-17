package com.quantumforge.worker.sandbox

import java.nio.file.Path
import java.time.Duration

/** Where the job's files live as seen from inside the sandbox. Tool directories are keyed by name. */
data class SandboxPaths(val source: String, val output: String, val tools: Map<String, String> = emptyMap())

data class ExecutionSpec(
    val sourceDir: Path,
    val outputDir: Path,
    val logDir: Path,
    /** Builds the command once the sandbox has decided its internal paths. */
    val command: (SandboxPaths) -> List<String>,
    val environment: Map<String, String>,
    val timeout: Duration,
    val memoryMb: Int,
    val cpuMillis: Int,
    val maxOutputBytes: Long,
    /** Trusted, read-only tool directories (name → host path) the command needs, such as an analyzer. */
    val tools: Map<String, Path> = emptyMap(),
)

data class ExecutionResult(
    val exitCode: Int?,
    val timedOut: Boolean,
    val cancelled: Boolean,
    val durationMs: Long,
    val stdout: CapturedOutput,
    val stderr: CapturedOutput,
    val command: List<String>,
    /** Set when the sandbox itself failed (e.g. the container runtime could not start the job); not a test failure. */
    val sandboxError: String? = null,
)

/**
 * An isolation boundary for running untrusted code. Implementations must be
 * honest in [describe] about what they do and do not isolate; that description
 * is stored with every job as part of its evidence.
 */
interface Sandbox {
    val mode: String

    fun describe(): Map<String, Any?>

    fun run(spec: ExecutionSpec, shouldStop: () -> Boolean): ExecutionResult
}

internal fun killTree(process: Process) {
    process.descendants().forEach { it.destroyForcibly() }
    process.destroyForcibly()
}
