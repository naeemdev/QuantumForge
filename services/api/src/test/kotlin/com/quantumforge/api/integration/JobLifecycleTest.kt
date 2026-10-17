package com.quantumforge.api.integration

import com.quantumforge.api.jobs.LeaseReaper
import com.quantumforge.api.support.Archives
import com.quantumforge.api.support.IntegrationTest
import com.quantumforge.api.support.Response
import com.quantumforge.api.support.elements
import com.quantumforge.api.support.text
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.nio.file.Files
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Drives the worker protocol by hand to pin down queue, lease and retry semantics. */
class JobLifecycleTest : IntegrationTest() {
    @Autowired
    lateinit var reaper: LeaseReaper

    private data class Fixture(val token: String, val orgId: String, val projectId: String)

    private fun fixture(): Fixture {
        val token = api.login("jobs-${System.nanoTime()}@jobs.test")
        val org = api.createOrganization(token)
        val project = api.createProject(token, org)
        val repo = api.createUploadRepository(token, project)
        val archive = Archives.tarGz(Files.createTempFile("tiny", ".tar.gz"), listOf(Archives.file("a.test.js", "// ${UUID.randomUUID()}\n")))
        assertEquals(201, api.upload("/api/v1/repositories/$repo/snapshots", token, archive).status)
        return Fixture(token, org, project)
    }

    private fun submit(f: Fixture, key: String? = null, body: Any? = null): Response =
        api.post("/api/v1/projects/${f.projectId}/test-runs", f.token, body ?: emptyMap<String, Any>(), key?.let { mapOf("Idempotency-Key" to it) } ?: emptyMap())

    private fun lease(): Response = api.raw("$internalUrl/internal/v1/leases", WORKER_TOKEN, "POST")

