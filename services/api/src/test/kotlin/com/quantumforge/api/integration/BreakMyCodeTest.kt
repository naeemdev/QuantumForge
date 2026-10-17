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
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Milestone 3 acceptance: a fuzz campaign finds and reproduces a seeded defect
 * of the bug corpus without being told where it is. The finding is resolved
 * only when its stored reproduction input stops failing, and static analysis
 * jobs never resolve fuzzing findings.
 */
class BreakMyCodeTest : IntegrationTest() {
    private fun worker() = Worker(
        WorkerConfig(
            controlPlaneUrl = internalUrl,
            workerToken = WORKER_TOKEN,
            isolationMode = "dev-process",
            workRoot = Files.createTempDirectory("qf-worker-fuzz"),
            tsAnalyzerDir = Archives.repoRoot().resolve("language-packs/typescript"),
        ),
    )

    private fun copySample(fixed: Boolean = false): Path {
        val dir = Files.createTempDirectory("qf-sample-fuzz")
        Archives.sampleRepository().toFile().copyRecursively(dir.toFile())
        if (fixed) {
            val git = ProcessBuilder(listOf("git", "apply") + (1..6).map { "fixes/D$it.patch" }).directory(dir.toFile()).start()
            assertTrue(git.waitFor(30, TimeUnit.SECONDS) && git.exitValue() == 0)
        }
        return dir
    }

    private fun upload(token: String, repo: String, dir: Path): String {
        val r = api.upload("/api/v1/repositories/$repo/snapshots", token, Archives.tarGzDirectory(dir, Files.createTempFile("s", ".tar.gz")))
        assertTrue(r.status in setOf(200, 201), r.body)
        return r.json.path("snapshot").path("id").text()
    }

    private fun fuzz(token: String, project: String, snapshot: String, seed: Int = 1): String {
        val submit = api.post("/api/v1/projects/$project/fuzz-campaigns", token, mapOf("snapshotId" to snapshot, "seed" to seed, "iterationsPerTarget" to 150))
        assertEquals(201, submit.status, submit.body)
        assertTrue(worker().runOnce())
        val id = submit.field("id")
        val state = api.get("/api/v1/fuzz-campaigns/$id", token).json.path("job").path("state").text()
        assertEquals("SUCCEEDED", state, api.get("/api/v1/jobs/$id/events", token).body)
        return id
    }

    private fun fuzzFindings(token: String, project: String) =
        api.get("/api/v1/projects/$project/findings?source=FUZZING&size=100", token).json.path("items").elements()

