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
 * Milestone 2 acceptance: a real TypeScript repository is analysed by the
 * adapter inside a worker, and its actual symbols, dependencies and findings can
 * be navigated. Findings keep their identity across snapshots and follow the
 * OPEN → FIXED → REOPENED lifecycle without overriding human triage.
 */
class CodeIntelligenceTest : IntegrationTest() {
    private fun worker() = Worker(
        WorkerConfig(
            controlPlaneUrl = internalUrl,
            workerToken = WORKER_TOKEN,
            isolationMode = "dev-process",
            workRoot = Files.createTempDirectory("qf-worker-ci"),
            tsAnalyzerDir = Archives.repoRoot().resolve("language-packs/typescript"),
        ),
    )

    private fun copySample(): Path {
        val dir = Files.createTempDirectory("qf-sample-ci")
        Archives.sampleRepository().toFile().copyRecursively(dir.toFile())
        return dir
    }

    private fun upload(token: String, repo: String, dir: Path): String {
        val r = api.upload("/api/v1/repositories/$repo/snapshots", token, Archives.tarGzDirectory(dir, Files.createTempFile("s", ".tar.gz")))
        assertTrue(r.status in setOf(200, 201), r.body)
        return r.json.path("snapshot").path("id").text()
    }

    private fun analyse(token: String, project: String, snapshot: String): String {
        val submit = api.post("/api/v1/projects/$project/analyses", token, mapOf("snapshotId" to snapshot))
        assertEquals(201, submit.status, submit.body)
        assertTrue(worker().runOnce())
        val id = submit.field("id")
        val detail = api.get("/api/v1/analyses/$id", token).json
        assertEquals("SUCCEEDED", detail.path("job").path("state").text(), detail.toString())
        return id
    }

    private fun findings(token: String, project: String, query: String = "") =
        api.get("/api/v1/projects/$project/findings?size=100$query", token).json.path("items").elements()

