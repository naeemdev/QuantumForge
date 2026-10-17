package com.quantumforge.worker.sandbox

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Runs jobs as child processes of the worker. FOR DEVELOPMENT AND CI ONLY.
 *
 * What it does: scrubbed environment, private HOME and TMPDIR, wall-clock
 * timeout with process-tree kill, bounded output capture, `prlimit` caps on core
 * dumps, file size and open files, and — when the kernel allows unprivileged user
 * namespaces — a private network namespace with no interfaces except loopback.
 *
 * What it does NOT do: filesystem isolation, PID isolation, memory or CPU limits
 * beyond V8's heap flag, or any defence against a deliberately hostile program.
 * The control plane refuses to dispatch to this mode unless explicitly allowed.
 */
class DevProcessSandbox : Sandbox {
    private val log = LoggerFactory.getLogger(javaClass)
    override val mode = "dev-process"

    private val prlimit: Boolean = probe(listOf("prlimit", "--core=0", "--", "true"))
    private val netns: Boolean = probe(listOf("unshare", "--user", "--map-root-user", "--net", "--", "true"))

    override fun describe(): Map<String, Any?> = mapOf(
        "mode" to mode,
        "hostileCodeBoundary" to false,
        "networkIsolation" to if (netns) "private network namespace (loopback only)" else "none: job shares the worker's network",
        "resourceLimits" to if (prlimit) "prlimit core=0 fsize=256MiB nofile=1024; V8 heap cap" else "V8 heap cap only",
        "filesystemIsolation" to "none (separate working directory only)",
        "warning" to "Development isolation. Not a security boundary for untrusted code.",
    )

    override fun run(spec: ExecutionSpec, shouldStop: () -> Boolean): ExecutionResult {
        val paths = SandboxPaths(spec.sourceDir.toString(), spec.outputDir.toString())
        val inner = spec.command(paths)
        val wrapped = buildList {
            if (netns) addAll(listOf("unshare", "--user", "--map-root-user", "--net", "--"))
            if (prlimit) addAll(listOf("prlimit", "--core=0", "--fsize=268435456", "--nofile=1024", "--"))
            addAll(inner)
        }
        val home = Files.createDirectories(spec.logDir.resolve("home"))
        val tmp = Files.createDirectories(spec.logDir.resolve("tmp"))
        val builder = ProcessBuilder(wrapped).directory(spec.sourceDir.toFile())
        builder.environment().apply {
            clear()
            put("PATH", System.getenv("PATH") ?: "/usr/local/bin:/usr/bin:/bin")
            put("HOME", home.toString())
            put("TMPDIR", tmp.toString())
            put("LANG", "C.UTF-8")
            put("TZ", "UTC")
            putAll(spec.environment)
        }
        val started = System.nanoTime()
        val process = builder.start()
        process.outputStream.close()
        val stdout = BoundedCapture(process.inputStream, spec.logDir.resolve("stdout.log"), spec.maxOutputBytes).start()
        val stderr = BoundedCapture(process.errorStream, spec.logDir.resolve("stderr.log"), spec.maxOutputBytes).start()

        val deadline = started + spec.timeout.toNanos()
        var timedOut = false
        var cancelled = false
        while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
            if (System.nanoTime() > deadline) {
                timedOut = true
                break
            }
            if (shouldStop()) {
                cancelled = true
                break
            }
        }
        if (timedOut || cancelled) {
            log.info("Stopping job process (timedOut={}, cancelled={})", timedOut, cancelled)
            killTree(process)
            process.waitFor(10, TimeUnit.SECONDS)
        }
        val durationMs = (System.nanoTime() - started) / 1_000_000
        return ExecutionResult(
            exitCode = if (process.isAlive) null else process.exitValue(),
            timedOut = timedOut,
            cancelled = cancelled,
            durationMs = durationMs,
            stdout = stdout.finish(),
            stderr = stderr.finish(),
            command = inner,
        )
    }

    private fun probe(command: List<String>): Boolean = try {
        val p = ProcessBuilder(command).redirectErrorStream(true).start()
        p.inputStream.readAllBytes()
        p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0
    } catch (e: Exception) {
        false
    }
}
