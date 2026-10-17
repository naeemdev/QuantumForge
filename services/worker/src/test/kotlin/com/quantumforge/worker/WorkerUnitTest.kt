package com.quantumforge.worker

import com.quantumforge.worker.sandbox.BoundedCapture
import com.quantumforge.worker.sandbox.DevProcessSandbox
import com.quantumforge.worker.sandbox.ExecutionSpec
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WorkerUnitTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun `junit parser maps statuses and refuses external entities`() {
        val xml = tmp.resolve("ok.xml")
        Files.writeString(xml, """
            <?xml version="1.0"?>
            <testsuites>
              <testsuite name="cart">
                <testcase name="adds" time="0.002"/>
                <testcase name="breaks" time="0.001"><failure message="1 !== 2">stack</failure></testcase>
                <testcase name="later"><skipped/></testcase>
                <testcase name="crash"><error message="boom"/></testcase>
              </testsuite>
              <testcase name="top-level" classname="test"/>
            </testsuites>
        """.trimIndent())
        val report = JunitParser.parse(xml, "node:test")
        assertEquals(listOf(TestStatus.PASSED, TestStatus.FAILED, TestStatus.SKIPPED, TestStatus.ERRORED, TestStatus.PASSED), report.cases.map { it.status })
        assertEquals("cart", report.cases[0].suite)
        assertEquals(null, report.cases[4].suite)
        assertEquals("1 !== 2", report.cases[1].failureMessage)
        assertEquals(2L, report.cases[0].durationMs)

        val xxe = tmp.resolve("xxe.xml")
        Files.writeString(xxe, """<?xml version="1.0"?><!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]><testsuites><testcase name="&e;"/></testsuites>""")
        assertFailsWith<Exception> { JunitParser.parse(xxe, "node:test") }
    }

    private fun tar(target: Path, vararg entries: Pair<String, String>): Path {
        TarArchiveOutputStream(GzipCompressorOutputStream(Files.newOutputStream(target))).use { out ->
            for ((name, content) in entries) {
                val e = TarArchiveEntry(name, true)
                e.size = content.length.toLong()
                out.putArchiveEntry(e)
                out.write(content.toByteArray())
                out.closeArchiveEntry()
            }
        }
        return target
    }

    @Test
    fun `extractor writes regular files and refuses traversal`() {
        val good = tar(tmp.resolve("good.tar.gz"), "a/b.ts" to "x", "c.ts" to "y")
        val dest = tmp.resolve("out")
        assertEquals(2, SnapshotExtractor.extract(good, dest))
        assertEquals("x", Files.readString(dest.resolve("a/b.ts")))

        val evil = tar(tmp.resolve("evil.tar.gz"), "../escape.txt" to "x")
        assertFailsWith<IllegalStateException> { SnapshotExtractor.extract(evil, tmp.resolve("out2")) }
        assertTrue(!Files.exists(tmp.resolve("escape.txt")))

        assertFailsWith<IllegalStateException> { SnapshotExtractor.verifyDigest(good, "0".repeat(64)) }
    }

    @Test
    fun `bounded capture keeps a prefix and reports the full size`() {
        val capture = BoundedCapture(ByteArrayInputStream(ByteArray(10_000) { 'a'.code.toByte() }), tmp.resolve("cap.log"), 1000).start().finish()
        assertEquals(10_000, capture.totalBytes)
        assertEquals(1000, capture.storedBytes)
        assertTrue(capture.truncated)
        assertEquals(1000, Files.size(tmp.resolve("cap.log")))
    }

    private fun spec(command: List<String>, timeout: Duration) = ExecutionSpec(
        sourceDir = Files.createDirectories(tmp.resolve("src")),
        outputDir = Files.createDirectories(tmp.resolve("out")),
        logDir = Files.createDirectories(tmp.resolve("logs")),
        command = { command },
        environment = mapOf("QF_PROBE" to "1"),
        timeout = timeout,
        memoryMb = 128,
        cpuMillis = 1000,
        maxOutputBytes = 4096,
    )

    @Test
    fun `dev sandbox enforces the timeout, scrubs the environment and kills the process`() {
        val sandbox = DevProcessSandbox()
        val slow = sandbox.run(spec(listOf("sh", "-c", "sleep 30"), Duration.ofMillis(500))) { false }
        assertTrue(slow.timedOut)
        assertTrue(slow.durationMs < 10_000)

        val env = sandbox.run(spec(listOf("sh", "-c", "env"), Duration.ofSeconds(10))) { false }
        assertEquals(0, env.exitCode)
        val output = Files.readString(env.stdout.file)
        assertTrue("QF_PROBE=1" in output)
        assertTrue("QF_WORKER_TOKEN" !in output && "AWS_" !in output, "worker secrets must not leak into jobs")

        var polls = 0
        val cancelled = sandbox.run(spec(listOf("sh", "-c", "sleep 30"), Duration.ofSeconds(30))) { ++polls > 2 }
        assertTrue(cancelled.cancelled)
    }
}