    @Test
    fun `analysis indexes the repository and tracks findings across snapshots`() {
        val owner = api.login("ci-owner-${System.nanoTime()}@ci.test")
        val org = api.createOrganization(owner)
        val project = api.createProject(owner, org, "Commerce")
        val repo = api.createUploadRepository(owner, project)
        val sample = copySample()
        val original = upload(owner, repo, sample)

        // 1. Analyse the shipped sample.
        val first = analyse(owner, project, original)
        val detail = api.get("/api/v1/analyses/$first", owner).json
        val index = detail.path("index")
        assertEquals("typescript", index.path("adapterId").text())
        assertEquals(10, index.path("fileCount").asInt())
        assertEquals("none", index.path("capabilities").path("dataFlow").text())
        assertTrue(index.path("limitations").size() > 0)
        assertEquals(5, detail.path("findings").path("byStatus").path("OPEN").asInt())

        val symbols = api.get("/api/v1/projects/$project/symbols?q=retry", owner).json.path("items").elements()
        val withRetry = symbols.first { it.path("key").text() == "src/retry.ts#withRetry" }
        assertEquals("function", withRetry.path("kind").text())
        assertEquals(5, withRetry.path("complexity").asInt()) // 1 + for + catch + if + ??
        assertEquals("method", api.get("/api/v1/projects/$project/symbols?q=reserve", owner).json.path("items").get(0).path("kind").text())

        val graph = api.get("/api/v1/projects/$project/dependencies", owner).json
        val edges = graph.path("edges").elements().map { "${it.path("from").text()} -> ${it.path("to").text()}" }
        assertTrue("src/orders.ts -> src/cart.ts" in edges && "tests/retry.test.ts -> src/retry.ts" in edges, edges.toString())
        assertTrue(graph.path("external").texts("packageName").containsAll(listOf("node:test", "node:assert/strict")))
        val calls = api.get("/api/v1/projects/$project/calls?symbol=src/cart.ts%23averageUnitPrice", owner).json.texts("to")
        assertTrue("src/cart.ts#itemCount" in calls && "src/money.ts#roundCurrency" in calls, calls.toString())

        val open = findings(owner, project)
        assertEquals(setOf("QF-TS-002", "QF-TS-004", "QF-TS-006", "QF-TS-007", "QF-TS-008"), open.texts("ruleId").toSet())
        assertTrue(open.all { it.path("evidenceLevel").text() == "SUSPECTED" && it.path("status").text() == "OPEN" })
        val rounding = open.first { it.path("ruleId").text() == "QF-TS-008" }
        assertEquals("src/money.ts", rounding.path("path").text())
        assertEquals(11, rounding.path("startLine").asInt())

        val annotations = api.get("/api/v1/projects/$project/annotations?snapshotId=$original&path=src/money.ts", owner).json
        assertEquals(listOf("QF-TS-008"), annotations.path("findings").texts("ruleId"))
        assertTrue(annotations.path("symbols").texts("name").containsAll(listOf("roundCurrency", "formatUsd", "salesTax")))

        // 2. Human triage: a dismissal needs a reason, and stale versions are rejected.
        val retry = open.first { it.path("ruleId").text() == "QF-TS-004" }
        val retryId = retry.path("id").text()
        val noReason = api.post("/api/v1/findings/$retryId/status", owner, mapOf("status" to "FALSE_POSITIVE", "version" to retry.path("version").asLong()))
        assertEquals("reason_required", noReason.errorCode)
        val dismissed = api.post("/api/v1/findings/$retryId/status", owner,
            mapOf("status" to "FALSE_POSITIVE", "reason" to "Callers only pass idempotent reads", "version" to retry.path("version").asLong()))
        assertEquals(200, dismissed.status, dismissed.body)
        assertEquals("stale_version", api.post("/api/v1/findings/$retryId/status", owner, mapOf("status" to "OPEN", "version" to 0)).errorCode)
        assertEquals("status_not_settable", api.post("/api/v1/findings/$retryId/status", owner, mapOf("status" to "FIXED", "version" to 1)).errorCode)

        // 3. Shift every line of money.ts: the finding keeps its identity, with a new occurrence.
        Files.writeString(sample.resolve("src/money.ts"), "// Pricing helpers\n// (moved)\n\n" + Files.readString(sample.resolve("src/money.ts")))
        val shifted = upload(owner, repo, sample)
        analyse(owner, project, shifted)
        val roundingAgain = findings(owner, project, "&ruleId=QF-TS-008").single()
        assertEquals(rounding.path("id").text(), roundingAgain.path("id").text())
        assertEquals(14, roundingAgain.path("startLine").asInt())
        assertEquals(2, api.get("/api/v1/findings/${rounding.path("id").text()}", owner).json.path("occurrences").size())

        // 4. Apply the known fixes: open findings become FIXED, the dismissed one keeps its decision.
        val fixedDir = copySample()
        val git = ProcessBuilder(listOf("git", "apply") + (1..6).map { "fixes/D$it.patch" }).directory(fixedDir.toFile()).start()
        assertTrue(git.waitFor(30, TimeUnit.SECONDS) && git.exitValue() == 0)
        val fixedSnapshot = upload(owner, repo, fixedDir)
        analyse(owner, project, fixedSnapshot)
        val afterFix = findings(owner, project).associate { it.path("ruleId").text() to it.path("status").text() }
        assertEquals(mapOf("QF-TS-002" to "FIXED", "QF-TS-004" to "FALSE_POSITIVE", "QF-TS-006" to "FIXED", "QF-TS-007" to "FIXED", "QF-TS-008" to "FIXED"), afterFix)
        val history = api.get("/api/v1/findings/${rounding.path("id").text()}", owner).json.path("history").elements()
        assertEquals(listOf("OPEN", "FIXED"), history.map { it.path("toStatus").text() })
        assertEquals("OPEN", history.last().path("fromStatus").text())

        // 5. Re-analysing the older snapshot must not reopen what a newer one fixed.
        analyse(owner, project, original)
        assertEquals("FIXED", findings(owner, project, "&ruleId=QF-TS-008").single().path("status").text())

        // 6. A new snapshot that reintroduces the bug reopens it.
        Files.writeString(sample.resolve("README.md"), "reintroduced\n")
        val regressed = upload(owner, repo, sample)
        analyse(owner, project, regressed)
        val reopened = findings(owner, project, "&ruleId=QF-TS-008").single()
        assertEquals("REOPENED", reopened.path("status").text())
        assertEquals(listOf("OPEN", "FIXED", "REOPENED"), api.get("/api/v1/findings/${reopened.path("id").text()}", owner).json.path("history").texts("toStatus"))

        // Rule catalogue was registered from the adapter's report.
        assertTrue(api.get("/api/v1/rules", owner).json.texts("id").containsAll((1..9).map { "QF-TS-00$it" }))

        // Another tenant sees none of it.
        val outsider = api.login("ci-outsider-${System.nanoTime()}@other.test")
        for (path in listOf(
            "/api/v1/analyses/$first", "/api/v1/analyses/$first/findings", "/api/v1/projects/$project/symbols",
            "/api/v1/projects/$project/dependencies", "/api/v1/projects/$project/findings", "/api/v1/findings/$retryId",
            "/api/v1/projects/$project/annotations?snapshotId=$original&path=src/money.ts",
        )) {
            assertEquals(404, api.get(path, outsider).status, path)
        }
        assertEquals(404, api.post("/api/v1/findings/$retryId/status", outsider, mapOf("status" to "OPEN", "version" to 0)).status)
    }

    @Test
    fun `viewers can read findings but not triage them`() {
        val owner = api.login("ci-o-${System.nanoTime()}@ci.test")
        val org = api.createOrganization(owner)
        val project = api.createProject(owner, org)
        val repo = api.createUploadRepository(owner, project)
        val snapshot = upload(owner, repo, copySample())
        analyse(owner, project, snapshot)
        val viewerEmail = "ci-v-${System.nanoTime()}@ci.test"
        val token = api.post("/api/v1/organizations/$org/invitations", owner, mapOf("email" to viewerEmail, "role" to "org_viewer")).field("token")
        val viewer = api.login(viewerEmail)
        api.post("/api/v1/invitations/accept", viewer, mapOf("token" to token))
        val finding = findings(viewer, project).first()
        assertEquals(403, api.post("/api/v1/findings/${finding.path("id").text()}/status", viewer,
            mapOf("status" to "CONFIRMED", "version" to finding.path("version").asLong())).status)
    }
}
