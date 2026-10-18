package com.quantumforge.api.integration

import com.quantumforge.api.support.Archives
import com.quantumforge.api.support.IntegrationTest
import com.quantumforge.api.support.text
import com.quantumforge.api.support.texts
import com.quantumforge.api.support.elements
import com.quantumforge.worker.Worker
import com.quantumforge.worker.WorkerConfig
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals

/**
 * The M1, M2 and M3 acceptance flows (including mutation testing) with the container sandbox instead of the
 * development process sandbox. Needs Docker (QF_TEST_DOCKER=1) and a deployed
 * copy of the TypeScript adapter (QF_TEST_TS_ANALYZER, e.g. from `pnpm deploy`),
 * because a pnpm workspace's symlinked dependencies do not resolve inside a container.
 */
class ContainerExecutionTest : IntegrationTest() {
    @Test
    fun `test runs, analyses, fuzz campaigns and mutation runs execute in containers`() {
        assumeTrue(System.getenv("QF_TEST_DOCKER") == "1", "QF_TEST_DOCKER not set")
        val analyzer = System.getenv("QF_TEST_TS_ANALYZER")?.let { Path.of(it) }
        assumeTrue(analyzer != null, "QF_TEST_TS_ANALYZER not set")

        val worker = Worker(
            WorkerConfig(
                controlPlaneUrl = internalUrl,
                workerToken = CONTAINER_WORKER_TOKEN,
                isolationMode = "container",
                workRoot = Files.createTempDirectory("qf-container-worker"),
                containerImage = System.getenv("QF_TEST_CONTAINER_IMAGE") ?: "node:22.22-bookworm-slim",
                tsAnalyzerDir = analyzer,
            ),
        )
        val token = api.login("container-${System.nanoTime()}@ci.test")
        val org = api.createOrganization(token)
        val project = api.createProject(token, org)
        val repo = api.createUploadRepository(token, project)
        val upload = api.upload("/api/v1/repositories/$repo/snapshots", token,
            Archives.tarGzDirectory(Archives.sampleRepository(), Files.createTempFile("sample", ".tar.gz")))
        val snapshot = upload.json.path("snapshot").path("id").text()

        val run = api.post("/api/v1/projects/$project/test-runs", token, mapOf("snapshotId" to snapshot)).field("id")
        worker.runOnce()
        val detail = api.get("/api/v1/jobs/$run", token).json
        assertEquals("FAILED", detail.path("job").path("state").text(), detail.toString())
        val tests = detail.path("job").path("resultSummary").path("tests")
        assertEquals(18, tests.path("total").asInt())
        assertEquals(6, tests.path("failed").asInt())
        val isolation = detail.path("attempts").get(0).path("environment").path("isolation")
        assertEquals("container", isolation.path("mode").text())
        assertEquals("none (--network none)", isolation.path("networkIsolation").text())

        val analysis = api.post("/api/v1/projects/$project/analyses", token, mapOf("snapshotId" to snapshot)).field("id")
        worker.runOnce()
        val analysed = api.get("/api/v1/analyses/$analysis", token).json
        assertEquals("SUCCEEDED", analysed.path("job").path("state").text(), analysed.toString())
        val rules = api.get("/api/v1/projects/$project/findings?source=STATIC_ANALYSIS", token).json.path("items").elements().texts("ruleId").toSet()
        assertEquals(setOf("QF-TS-002", "QF-TS-004", "QF-TS-006", "QF-TS-007", "QF-TS-008"), rules)

        // The fuzzer forks one child process per target; that must work as uid 65534 on a read-only root.
        val campaign = api.post("/api/v1/projects/$project/fuzz-campaigns", token, mapOf("snapshotId" to snapshot, "iterationsPerTarget" to 100)).field("id")
        worker.runOnce()
        val fuzzed = api.get("/api/v1/fuzz-campaigns/$campaign", token).json
        assertEquals("SUCCEEDED", fuzzed.path("job").path("state").text(), fuzzed.toString())
        assertEquals(8, fuzzed.path("campaign").path("targetsTested").asInt())
        val reproduction = fuzzed.path("reproductions").elements().single()
        assertEquals("averageUnitPrice", reproduction.path("exportName").text())
        assertEquals(3, reproduction.path("reproduced").asInt())

        // Mutation testing writes its work copies to the output mount and runs node:test once per mutant.
        val mutationRun = api.post("/api/v1/projects/$project/mutation-runs", token, mapOf("snapshotId" to snapshot, "maxMutants" to 20)).field("id")
        worker.runOnce()
        val mutated = api.get("/api/v1/mutation-runs/$mutationRun", token).json
        assertEquals("SUCCEEDED", mutated.path("job").path("state").text(), mutated.toString())
        assertEquals(12, mutated.path("run").path("baseline").path("passed").asInt())
        assertEquals(20, mutated.path("run").path("total").asInt())
        assertEquals(0, mutated.path("run").path("errors").asInt())
    }
}
