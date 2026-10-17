package com.quantumforge.worker.sandbox

import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Runs each job in a fresh container: non-root user, read-only root filesystem,
 * read-only source mount, no network, all capabilities dropped,
 * no-new-privileges, and pids/memory/CPU limits. With [gvisor] the container
 * uses the runsc runtime, which puts a user-space kernel between the job and the
 * host kernel.
 *
 * Plain containers share the host kernel and are not, by themselves, a sufficient
 * boundary for hostile code; production deployments should use gVisor or
 * microVM-backed runtimes. The worker host must not be shared with trusted
 * services, and the worker never mounts the Docker socket into a job.
 */
class ContainerSandbox(
    private val runtime: String,
    private val image: String,
    private val gvisor: Boolean,
) : Sandbox {
    private val log = LoggerFactory.getLogger(javaClass)
    override val mode = if (gvisor) "container-gvisor" else "container"

    override fun describe(): Map<String, Any?> = mapOf(
        "mode" to mode,
        "hostileCodeBoundary" to gvisor,
        "runtime" to if (gvisor) "$runtime (runsc)" else runtime,
        "image" to image,
        "networkIsolation" to "none (--network none)",
        "filesystemIsolation" to "read-only root, read-only source, writable /work/out and 64 MiB /tmp",
        "user" to "65534:65534",
    )

    override fun run(spec: ExecutionSpec, shouldStop: () -> Boolean): ExecutionResult {
        val paths = SandboxPaths("/work/src", "/work/out", spec.tools.keys.associateWith { "/opt/qf/tools/$it" })
        val inner = spec.command(paths)
        val name = "qf-job-" + UUID.randomUUID().toString().take(12)
        val cpus = "%.2f".format(spec.cpuMillis / 1000.0)
        val args = buildList {
            addAll(listOf(runtime, "run", "--rm", "--name", name))
            if (gvisor) add("--runtime=runsc")
            addAll(listOf("--network", "none", "--read-only", "--cap-drop", "ALL", "--security-opt", "no-new-privileges"))
            addAll(listOf("--pids-limit", "256", "--memory", "${spec.memoryMb}m", "--memory-swap", "${spec.memoryMb}m", "--cpus", cpus))
            addAll(listOf("--user", "65534:65534", "--tmpfs", "/tmp:rw,noexec,nosuid,size=64m"))
            addAll(listOf("-v", "${spec.sourceDir}:/work/src:ro", "-v", "${spec.outputDir}:/work/out:rw", "-w", "/work/src"))
            spec.tools.forEach { (name, hostPath) -> addAll(listOf("-v", "$hostPath:/opt/qf/tools/$name:ro")) }
            addAll(listOf("-e", "HOME=/tmp", "-e", "TMPDIR=/tmp", "-e", "TZ=UTC", "-e", "LANG=C.UTF-8"))
            spec.environment.forEach { (k, v) -> addAll(listOf("-e", "$k=$v")) }
            add(image)
            addAll(inner)
        }
        val started = System.nanoTime()
        val process = ProcessBuilder(args).start()
        process.outputStream.close()
        val stdout = BoundedCapture(process.inputStream, spec.logDir.resolve("stdout.log"), spec.maxOutputBytes).start()
        val stderr = BoundedCapture(process.errorStream, spec.logDir.resolve("stderr.log"), spec.maxOutputBytes).start()
        val deadline = started + spec.timeout.toNanos()
        var timedOut = false
        var cancelled = false
        while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
            if (System.nanoTime() > deadline) { timedOut = true; break }
            if (shouldStop()) { cancelled = true; break }
        }
        if (timedOut || cancelled) {
            log.info("Killing container {} (timedOut={}, cancelled={})", name, timedOut, cancelled)
            runCatching { ProcessBuilder(runtime, "kill", name).start().waitFor(15, TimeUnit.SECONDS) }
            killTree(process)
            process.waitFor(10, TimeUnit.SECONDS)
        }
        return ExecutionResult(
            exitCode = if (process.isAlive) null else process.exitValue(),
            timedOut = timedOut,
            cancelled = cancelled,
            durationMs = (System.nanoTime() - started) / 1_000_000,
            stdout = stdout.finish(),
            stderr = stderr.finish(),
            command = inner,
        )
    }
}
