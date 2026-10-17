package com.quantumforge.api.identity

import com.quantumforge.api.access.OrgAccess
import com.quantumforge.api.access.Roles
import com.quantumforge.api.audit.AuditActor
import com.quantumforge.api.audit.AuditService
import com.quantumforge.api.common.BadRequestException
import com.quantumforge.api.common.ConflictException
import com.quantumforge.api.common.ForbiddenException
import com.quantumforge.api.common.Hashing
import com.quantumforge.api.common.Ids
import com.quantumforge.api.common.NotFoundException
import com.quantumforge.api.common.instant
import com.quantumforge.api.common.sql
import com.quantumforge.api.common.uuid
import com.quantumforge.api.security.AuthMethod
import com.quantumforge.api.security.Caller
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class Invitation(
    val id: UUID,
    val organizationId: UUID,
    val email: String,
    val roleId: String,
    val status: String,
    val expiresAt: Instant,
    val createdAt: Instant,
)

/** Returned once, at creation: the plaintext token is never stored or shown again. */
data class CreatedInvitation(val invitation: Invitation, val token: String)

@Service
class InvitationService(
    private val jdbc: JdbcClient,
    private val audit: AuditService,
    private val clock: Clock,
) {
    private val emailPattern = Regex("^[^@\\s]{1,64}@[^@\\s]{1,255}$")

    @Transactional
    fun create(ctx: OrgAccess, email: String, roleId: String, ttl: Duration = Duration.ofDays(7)): CreatedInvitation {
        val normalized = email.trim().lowercase()
        if (!emailPattern.matches(normalized)) throw BadRequestException("invalid_email", "Invalid email address")
        if (roleId !in Roles.all) throw BadRequestException("invalid_role", "Unknown role '$roleId'")
        if (roleId == Roles.OWNER && ctx.roleId != Roles.OWNER) throw ForbiddenException("Only owners can invite owners")

        val alreadyMember = jdbc.sql(
            """
            SELECT count(*) FROM organization_memberships m JOIN users u ON u.id = m.user_id
            WHERE m.organization_id = :org AND m.status = 'ACTIVE' AND lower(u.email) = :email
            """,
        ).param("org", ctx.organizationId).param("email", normalized).query(Int::class.java).single()
        if (alreadyMember > 0) throw ConflictException("already_member", "$normalized is already a member")

        val token = "qf_inv_" + Hashing.randomSecret()
        val now = Instant.now(clock)
        val id = Ids.newId(clock)
        try {
            jdbc.sql(
                """
                INSERT INTO invitations (id, tenant_id, organization_id, email, role_id, token_hash, expires_at,
                                         created_at, created_by, updated_at, updated_by)
                VALUES (:id, :tenant, :org, :email, :role, :hash, :expires, :now, :user, :now, :user)
                """,
            ).param("id", id).param("tenant", ctx.tenantId).param("org", ctx.organizationId)
                .param("email", normalized).param("role", roleId).param("hash", Hashing.sha256(token))
                .param("expires", now.plus(ttl).sql()).param("now", now.sql()).param("user", ctx.userId).update()
        } catch (e: DuplicateKeyException) {
            throw ConflictException("invitation_pending", "An invitation for $normalized is already pending")
        }
        audit.record(ctx, "invitation.created", "invitation", id, mapOf("email" to normalized, "role" to roleId))
        return CreatedInvitation(get(id), token)
    }

    fun list(ctx: OrgAccess): List<Invitation> =
        jdbc.sql("SELECT * FROM invitations WHERE tenant_id = :tenant AND organization_id = :org ORDER BY created_at DESC LIMIT 500")
            .param("tenant", ctx.tenantId).param("org", ctx.organizationId).query(::map).list()

    @Transactional
    fun revoke(ctx: OrgAccess, invitationId: UUID) {
        val n = jdbc.sql(
            """
            UPDATE invitations SET status = 'REVOKED', updated_at = :now, updated_by = :user, version = version + 1
            WHERE id = :id AND tenant_id = :tenant AND organization_id = :org AND status = 'PENDING'
            """,
        ).param("now", Instant.now(clock).sql()).param("user", ctx.userId)
            .param("id", invitationId).param("tenant", ctx.tenantId).param("org", ctx.organizationId).update()
        if (n == 0) throw NotFoundException("invitation", invitationId)
        audit.record(ctx, "invitation.revoked", "invitation", invitationId)
    }

    /**
     * Accepting requires the caller's email (from their identity provider) to match
     * the invited address, so a leaked link alone does not grant access.
     */
    @Transactional
    fun accept(caller: Caller, token: String): UUID {
        if (caller.authMethod == AuthMethod.API_TOKEN) throw ForbiddenException("API tokens cannot accept invitations")
        data class Row(val id: UUID, val tenantId: UUID, val orgId: UUID, val email: String, val roleId: String, val status: String, val expiresAt: Instant)
        val row = jdbc.sql("SELECT * FROM invitations WHERE token_hash = :hash FOR UPDATE")
            .param("hash", Hashing.sha256(token))
            .query { rs, _ -> Row(rs.uuid("id"), rs.uuid("tenant_id"), rs.uuid("organization_id"), rs.getString("email"), rs.getString("role_id"), rs.getString("status"), rs.instant("expires_at")) }
            .optional().orElseThrow { NotFoundException("invitation", null) }
        val now = Instant.now(clock)
        if (row.status != "PENDING") throw ConflictException("invitation_not_pending", "This invitation is ${row.status.lowercase()}")
        if (row.expiresAt.isBefore(now)) {
            jdbc.sql("UPDATE invitations SET status = 'EXPIRED', updated_at = :now, updated_by = :user WHERE id = :id")
                .param("now", now.sql()).param("user", caller.userId).param("id", row.id).update()
            throw ConflictException("invitation_expired", "This invitation has expired")
        }
        if (caller.email?.lowercase() != row.email) {
            audit.write(row.tenantId, row.orgId, AuditActor.of(caller), "invitation.accept_denied", "invitation", row.id, "DENIED")
            throw ForbiddenException("This invitation was issued to a different email address")
        }

        // Reactivate a previously removed membership rather than violating the unique key.
        val updated = jdbc.sql(
            """
            UPDATE organization_memberships SET status = 'ACTIVE', role_id = :role, updated_at = :now, updated_by = :user, version = version + 1
            WHERE organization_id = :org AND user_id = :user AND status <> 'ACTIVE'
            """,
        ).param("role", row.roleId).param("now", now.sql()).param("user", caller.userId).param("org", row.orgId).update()
        if (updated == 0) {
            jdbc.sql(
                """
                INSERT INTO organization_memberships (id, tenant_id, organization_id, user_id, role_id, created_at, created_by, updated_at, updated_by)
                VALUES (:id, :tenant, :org, :user, :role, :now, :user, :now, :user)
                ON CONFLICT (organization_id, user_id) DO NOTHING
                """,
            ).param("id", Ids.newId(clock)).param("tenant", row.tenantId).param("org", row.orgId)
                .param("user", caller.userId).param("role", row.roleId).param("now", now.sql()).update()
        }
        jdbc.sql(
            """
            UPDATE invitations SET status = 'ACCEPTED', accepted_by = :user, accepted_at = :now, updated_at = :now, updated_by = :user, version = version + 1
            WHERE id = :id
            """,
        ).param("user", caller.userId).param("now", now.sql()).param("id", row.id).update()
        audit.write(row.tenantId, row.orgId, AuditActor.of(caller), "invitation.accepted", "invitation", row.id, "SUCCESS", mapOf("role" to row.roleId))
        return row.orgId
    }

    private fun get(id: UUID): Invitation =
        jdbc.sql("SELECT * FROM invitations WHERE id = :id").param("id", id).query(::map).single()

    private fun map(rs: ResultSet, @Suppress("UNUSED_PARAMETER") i: Int) = Invitation(
        id = rs.uuid("id"),
        organizationId = rs.uuid("organization_id"),
        email = rs.getString("email"),
        roleId = rs.getString("role_id"),
        status = rs.getString("status"),
        expiresAt = rs.instant("expires_at"),
        createdAt = rs.instant("created_at"),
    )
}