    private fun attemptCall(lease: Response, action: String, body: Any? = emptyMap<String, Any>()): Response {
        val request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("$internalUrl/internal/v1/attempts/${lease.field("attemptId")}/$action"))
            .header("Authorization", "Bearer $WORKER_TOKEN")
            .header("X-Lease-Token", lease.field("leaseToken"))
            .header("Content-Type", "application/json")
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(com.quantumforge.api.support.ApiClient.mapper.writeValueAsString(body)))
            .build()
        val r = java.net.http.HttpClient.newHttpClient().send(request, java.net.http.HttpResponse.BodyHandlers.ofString())
        return Response(r.statusCode(), r.body(), r.headers().map())
    }

    private fun complete(lease: Response, outcome: String) = attemptCall(lease, "complete", mapOf("outcome" to outcome, "exitCode" to 0, "durationMs" to 10))

    private fun job(f: Fixture, id: String) = api.get("/api/v1/jobs/$id", f.token).json

    @Test
    fun `idempotency key returns the original job and rejects a different request`() {
        val f = fixture()
        val first = submit(f, "deploy-42")
        assertEquals(201, first.status, first.body)
        val retry = submit(f, "deploy-42")
        assertEquals(200, retry.status)
        assertEquals(first.field("id"), retry.field("id"))
        val conflicting = submit(f, "deploy-42", mapOf("priority" to 5))
        assertEquals(409, conflicting.status)
        assertEquals("idempotency_key_reused", conflicting.errorCode)
    }

    @Test
    fun `active job quota is enforced per tenant`() {
        val f = fixture()
        jdbc.sql("UPDATE tenants SET max_active_jobs = 1 WHERE id = (SELECT tenant_id FROM organizations WHERE id = CAST(:org AS uuid))").param("org", f.orgId).update()
        assertEquals(201, submit(f).status)
        val second = submit(f)
        assertEquals(429, second.status)
        assertEquals("quota_exceeded", second.errorCode)
    }

    @Test
    fun `invalid configuration and missing snapshots are rejected before queueing`() {
        val token = api.login("cfg-${System.nanoTime()}@jobs.test")
        val org = api.createOrganization(token)
        val project = api.createProject(token, org)
        assertEquals("no_snapshot", api.post("/api/v1/projects/$project/test-runs", token, emptyMap<String, Any>()).errorCode)
        val f = fixture()
        val bad = submit(f, body = mapOf("config" to mapOf("testPatterns" to listOf("../../etc/passwd"))))
        assertEquals(400, bad.status)
        val optionInjection = submit(f, body = mapOf("config" to mapOf("testPatterns" to listOf("--require=/tmp/x.js"))))
        assertEquals(400, optionInjection.status)
    }

    @Test
    fun `queued jobs cancel immediately and finished jobs cannot be cancelled`() {
        val f = fixture()
        val id = submit(f).field("id")
        val cancelled = api.post("/api/v1/jobs/$id/cancel", f.token)
        assertEquals("CANCELLED", cancelled.field("state"))
        assertEquals(409, api.post("/api/v1/jobs/$id/cancel", f.token).status)
        assertEquals(204, lease().status, "a cancelled job must never be leased")
    }

    @Test
    fun `running jobs learn about cancellation from the heartbeat`() {
        val f = fixture()
        val id = submit(f).field("id")
        val leased = lease()
        assertEquals(200, leased.status)
        assertEquals(id, leased.field("jobId"))
        assertEquals(204, attemptCall(leased, "start", mapOf("environment" to mapOf("node" to "test"))).status)
        assertEquals("RUNNING", job(f, id).path("job").path("state").text())

        assertEquals(true, api.post("/api/v1/jobs/$id/cancel", f.token).json.path("cancelRequested").asBoolean())
        assertEquals(true, attemptCall(leased, "heartbeat").json.path("cancelRequested").asBoolean())
        assertEquals("CANCELLED", complete(leased, "CANCELLED").field("jobState"))
        assertEquals("CANCELLED", job(f, id).path("job").path("state").text())
    }

    @Test
    fun `completion is idempotent and conflicting reports are refused`() {
        val f = fixture()
        submit(f)
        val leased = lease()
        attemptCall(leased, "start", mapOf("environment" to emptyMap<String, Any>()))
        assertEquals("FAILED", complete(leased, "FAILED").field("jobState"))
        assertEquals(200, complete(leased, "FAILED").status, "a retried identical report is accepted")
        assertEquals(409, complete(leased, "SUCCEEDED").status)
        assertEquals(409, attemptCall(leased, "heartbeat").status, "a finished attempt has no lease")
    }

    @Test
    fun `expired leases are retried and then fail as infrastructure errors`() {
        val f = fixture()
        val id = submit(f).field("id")
        fun expireLeases() = jdbc.sql("UPDATE job_attempts SET lease_expires_at = now() - interval '1 minute' WHERE outcome IS NULL AND job_id = CAST(:id AS uuid)").param("id", id).update()

        // Attempt 1: the worker disappears.
        val first = lease()
        expireLeases()
        assertEquals(1, reaper.reapExpired())
        assertEquals("QUEUED", job(f, id).path("job").path("state").text())
        val stale = attemptCall(first, "heartbeat")
        assertEquals(409, stale.status, "the old lease must be dead")
        assertEquals("lease_lost", stale.errorCode)
        assertEquals(409, complete(first, "SUCCEEDED").status, "a late report from a lost lease must not overwrite the job")

        // Attempt 2: the worker reports an infrastructure error and the job is retried.
        val second = lease()
        assertEquals(2, second.json.path("attemptNumber").asInt())
        val retried = complete(second, "INFRA_ERROR")
        assertEquals("QUEUED", retried.field("jobState"))
        assertEquals(true, retried.json.path("retried").asBoolean())

        // Attempt 3 (the last): the lease expires again, attempts are exhausted.
        lease()
        expireLeases()
        reaper.reapExpired()
        val final = job(f, id)
        assertEquals("INFRA_ERROR", final.path("job").path("state").text())
        assertEquals(listOf("LEASE_EXPIRED", "INFRA_ERROR", "LEASE_EXPIRED"), final.path("attempts").elements().map { it.path("outcome").text() })
        val events = api.get("/api/v1/jobs/$id/events", f.token).json.elements().map { it.path("eventType").text() }
        assertTrue(events.count { it == "job.lease_expired" } == 2 && "job.retry_scheduled" in events)
    }

    @Test
    fun `audit events cannot be modified or deleted`() {
        fixture()
        assertFailsWith<Exception> { jdbc.sql("UPDATE audit_events SET action = 'tampered'").update() }
        assertFailsWith<Exception> { jdbc.sql("DELETE FROM audit_events").update() }
    }
}
