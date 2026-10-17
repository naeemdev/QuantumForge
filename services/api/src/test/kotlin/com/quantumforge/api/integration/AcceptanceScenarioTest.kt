package com.quantumforge.api.integration

import com.quantumforge.api.support.Archives
import com.quantumforge.api.support.IntegrationTest
import com.quantumforge.api.support.elements
import com.quantumforge.api.support.text
import com.quantumforge.api.support.texts
import com.quantumforge.worker.Worker
import com.quantumforge.worker.WorkerConfig
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Milestone 1 acceptance scenario, end to end over HTTP with a real worker
 * process running the seeded sample repository:
 *
 * login → organization → project → upload snapshot → queue test run → worker
 * leases and executes it → results, logs and environment are stored → the
 * dashboard and history show them → another tenant sees none of it.
 */
class AcceptanceScenarioTest : IntegrationTest() {
    private fun worker() = Worker(
        WorkerConfig(
            controlPlaneUrl = internalUrl,
            workerToken = WORKER_TOKEN,
            isolationMode = "dev-process",
            workRoot = Files.createTempDirectory("qf-worker-test"),
        ),
    )

    @Test
    fun `seeded defects are executed, recorded and isolated per tenant`() {
        val owner = api.login("owner-${System.nanoTime()}@demo.test", "Demo Owner")
        val orgId = api.createOrganization(owner, "Demo Engineering")
        val projectId = api.createProject(owner, orgId, "Sample TypeScript Application")
        val repoId = api.createUploadRepository(owner, projectId, "sample-commerce-api")

        // Upload the sample as a GitHub-style archive with a single top-level folder.
        val staging = Files.createTempDirectory("qf-sample")
        val wrapped = staging.resolve("sample-commerce-api-main")
        Archives.sampleRepository().toFile().copyRecursively(wrapped.toFile())
        val archive = Archives.tarGzDirectory(staging, Files.createTempFile("sample", ".tar.gz"))
        val upload = api.upload("/api/v1/repositories/$repoId/snapshots", owner, archive)
        assertEquals(201, upload.status, upload.body)
        val snapshotId = upload.json.path("snapshot").path("id").text()
        assertEquals(true, upload.json.path("created").asBoolean())

        val files = api.get("/api/v1/snapshots/$snapshotId/files?size=200", owner)
        val paths = files.json.path("items").texts("path")
        assertTrue("src/money.ts" in paths && "tests/cart.test.ts" in paths, "root folder should be stripped: $paths")
        val languages = api.get("/api/v1/snapshots/$snapshotId/languages", owner).json.texts("language")
        assertTrue("TypeScript" in languages)

        // Same content again resolves to the same immutable snapshot.
        val again = api.upload("/api/v1/repositories/$repoId/snapshots", owner, archive)
        assertEquals(200, again.status)
        assertEquals(snapshotId, again.json.path("snapshot").path("id").text())

        // Queue, then run on an authorised worker.
        val submit = api.post("/api/v1/projects/$projectId/test-runs", owner, mapOf("snapshotId" to snapshotId), mapOf("Idempotency-Key" to "acceptance-1"))
        assertEquals(201, submit.status, submit.body)
        val jobId = submit.field("id")
        assertEquals("QUEUED", submit.field("state"))
        assertTrue(worker().runOnce(), "worker should lease the queued job")

        val detail = api.get("/api/v1/jobs/$jobId", owner).json
        val job = detail.path("job")
        assertEquals("FAILED", job.path("state").text(), "seeded defects must make the run fail")
        val tests = job.path("resultSummary").path("tests")
        assertEquals(18, tests.path("total").asInt())
        assertEquals(12, tests.path("passed").asInt())
        assertEquals(6, tests.path("failed").asInt())
        val attempt = detail.path("attempts").get(0)
        assertEquals("dev-process", attempt.path("isolationMode").text())
        assertEquals(1, attempt.path("exitCode").asInt())
        assertTrue(attempt.path("environment").path("runtime").path("node").text().startsWith("v"))
        assertEquals(true, attempt.path("environment").path("snapshot").path("verified").asBoolean())

        val results = api.get("/api/v1/jobs/$jobId/test-results", owner).json.get(0).path("cases")
        val failed = results.elements().filter { it.path("status").text() == "FAILED" }.map { it.path("name").text().substringBefore(':') }.sorted()
        assertEquals(listOf("D1", "D2", "D3", "D4", "D5", "D6"), failed)
        assertTrue(results.elements().first { it.path("name").text().startsWith("D1") }.path("failureMessage").text().contains("1.01"))

        // Evidence artifacts are retrievable and match their recorded digests.
        val artifacts = api.get("/api/v1/jobs/$jobId/artifacts", owner).json
        assertEquals(setOf("stdout.log", "stderr.log", "environment.json", "junit.xml"), artifacts.texts("name").toSet())
        val stdout = artifacts.elements().first { it.path("name").text() == "stdout.log" }
        val (status, bytes) = api.bytes("/api/v1/jobs/$jobId/artifacts/${stdout.path("id").text()}/content", owner)
        assertEquals(200, status)
        assertEquals(stdout.path("sha256").text(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
        assertTrue(bytes.toString(Charsets.UTF_8).contains("D5: concurrent reservations never oversell the last unit"))

        val events = api.get("/api/v1/jobs/$jobId/events", owner).json.texts("eventType")
        assertEquals(listOf("job.queued", "job.leased", "job.started"), events.take(3))
        assertEquals("job.completed", events.last())

        // Now verify that applying the known fixes turns the same suite green.
        val fixed = applyKnownFixes()
        val fixedUpload = api.upload("/api/v1/repositories/$repoId/snapshots", owner, Archives.tarGzDirectory(fixed, Files.createTempFile("fixed", ".tar.gz")))
        assertEquals(201, fixedUpload.status, fixedUpload.body)
        val fixedJob = api.post("/api/v1/projects/$projectId/test-runs", owner, mapOf("snapshotId" to fixedUpload.json.path("snapshot").path("id").text())).field("id")
        assertTrue(worker().runOnce())
        val fixedState = api.get("/api/v1/jobs/$fixedJob", owner).json.path("job")
        assertEquals("SUCCEEDED", fixedState.path("state").text(), fixedState.toString())
        assertEquals(18, fixedState.path("resultSummary").path("tests").path("passed").asInt())

        // History and dashboard reflect both executions.
        val history = api.get("/api/v1/jobs?organizationId=$orgId&projectId=$projectId", owner).json
        assertEquals(2, history.path("total").asInt())
        val overview = api.get("/api/v1/organizations/$orgId/overview", owner).json
        assertEquals(1, overview.path("projectCount").asInt())
        assertEquals(1, overview.path("jobsByState").path("FAILED").asInt())
        assertEquals(1, overview.path("jobsByState").path("SUCCEEDED").asInt())
        assertEquals(30, overview.path("testTotals").path("passed").asInt())

        // A different tenant cannot see any of it.
        val outsider = api.login("outsider-${System.nanoTime()}@other.test")
        val outsiderOrg = api.createOrganization(outsider, "Other Company")
        for (path in listOf(
            "/api/v1/organizations/$orgId",
            "/api/v1/projects/$projectId",
            "/api/v1/repositories/$repoId",
            "/api/v1/snapshots/$snapshotId",
            "/api/v1/snapshots/$snapshotId/content?path=src/money.ts",
            "/api/v1/jobs/$jobId",
            "/api/v1/jobs/$jobId/test-results",
            "/api/v1/jobs/$jobId/artifacts",
            "/api/v1/jobs/$jobId/artifacts/${stdout.path("id").text()}/content",
            "/api/v1/projects?organizationId=$orgId",
            "/api/v1/jobs?organizationId=$orgId",
            "/api/v1/organizations/$orgId/overview",
        )) {
            assertEquals(404, api.get(path, outsider).status, "outsider must get 404 for $path")
        }
        assertEquals(404, api.post("/api/v1/projects/$projectId/test-runs", outsider).status)
        assertEquals(404, api.post("/api/v1/jobs/$jobId/cancel", outsider).status)
        val outsiderOrgs = api.get("/api/v1/organizations", outsider).json.path("items").texts("id")
        assertEquals(listOf(outsiderOrg), outsiderOrgs)
    }

    private fun applyKnownFixes(): Path {
        val dir = Files.createTempDirectory("qf-fixed")
        Archives.sampleRepository().toFile().copyRecursively(dir.toFile())
        val patches = Files.list(dir.resolve("fixes")).use { s -> s.map { it.toString() }.sorted().toList() }
        val p = ProcessBuilder(listOf("git", "apply") + patches).directory(dir.toFile()).redirectErrorStream(true).start()
        val output = p.inputStream.readAllBytes().toString(Charsets.UTF_8)
        check(p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0) { "git apply failed: $output" }
        return dir
    }
}
