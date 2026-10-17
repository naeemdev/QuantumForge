package com.quantumforge.api.integration

import com.quantumforge.api.support.IntegrationTest
import com.quantumforge.api.support.elements
import com.quantumforge.api.support.text
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AuthorizationTest : IntegrationTest() {
    private fun email(tag: String) = "$tag-${System.nanoTime()}@authz.test"

    @Test
    fun `requests without or with invalid credentials are rejected`() {
        assertEquals(401, api.get("/api/v1/me", null).status)
        val bad = api.get("/api/v1/me", "not-a-jwt")
        assertEquals(401, bad.status)
        assertEquals("invalid_token", bad.errorCode)
        assertEquals(401, api.get("/api/v1/me", "qf_pat_doesnotexist").status)
        // Errors carry the request id for support and correlation.
        assertNotNull(bad.json.path("error").path("requestId").text().takeIf { it.isNotEmpty() })
    }

    @Test
    fun `invitation flow grants the invited role and enforces it`() {
        val owner = api.login(email("owner"))
        val org = api.createOrganization(owner)
        val viewerEmail = email("viewer")
        val invite = api.post("/api/v1/organizations/$org/invitations", owner, mapOf("email" to viewerEmail, "role" to "org_viewer"))
        assertEquals(201, invite.status, invite.body)
        val token = invite.field("token")

        // Someone else holding the link cannot use it.
        val stranger = api.login(email("stranger"))
        assertEquals(403, api.post("/api/v1/invitations/accept", stranger, mapOf("token" to token)).status)

        val viewer = api.login(viewerEmail)
        val accepted = api.post("/api/v1/invitations/accept", viewer, mapOf("token" to token))
        assertEquals(200, accepted.status, accepted.body)
        assertEquals("org_viewer", accepted.field("role"))
        assertEquals(409, api.post("/api/v1/invitations/accept", viewer, mapOf("token" to token)).status, "invitations are single-use")

        // Viewer can read but not write.
        assertEquals(200, api.get("/api/v1/projects?organizationId=$org", viewer).status)
        val denied = api.post("/api/v1/projects", viewer, mapOf("organizationId" to org, "name" to "Nope"))
        assertEquals(403, denied.status)
        assertEquals("project.create", denied.json.path("error").path("details").path("permission").text())
        assertEquals(403, api.post("/api/v1/organizations/$org/invitations", viewer, mapOf("email" to email("x"), "role" to "org_member")).status)

        // The denial is in the audit trail.
        val audit = api.get("/api/v1/organizations/$org/audit-events", owner).json.path("items").elements()
        assertTrue(audit.any { it.path("action").text() == "permission.denied" && it.path("outcome").text() == "DENIED" })
        assertTrue(audit.any { it.path("action").text() == "invitation.accepted" })
    }

    @Test
    fun `role changes cannot escalate privileges or remove the last owner`() {
        val owner = api.login(email("owner"))
        val org = api.createOrganization(owner)
        val adminEmail = email("admin")
        val token = api.post("/api/v1/organizations/$org/invitations", owner, mapOf("email" to adminEmail, "role" to "org_admin")).field("token")
        val admin = api.login(adminEmail)
        api.post("/api/v1/invitations/accept", admin, mapOf("token" to token))

        val members = api.get("/api/v1/organizations/$org/members", owner).json.path("items").elements()
        val ownerMembership = members.first { it.path("roleId").text() == "org_owner" }
        val adminMembership = members.first { it.path("roleId").text() == "org_admin" }

        // An admin cannot promote themselves to owner or demote the owner.
        val selfPromote = api.patch("/api/v1/organizations/$org/members/${adminMembership.path("membershipId").text()}", admin,
            mapOf("role" to "org_owner", "version" to adminMembership.path("version").asLong()))
        assertEquals(403, selfPromote.status)
        val demoteOwner = api.patch("/api/v1/organizations/$org/members/${ownerMembership.path("membershipId").text()}", admin,
            mapOf("role" to "org_member", "version" to ownerMembership.path("version").asLong()))
        assertEquals(403, demoteOwner.status)

        // The sole owner cannot demote themselves.
        val lastOwner = api.patch("/api/v1/organizations/$org/members/${ownerMembership.path("membershipId").text()}", owner,
            mapOf("role" to "org_admin", "version" to ownerMembership.path("version").asLong()))
        assertEquals(409, lastOwner.status)
        assertEquals("last_owner", lastOwner.errorCode)

        // Stale versions are rejected (optimistic locking).
        val stale = api.patch("/api/v1/organizations/$org/members/${adminMembership.path("membershipId").text()}", owner,
            mapOf("role" to "org_member", "version" to 99))
        assertEquals(409, stale.status)
        assertEquals("stale_version", stale.errorCode)
    }

    @Test
    fun `api tokens are scoped to one organization and stop working when revoked`() {
        val user = api.login(email("cli"))
        val orgA = api.createOrganization(user)
        val orgB = api.createOrganization(user)
        val created = api.post("/api/v1/organizations/$orgA/api-tokens", user, mapOf("name" to "ci", "expiresInDays" to 7))
        assertEquals(201, created.status, created.body)
        val secret = created.field("secret")
        assertTrue(secret.startsWith("qf_pat_"))
        val tokenId = created.json.path("token").path("id").text()

        assertEquals(201, api.post("/api/v1/projects", secret, mapOf("organizationId" to orgA, "name" to "From CI")).status)
        assertEquals(404, api.get("/api/v1/organizations/$orgB", secret).status, "token must not reach other organizations")
        assertEquals(listOf(orgA), api.get("/api/v1/organizations", secret).json.path("items").elements().map { it.path("id").text() })
        assertEquals(403, api.post("/api/v1/organizations/$orgA/api-tokens", secret, mapOf("name" to "nested")).status, "tokens cannot mint tokens")
        assertEquals(403, api.post("/api/v1/organizations", secret, mapOf("name" to "From token")).status)

        // Secrets are never returned again.
        val listed = api.get("/api/v1/organizations/$orgA/api-tokens", user)
        assertTrue(!listed.body.contains(secret))

        assertEquals(204, api.delete("/api/v1/organizations/$orgA/api-tokens/$tokenId", user).status)
        assertEquals(401, api.get("/api/v1/me", secret).status)
    }

    @Test
    fun `worker endpoints are unreachable from the public port and require a worker token`() {
        val user = api.login(email("u"))
        assertEquals(404, api.post("/internal/v1/leases", WORKER_TOKEN).status)
        assertEquals(401, api.raw("$internalUrl/internal/v1/leases", user, "POST").status)
        assertEquals(404, api.raw("$internalUrl/api/v1/me", user).status)
        assertTrue(api.raw("$internalUrl/internal/v1/leases", WORKER_TOKEN, "POST").status in setOf(200, 204))
    }

    @Test
    fun `container workers can always lease jobs`() {
        assertTrue(api.raw("$internalUrl/internal/v1/leases", CONTAINER_WORKER_TOKEN, "POST").status in setOf(200, 204))
    }

    @Test
    fun `the openapi document excludes internal routes`() {
        val doc = api.get("/v3/api-docs/public", null)
        assertEquals(200, doc.status)
        assertTrue(doc.body.contains("/api/v1/projects"))
        assertTrue(!doc.body.contains("/internal/"))
    }
}