    @Test
    fun `a fuzz campaign finds, reproduces and later confirms the fix of a seeded defect`() {
        val owner = api.login("fuzz-owner-${System.nanoTime()}@ci.test")
        val org = api.createOrganization(owner)
        val project = api.createProject(owner, org, "Commerce")
        val repo = api.createUploadRepository(owner, project)
        val shipped = upload(owner, repo, copySample())

        // 1. Fuzz the shipped corpus: D6 is found, shrunk and reproduced, and nothing else is reported.
        val first = fuzz(owner, project, shipped)
        val detail = api.get("/api/v1/fuzz-campaigns/$first", owner).json
        val campaign = detail.path("campaign")
        assertEquals("typescript-fuzz", campaign.path("engineId").text())
        assertEquals(8, campaign.path("targetsTested").asInt())
        assertEquals(1, campaign.path("failures").asInt())
        assertEquals(0, campaign.path("corpusEntries").asInt())
        assertTrue(campaign.path("limitations").size() > 0)
        val targets = detail.path("targets").elements().associateBy { it.path("exportName").text() }
        assertEquals("TESTED", targets.getValue("averageUnitPrice").path("status").text())
        assertEquals("generic function", targets.getValue("withRetry").path("skipReason").text())
        assertEquals("SKIPPED", targets.getValue("InventoryService").path("status").text())

        val reproduction = detail.path("reproductions").elements().single()
        assertEquals("src/cart.ts", reproduction.path("path").text())
        assertEquals("averageUnitPrice", reproduction.path("exportName").text())
        assertEquals("non-finite-result", reproduction.path("oracle").text())
        assertEquals("returned NaN", reproduction.path("message").text())
        assertEquals(0, reproduction.path("input").get(0).path("lines").size())
        assertEquals(3, reproduction.path("reproduced").asInt())
        assertTrue("averageUnitPrice({ lines: [] })" in reproduction.path("reproductionTest").text())

        val finding = fuzzFindings(owner, project).single()
        val findingId = finding.path("id").text()
        assertEquals("QF-FUZZ-002", finding.path("ruleId").text())
        assertEquals("FUZZING", finding.path("source").text())
        assertEquals("REPRODUCED", finding.path("evidenceLevel").text())
        assertEquals("src/cart.ts#averageUnitPrice", finding.path("symbolKey").text())
        assertEquals("OPEN", finding.path("status").text())
        assertEquals(1, api.get("/api/v1/findings/$findingId", owner).json.path("reproductions").size())

        // 2. Static analysis of the fixed code must not resolve a fuzzing finding.
        val fixed = upload(owner, repo, copySample(fixed = true))
        val analysis = api.post("/api/v1/projects/$project/analyses", owner, mapOf("snapshotId" to fixed))
        assertEquals(201, analysis.status, analysis.body)
        assertTrue(worker().runOnce())
        assertEquals("OPEN", fuzzFindings(owner, project).single().path("status").text())

        // 3. Another seed on the shipped code replays the stored input first and still reproduces it.
        val second = fuzz(owner, project, shipped, seed = 99)
        val secondDetail = api.get("/api/v1/fuzz-campaigns/$second", owner).json
        assertEquals(1, secondDetail.path("campaign").path("corpusEntries").asInt())
        assertEquals(1, secondDetail.path("targets").elements().first { it.path("exportName").text() == "averageUnitPrice" }.path("replayed").asInt())
        assertEquals(listOf(findingId), fuzzFindings(owner, project).texts("id"))
        assertEquals("OPEN", fuzzFindings(owner, project).single().path("status").text())

        // 4. On the fixed code the replayed input no longer fails: FIXED, with the reason recorded.
        fuzz(owner, project, fixed)
        val resolved = api.get("/api/v1/findings/$findingId", owner).json
        assertEquals("FIXED", resolved.path("finding").path("status").text())
        val history = resolved.path("history").elements()
        assertEquals(listOf("OPEN", "FIXED"), history.map { it.path("toStatus").text() })
        assertEquals("Its reproduction input no longer fails", history.last().path("reason").text())

        // 5. Fuzzing the older snapshot again does not reopen what the newer one fixed.
        fuzz(owner, project, shipped)
        assertEquals("FIXED", fuzzFindings(owner, project).single().path("status").text())

        // Campaign list, rule catalogue and validation.
        val list = api.get("/api/v1/projects/$project/fuzz-campaigns", owner).json
        assertEquals(4, list.path("total").asInt())
        assertTrue(api.get("/api/v1/rules", owner).json.texts("id").containsAll(listOf("QF-FUZZ-001", "QF-FUZZ-002", "QF-FUZZ-003", "QF-FUZZ-004")))
        assertEquals(400, api.post("/api/v1/projects/$project/fuzz-campaigns", owner, mapOf("iterationsPerTarget" to 0)).status)
        assertEquals(404, api.get("/api/v1/fuzz-campaigns/${analysis.field("id")}", owner).status)

        // Another tenant sees none of it.
        val outsider = api.login("fuzz-outsider-${System.nanoTime()}@other.test")
        for (path in listOf("/api/v1/fuzz-campaigns/$first", "/api/v1/projects/$project/fuzz-campaigns", "/api/v1/findings/$findingId")) {
            assertEquals(404, api.get(path, outsider).status, path)
        }
        assertEquals(404, api.post("/api/v1/projects/$project/fuzz-campaigns", outsider).status)
    }
}
