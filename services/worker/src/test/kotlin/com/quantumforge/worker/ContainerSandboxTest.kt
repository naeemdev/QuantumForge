package com.quantumforge.worker

import com.quantumforge.worker.sandbox.ContainerSandbox
import com.quantumforge.worker.sandbox.ExecutionSpec
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Checks the container sandbox's isolation properties against a real Docker
 * daemon. Runs when QF_TEST_DOCKER=1 (CI); skipped elsewhere.
 */
class ContainerSandboxTest {
    @TempDir
    lateinit var tmp: Path

    private val image = System.getenv("QF_TEST_CONTAINER_IMAGE") ?: "node:22.22-bookworm-slim"

    @BeforeEach
    fun requireDocker() {
        assumeTrue(System.getenv("QF_TEST_DOCKER") == "1", "QF_TEST_DOCKER not set")
    }

    private fun spec(script: String, timeout: Duration = Duration.ofSeconds(60), tools: Map<String, Path> = emptyMap()): ExecutionSpec {
        val out = Files.createDirectories(tmp.resolve("out"))
        Files.setPosixFilePermissions(out, PosixFilePermissions.fromString("rwxrwxrwx"))
        return ExecutionSpec(
            sourceDir = Files.createDirectories(tmp.resolve("src")),
            outputDir = out,
            logDir = Files.createDirectories(tmp.resolve("logs")),
            command = { listOf("sh", "-c", script) },
            environment = mapOf("QF_PROBE" to "1"),
            timeout = timeout,
            memoryMb = 256,
            cpuMillis = 1000,
            maxOutputBytes = 64 * 1024,
            tools = tools,
        )
    }

    @Test
    fun `jobs run unprivileged without network on a read-only root`() {
        val tool = Files.createDirectories(tmp.resolve("tool"))
        Files.writeString(tool.resolve("hello.txt"), "tool-visible")
        val script = listOf(
            "echo uid=$(id -u)",
            "touch /etc/qf 2>/dev/null && echo rootfs=writable || echo rootfs=readonly",
            "touch /work/src/qf 2>/dev/null && echo src=writable || echo src=readonly",
            "touch /opt/qf/tools/t/qf 2>/dev/null && echo tool=writable || echo tool=readonly",
            "echo result > /work/out/r.txt && echo out=writable",
            "cat /opt/qf/tools/t/hello.txt; echo",
            "echo probe=\$QF_PROBE token=\${QF_WORKER_TOKEN:-absent}",
            "node -e \"fetch('http://1.1.1.1',{signal:AbortSignal.timeout(3000)}).then(()=>console.log('net=open'),()=>console.log('net=blocked'))\"",
        ).joinToString("; ")
        val result = ContainerSandbox("docker", image, gvisor = false).run(spec(script, tools = mapOf("t" to tool))) { false }
        val output = Files.readString(result.stdout.file)
        assertNull(result.sandboxError, Files.readString(result.stderr.file))
        assertEquals(0, result.exitCode, output + Files.readString(result.stderr.file))
        for (expected in listOf("uid=65534", "rootfs=readonly", "src=readonly", "tool=readonly", "out=writable", "tool-visible", "probe=1 token=absent", "net=blocked")) {
            assertTrue(expected in output, "expected '$expected' in:\n$output")
        }
        assertEquals("result", Files.readString(tmp.resolve("out/r.txt")).trim())
    }

    @Test
    fun `timeouts stop the container`() {
        val result = ContainerSandbox("docker", image, gvisor = false).run(spec("sleep 60", Duration.ofSeconds(3))) { false }
        assertTrue(result.timedOut)
        assertTrue(result.durationMs < 30_000, "took ${result.durationMs} ms")
    }

    @Test
    fun `runtime failures are reported as sandbox errors, not job failures`() {
        val result = ContainerSandbox("docker", "quantumforge/does-not-exist:none", gvisor = false).run(spec("true")) { false }
        assertNotNull(result.sandboxError)
    }
}
