package com.quantumforge.api.integration

import com.quantumforge.api.support.Archives
import com.quantumforge.api.support.IntegrationTest
import com.quantumforge.api.support.elements
import com.quantumforge.api.support.text
import com.quantumforge.worker.Worker
import com.quantumforge.worker.WorkerConfig
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Mutation testing end to end: the real runner in a worker against the bug
 * corpus, whose suite already fails six tests. Only the twelve passing tests can
 * kill mutants, and the survivors point at what the suite does not check.
 */
class MutationTestingTest : IntegrationTest() {
    private fun worker() = Worker(
        WorkerConfig(
            controlPlaneUrl = internalUrl,
            workerToken = WORKER_TOKEN,
            isolationMode = "dev-process",
            workRoot = Files.createTempDirectory("qf-worker-mutation"),
            tsAnalyzerDir = Archives.repoRoot().resolve("language-packs/typescript"),
        ),
    )

    @Test
    fun `a mutation run scores the suite and lists surviving mutants`() {
        val owner = api.login("mutation-${System.nanoTime()}@ci.test")
        val org = api.createOrganization(owner)
        val project = api.createProject(owner, org, "Commerce")
        val repo = api.createUploadRepository(owner, project)
        val upload = api.upload("/api/v1/repositories/$repo/snapshots", owner,
            Archives.tarGzDirectory(Archives.sampleRepository(), Files.createTempFile("s", ".tar.gz")))
        val snapshot = upload.json.path("snapshot").path("id").text()

        val submit = api.post("/api/v1/projects/$project/mutation-runs", owner, mapOf("snapshotId" to snapshot))
        assertEquals(201, submit.status, submit.body)
        assertEquals("TS_MUTATION", submit.json.path("config").path("preset").text())
        assertTrue(worker().runOnce())
        val id = submit.field("id")
        val detail = api.get("/api/v1/mutation-runs/$id", owner).json
        assertEquals("SUCCEEDED", detail.path("job").path("state").text(), detail.toString())

        val run = detail.path("run")
        assertTrue(run.path("notRunReason").isNull)
        assertEquals(18, run.path("baseline").path("tests").asInt())
        assertEquals(12, run.path("baseline").path("passed").asInt())
        assertEquals(6, run.path("baseline").path("failingTests").size())
        val total = run.path("total").asInt()
        assertEquals(run.path("mutantsFound").asInt(), total)
        assertEquals(total, run.path("killed").asInt() + run.path("survived").asInt() + run.path("timedOut").asInt() + run.path("errors").asInt())
        assertEquals(0, run.path("errors").asInt())
        assertTrue(run.path("score").asDouble() in 50.0..95.0, run.toString())
        assertEquals(8, run.path("operators").size())

        val mutants = detail.path("mutants").elements()
        assertEquals(total, mutants.size)
        assertEquals("SURVIVED", mutants.first().path("status").text(), "survivors are listed first")
        val reserve = mutants.single { it.path("path").text() == "src/orders.ts" && it.path("operator").text() == "call-removal" && it.path("original").text().contains("reserve") }
        assertEquals("SURVIVED", reserve.path("status").text())
        assertEquals("src/orders.ts#placeOrder", reserve.path("symbolKey").text())
        val killed = mutants.first { it.path("status").text() == "KILLED" }
        assertTrue(killed.path("killedBy").text().contains(" › "), killed.toString())
        assertNull(detail.path("job").path("resultSummary").path("mutation").path("notRunReason").textValue())

        val list = api.get("/api/v1/projects/$project/mutation-runs", owner).json
        assertEquals(1, list.path("total").asInt())
        assertEquals(total, list.path("items").get(0).path("run").path("total").asInt())

        // Mutation results are not findings.
        assertEquals(0, api.get("/api/v1/projects/$project/findings", owner).json.path("total").asInt())
        assertEquals(400, api.post("/api/v1/projects/$project/mutation-runs", owner, mapOf("testPatterns" to listOf("../escape/*.ts"))).status)
        assertEquals(400, api.post("/api/v1/projects/$project/mutation-runs", owner, mapOf("maxMutants" to 0)).status)

        val outsider = api.login("mutation-outsider-${System.nanoTime()}@other.test")
        assertEquals(404, api.get("/api/v1/mutation-runs/$id", outsider).status)
        assertEquals(404, api.get("/api/v1/projects/$project/mutation-runs", outsider).status)
        assertEquals(404, api.post("/api/v1/projects/$project/mutation-runs", outsider).status)
    }
}
