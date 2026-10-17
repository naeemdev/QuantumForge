package com.quantumforge.worker

import com.quantumforge.worker.sandbox.ContainerSandbox
import com.quantumforge.worker.sandbox.DevProcessSandbox
import com.quantumforge.worker.sandbox.Sandbox
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Long-running worker process: lease, run, report, repeat. Polls with backoff
 * while the queue is empty and finishes the current job on SIGTERM.
 */
class Worker(private val config: WorkerConfig, private val sandbox: Sandbox = sandboxFor(config)) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val client = ControlPlaneClient(config.controlPlaneUrl, config.workerToken)
    private val stopping = AtomicBoolean(false)
    private val runner = JobRunner(client, sandbox, config, detectToolVersions(config))

    fun stop() = stopping.set(true)

    /** Leases and runs at most one job. Returns false when the queue was empty. */
    fun runOnce(): Boolean {
        val job = client.lease() ?: return false
        log.info("Leased job {} attempt {} ({})", job.jobId, job.attemptNumber, job.config.preset)
        val result = runner.run(job)
        log.info("Job {} attempt {} finished: {}", job.jobId, job.attemptNumber, result?.jobState ?: "lease lost")
        return true
    }

    fun runForever() {
        log.info("Worker started: isolation={} controlPlane={}", sandbox.mode, config.controlPlaneUrl)
        var idle = config.pollInterval
        while (!stopping.get()) {
            val worked = try {
                runOnce()
            } catch (e: ControlPlaneException) {
                if (e.code == "isolation_not_permitted") {
                    log.error("The control plane refuses this worker's isolation mode ({}). Exiting.", sandbox.mode)
                    return
                }
                log.warn("Control plane error: {}", e.message)
                false
            } catch (e: Exception) {
                log.warn("Worker loop error: {}", e.message)
                false
            }
            idle = if (worked) config.pollInterval else minOf(idle.multipliedBy(2), config.maxPollInterval)
            if (!worked && !stopping.get()) TimeUnit.MILLISECONDS.sleep(idle.toMillis())
        }
        log.info("Worker stopped")
    }

    companion object {
        fun sandboxFor(config: WorkerConfig): Sandbox = when (config.isolationMode) {
            "dev-process" -> DevProcessSandbox()
            "container" -> ContainerSandbox(config.containerRuntime, config.containerImage, gvisor = false)
            "container-gvisor" -> ContainerSandbox(config.containerRuntime, config.containerImage, gvisor = true)
            else -> error("Unsupported isolation mode ${config.isolationMode}")
        }

        fun detectToolVersions(config: WorkerConfig): Map<String, String?> {
            val node = if (config.isolationMode == "dev-process") {
                runCatching {
                    val p = ProcessBuilder(config.nodeExecutable, "--version").redirectErrorStream(true).start()
                    p.inputStream.readAllBytes().toString(Charsets.UTF_8).trim().also { p.waitFor(5, TimeUnit.SECONDS) }
                }.getOrNull()
            } else {
                "image ${config.containerImage}"
            }
            return mapOf("node" to node, "workerVersion" to (Worker::class.java.`package`?.implementationVersion ?: "dev"))
        }
    }
}

fun main() {
    val config = WorkerConfig.fromEnvironment()
    val worker = Worker(config)
    Runtime.getRuntime().addShutdownHook(Thread { worker.stop() })
    worker.runForever()
}
