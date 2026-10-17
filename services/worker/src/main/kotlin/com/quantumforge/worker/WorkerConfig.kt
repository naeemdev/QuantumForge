package com.quantumforge.worker

import java.nio.file.Path
import java.time.Duration

data class WorkerConfig(
    /** Base URL of the control plane's internal port, e.g. http://api:8081 */
    val controlPlaneUrl: String,
    val workerToken: String,
    /** dev-process | container | container-gvisor */
    val isolationMode: String,
    val workRoot: Path,
    val pollInterval: Duration = Duration.ofSeconds(2),
    val maxPollInterval: Duration = Duration.ofSeconds(15),
    val nodeExecutable: String = "node",
    val containerRuntime: String = "docker",
    val containerImage: String = "node:22.22-bookworm-slim",
    val maxSnapshotBytes: Long = 200L * 1024 * 1024,
) {
    companion object {
        fun fromEnvironment(env: Map<String, String> = System.getenv()): WorkerConfig {
            fun required(name: String) = env[name]?.takeIf { it.isNotBlank() } ?: error("Missing required environment variable $name")
            return WorkerConfig(
                controlPlaneUrl = required("QF_CONTROL_PLANE_URL").trimEnd('/'),
                workerToken = required("QF_WORKER_TOKEN"),
                isolationMode = env["QF_ISOLATION_MODE"] ?: "container",
                workRoot = Path.of(env["QF_WORK_ROOT"] ?: System.getProperty("java.io.tmpdir")).resolve("quantumforge-worker"),
                nodeExecutable = env["QF_NODE"] ?: "node",
                containerRuntime = env["QF_CONTAINER_RUNTIME"] ?: "docker",
                containerImage = env["QF_CONTAINER_IMAGE"] ?: "node:22.22-bookworm-slim",
            )
        }
    }
}
