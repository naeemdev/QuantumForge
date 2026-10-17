package com.quantumforge.api.identity

import com.quantumforge.api.access.AccessService
import com.quantumforge.api.access.OrgAccess
import com.quantumforge.api.access.Permission
import com.quantumforge.api.access.Roles
import com.quantumforge.api.audit.AuditActor
import com.quantumforge.api.audit.AuditService
import com.quantumforge.api.common.BadRequestException
import com.quantumforge.api.common.ConflictException
import com.quantumforge.api.common.ForbiddenException
import com.quantumforge.api.common.Ids
import com.quantumforge.api.common.NotFoundException
import com.quantumforge.api.common.Page
import com.quantumforge.api.common.PageRequest
import com.quantumforge.api.common.Slugs
import com.quantumforge.api.common.StaleVersionException
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
import java.time.Instant
import java.util.UUID

data class Organization(
    val id: UUID,
    val tenantId: UUID,
    val name: String,
    val slug: String,
    val status: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long,
)

data class OrganizationSummary(val organization: Organization, val roleId: String)

data class Workspace(val id: UUID, val organizationId: UUID, val name: String, val slug: String, val createdAt: Instant)

data class Member(
    val membershipId: UUID,
    val userId: UUID,
    val email: String?,
    val displayName: String?,
    val roleId: String,
    val status: String,
    val joinedAt: Instant,
    val version: Long,
)

@Service
class OrganizationService(
    private val jdbc: JdbcClient,
    private val access: AccessService,
    private val audit: AuditService,
    private val clock: Clock,
) {
    /**
     * Creates an organization with a default workspace and makes the caller its
     * owner. The first organization a user creates also creates their tenant;
     * later ones join the tenant they own.
     */
    @Transactional
    fun create(caller: Caller, name: String, requestedSlug: String?): Organization {
        if (caller.authMethod == AuthMethod.API_TOKEN) {
            throw ForbiddenException("API tokens cannot create organizations")
        }
        val slug = requestedSlug?.let { Slugs.require(it) } ?: uniqueSlug(Slugs.fromName(name))
        val now = Instant.now(clock)

        val tenantId = jdbc.sql("SELECT id FROM tenants WHERE owner_user_id = :user AND status = 'ACTIVE' ORDER BY created_at LIMIT 1")
            .param("user", caller.userId).query(UUID::class.java).optional().orElse(null)
            ?: createTenant(caller, name, now)

        val orgId = Ids.newId(clock)
        try {
            jdbc.sql(
                """
                INSERT INTO organizations (id, tenant_id, name, slug, created_at, created_by, updated_at, updated_by)
                VALUES (:id, :tenant, :name, :slug, :now, :user, :now, :user)
                """,
            ).param("id", orgId).param("tenant", tenantId).param("name", name).param("slug", slug)
                .param("now", now.sql()).param("user", caller.userId).update()
        } catch (e: DuplicateKeyException) {
            throw ConflictException("slug_taken", "Organization slug '$slug' is already in use", mapOf("slug" to slug))
        }

        jdbc.sql(
            """
            INSERT INTO workspaces (id, tenant_id, organization_id, name, slug, created_at, created_by, updated_at, updated_by)
            VALUES (:id, :tenant, :org, 'Default', 'default', :now, :user, :now, :user)
            """,
        ).param("id", Ids.newId(clock)).param("tenant", tenantId).param("org", orgId)
            .param("now", now.sql()).param("user", caller.userId).update()

        jdbc.sql(
            """
            INSERT INTO organization_memberships (id, tenant_id, organization_id, user_id, role_id, created_at, created_by, updated_at, updated_by)
            VALUES (:id, :tenant, :org, :user, :role, :now, :user, :now, :user)
            """,
        ).param("id", Ids.newId(clock)).param("tenant", tenantId).param("org", orgId).param("user", caller.userId)
            .param("role", Roles.OWNER).param("now", now.sql()).update()

        audit.write(tenantId, orgId, AuditActor.of(caller), "organization.created", "organization", orgId, "SUCCESS", mapOf("slug" to slug))
        return get(orgId)
    }

    private fun createTenant(caller: Caller, orgName: String, now: Instant): UUID {
        val id = Ids.newId(clock)
        jdbc.sql(
            """
            INSERT INTO tenants (id, name, owner_user_id, created_at, created_by, updated_at, updated_by)
            VALUES (:id, :name, :user, :now, :user, :now, :user)
            """,
        ).param("id", id).param("name", orgName).param("user", caller.userId).param("now", now.sql()).update()
        audit.write(id, null, AuditActor.of(caller), "tenant.created", "tenant", id, "SUCCESS")
        return id
    }

    private fun uniqueSlug(base: String): String {
        val taken = jdbc.sql("SELECT slug FROM organizations WHERE slug = :base OR slug LIKE :prefix")
            .param("base", base).param("prefix", "${base.take(56)}-%")
            .query(String::class.java).set()
        if (base !in taken) return base
        for (i in 2..999) {
            val candidate = "${base.take(56)}-$i"
            if (candidate !in taken) return candidate
        }
        return "${base.take(50)}-${UUID.randomUUID().toString().take(8)}"
    }

    fun get(id: UUID): Organization =
        jdbc.sql("SELECT * FROM organizations WHERE id = :id").param("id", id).query(::mapOrg).optional()
            .orElseThrow { NotFoundException("organization", id) }

    fun listForCaller(caller: Caller, page: PageRequest): Page<OrganizationSummary> {
        val tokenOrg = caller.tokenOrganizationId
        val filter = if (tokenOrg != null) "AND o.id = :tokenOrg" else ""
        val items = jdbc.sql(
            """
            SELECT o.*, m.role_id FROM organization_memberships m
            JOIN organizations o ON o.id = m.organization_id
            WHERE m.user_id = :user AND m.status = 'ACTIVE' AND o.status = 'ACTIVE' $filter
            ORDER BY o.name, o.id LIMIT :limit OFFSET :offset
            """,
        ).param("user", caller.userId).apply { if (tokenOrg != null) param("tokenOrg", tokenOrg) }
            .param("limit", page.size).param("offset", page.offset)
            .query { rs, i -> OrganizationSummary(mapOrg(rs, i), rs.getString("role_id")) }.list()
        val total = jdbc.sql(
            """
            SELECT count(*) FROM organization_memberships m JOIN organizations o ON o.id = m.organization_id
            WHERE m.user_id = :user AND m.status = 'ACTIVE' AND o.status = 'ACTIVE' $filter
            """,
        ).param("user", caller.userId).apply { if (tokenOrg != null) param("tokenOrg", tokenOrg) }
            .query(Long::class.java).single()
        return Page.of(items, page, total)
    }

    @Transactional
    fun rename(ctx: OrgAccess, name: String, expectedVersion: Long): Organization {
        val updated = jdbc.sql(
            """
            UPDATE organizations SET name = :name, updated_at = :now, updated_by = :user, version = version + 1
            WHERE id = :id AND tenant_id = :tenant AND version = :version
            """,
        ).param("name", name).param("now", Instant.now(clock).sql()).param("user", ctx.userId)
            .param("id", ctx.organizationId).param("tenant", ctx.tenantId).param("version", expectedVersion).update()
        if (updated == 0) throw StaleVersionException("organization")
        audit.record(ctx, "organization.renamed", "organization", ctx.organizationId, mapOf("name" to name))
        return get(ctx.organizationId)
    }

    fun workspaces(ctx: OrgAccess): List<Workspace> =
        jdbc.sql("SELECT * FROM workspaces WHERE tenant_id = :tenant AND organization_id = :org ORDER BY created_at")
            .param("tenant", ctx.tenantId).param("org", ctx.organizationId)
            .query { rs, _ -> Workspace(rs.uuid("id"), rs.uuid("organization_id"), rs.getString("name"), rs.getString("slug"), rs.instant("created_at")) }
            .list()

    fun members(ctx: OrgAccess, page: PageRequest): Page<Member> {
        val items = jdbc.sql(
            """
            SELECT m.id AS membership_id, m.user_id, u.email, u.display_name, m.role_id, m.status, m.created_at, m.version
            FROM organization_memberships m JOIN users u ON u.id = m.user_id
            WHERE m.tenant_id = :tenant AND m.organization_id = :org AND m.status <> 'REMOVED'
            ORDER BY m.created_at, m.id LIMIT :limit OFFSET :offset
            """,
        ).param("tenant", ctx.tenantId).param("org", ctx.organizationId)
            .param("limit", page.size).param("offset", page.offset)
            .query(::mapMember).list()
        val total = jdbc.sql("SELECT count(*) FROM organization_memberships WHERE tenant_id = :tenant AND organization_id = :org AND status <> 'REMOVED'")
            .param("tenant", ctx.tenantId).param("org", ctx.organizationId).query(Long::class.java).single()
        return Page.of(items, page, total)
    }

    /**
     * Role changes follow two rules: nobody can grant a role ranked above their
     * own, and the organization must always keep at least one active owner.
     */
    @Transactional
    fun changeRole(ctx: OrgAccess, membershipId: UUID, newRole: String, expectedVersion: Long): Member {
        if (newRole !in Roles.all) throw BadRequestException("invalid_role", "Unknown role '$newRole'")
        val target = member(ctx, membershipId)
        val ranks = roleRanks()
        val myRank = ranks.getValue(ctx.roleId)
        if (ranks.getValue(newRole) > myRank || ranks.getValue(target.roleId) > myRank) {
            throw ForbiddenException("You cannot assign or change a role ranked above your own")
        }
        lockOrganization(ctx)
        if (target.roleId == Roles.OWNER && newRole != Roles.OWNER && activeOwnerCount(ctx) <= 1) {
            throw ConflictException("last_owner", "An organization must keep at least one owner")
        }
        val updated = jdbc.sql(
            """
            UPDATE organization_memberships SET role_id = :role, updated_at = :now, updated_by = :user, version = version + 1
            WHERE id = :id AND tenant_id = :tenant AND organization_id = :org AND version = :version
            """,
        ).param("role", newRole).param("now", Instant.now(clock).sql()).param("user", ctx.userId)
            .param("id", membershipId).param("tenant", ctx.tenantId).param("org", ctx.organizationId)
            .param("version", expectedVersion).update()
        if (updated == 0) throw StaleVersionException("membership")
        audit.record(ctx, "membership.role_changed", "membership", membershipId, mapOf("from" to target.roleId, "to" to newRole, "userId" to target.userId))
        return member(ctx, membershipId)
    }

    @Transactional
    fun removeMember(ctx: OrgAccess, membershipId: UUID) {
        val target = member(ctx, membershipId)
        val ranks = roleRanks()
        if (ranks.getValue(target.roleId) > ranks.getValue(ctx.roleId)) {
            throw ForbiddenException("You cannot remove a member ranked above you")
        }
        lockOrganization(ctx)
        if (target.roleId == Roles.OWNER && activeOwnerCount(ctx) <= 1) {
            throw ConflictException("last_owner", "An organization must keep at least one owner")
        }
        jdbc.sql(
            """
            UPDATE organization_memberships SET status = 'REMOVED', updated_at = :now, updated_by = :user, version = version + 1
            WHERE id = :id AND tenant_id = :tenant AND organization_id = :org
            """,
        ).param("now", Instant.now(clock).sql()).param("user", ctx.userId)
            .param("id", membershipId).param("tenant", ctx.tenantId).param("org", ctx.organizationId).update()
        // Tokens belong to a membership in practice; revoke them with it.
        jdbc.sql("UPDATE api_tokens SET revoked_at = :now, revoked_by = :user WHERE organization_id = :org AND user_id = :target AND revoked_at IS NULL")
            .param("now", Instant.now(clock).sql()).param("user", ctx.userId).param("org", ctx.organizationId).param("target", target.userId).update()
        audit.record(ctx, "membership.removed", "membership", membershipId, mapOf("userId" to target.userId))
    }

    fun member(ctx: OrgAccess, membershipId: UUID): Member =
        jdbc.sql(
            """
            SELECT m.id AS membership_id, m.user_id, u.email, u.display_name, m.role_id, m.status, m.created_at, m.version
            FROM organization_memberships m JOIN users u ON u.id = m.user_id
            WHERE m.id = :id AND m.tenant_id = :tenant AND m.organization_id = :org AND m.status <> 'REMOVED'
            """,
        ).param("id", membershipId).param("tenant", ctx.tenantId).param("org", ctx.organizationId)
            .query(::mapMember).optional().orElseThrow { NotFoundException("membership", membershipId) }

    /** Serialises owner-count checks so two concurrent demotions cannot both pass. */
    private fun lockOrganization(ctx: OrgAccess) {
        jdbc.sql("SELECT id FROM organizations WHERE id = :id AND tenant_id = :tenant FOR UPDATE")
            .param("id", ctx.organizationId).param("tenant", ctx.tenantId).query(UUID::class.java).single()
    }

    private fun activeOwnerCount(ctx: OrgAccess): Int =
        jdbc.sql("SELECT count(*) FROM organization_memberships WHERE organization_id = :org AND tenant_id = :tenant AND role_id = 'org_owner' AND status = 'ACTIVE'")
            .param("org", ctx.organizationId).param("tenant", ctx.tenantId).query(Int::class.java).single()

    private fun roleRanks(): Map<String, Int> =
        jdbc.sql("SELECT id, rank FROM roles WHERE organization_id IS NULL")
            .query { rs, _ -> rs.getString("id") to rs.getInt("rank") }.list().toMap()

    fun requireMembersManage(orgId: UUID) = access.require(orgId, Permission.ORG_MEMBERS_MANAGE)

    private fun mapOrg(rs: ResultSet, @Suppress("UNUSED_PARAMETER") i: Int) = Organization(
        id = rs.uuid("id"),
        tenantId = rs.uuid("tenant_id"),
        name = rs.getString("name"),
        slug = rs.getString("slug"),
        status = rs.getString("status"),
        createdAt = rs.instant("created_at"),
        updatedAt = rs.instant("updated_at"),
        version = rs.getLong("version"),
    )

    private fun mapMember(rs: ResultSet, @Suppress("UNUSED_PARAMETER") i: Int) = Member(
        membershipId = rs.uuid("membership_id"),
        userId = rs.uuid("user_id"),
        email = rs.getString("email"),
        displayName = rs.getString("display_name"),
        roleId = rs.getString("role_id"),
        status = rs.getString("status"),
        joinedAt = rs.instant("created_at"),
        version = rs.getLong("version"),
    )
}
