package com.quantumforge.api.access

import com.quantumforge.api.audit.AuditService
import com.quantumforge.api.common.ForbiddenException
import com.quantumforge.api.common.NotFoundException
import com.quantumforge.api.common.uuid
import com.quantumforge.api.security.Caller
import com.quantumforge.api.security.CurrentCaller
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * The caller's authorised context inside one organization. Every tenant-scoped
 * query takes its tenant_id from here, never from request input.
 */
data class OrgAccess(
    val caller: Caller,
    val tenantId: UUID,
    val organizationId: UUID,
    val roleId: String,
    val permissions: Set<Permission>,
) {
    val userId: UUID get() = caller.userId

    fun has(permission: Permission) = permission in permissions
}

@Service
class AccessService(private val jdbc: JdbcClient, private val audit: AuditService) {
    /**
     * Resolves the caller's membership in [organizationId] and requires [permission].
     * Non-members get 404 so organization IDs cannot be enumerated; members lacking
     * the permission get 403 and a DENIED audit event.
     */
    fun require(organizationId: UUID, permission: Permission, caller: Caller = CurrentCaller.get()): OrgAccess {
        val access = resolve(organizationId, caller) ?: throw NotFoundException("organization", organizationId)
        if (!access.has(permission)) {
            audit.recordDenied(access, "permission.denied", "organization", organizationId, mapOf("permission" to permission.code))
            throw ForbiddenException(details = mapOf("permission" to permission.code))
        }
        return access
    }

    fun resolve(organizationId: UUID, caller: Caller = CurrentCaller.get()): OrgAccess? {
        // A token minted for one organization never works in another.
        if (caller.tokenOrganizationId != null && caller.tokenOrganizationId != organizationId) return null

        data class Row(val tenantId: UUID, val roleId: String)
        val row = jdbc.sql(
            """
            SELECT m.tenant_id, m.role_id
            FROM organization_memberships m
            JOIN organizations o ON o.id = m.organization_id AND o.tenant_id = m.tenant_id
            JOIN tenants t ON t.id = o.tenant_id
            WHERE m.organization_id = :org AND m.user_id = :user
              AND m.status = 'ACTIVE' AND o.status = 'ACTIVE' AND t.status = 'ACTIVE'
            """,
        )
            .param("org", organizationId).param("user", caller.userId)
            .query { rs, _ -> Row(rs.uuid("tenant_id"), rs.getString("role_id")) }
            .optional().orElse(null) ?: return null

        val permissions = jdbc.sql("SELECT permission_code FROM role_permissions WHERE role_id = :role")
            .param("role", row.roleId)
            .query(String::class.java).list()
            .mapNotNull { code -> code?.let(Permission::fromCode) }
            .toSet()
        return OrgAccess(caller, row.tenantId, organizationId, row.roleId, permissions)
    }

    /** Organization that owns a project, used to authorise project-scoped routes. */
    fun organizationOfProject(projectId: UUID): UUID =
        jdbc.sql("SELECT organization_id FROM projects WHERE id = :id").param("id", projectId)
            .query(UUID::class.java).optional().orElseThrow { NotFoundException("project", projectId) }

    fun organizationOfJob(jobId: UUID): UUID =
        jdbc.sql("SELECT organization_id FROM analysis_jobs WHERE id = :id").param("id", jobId)
            .query(UUID::class.java).optional().orElseThrow { NotFoundException("job", jobId) }

    fun organizationOfRepository(repositoryId: UUID): UUID =
        jdbc.sql("SELECT p.organization_id FROM repositories r JOIN projects p ON p.id = r.project_id AND p.tenant_id = r.tenant_id WHERE r.id = :id")
            .param("id", repositoryId)
            .query(UUID::class.java).optional().orElseThrow { NotFoundException("repository", repositoryId) }

    fun organizationOfSnapshot(snapshotId: UUID): UUID =
        jdbc.sql(
            """
            SELECT p.organization_id FROM source_snapshots s
            JOIN repositories r ON r.id = s.repository_id AND r.tenant_id = s.tenant_id
            JOIN projects p ON p.id = r.project_id AND p.tenant_id = r.tenant_id
            WHERE s.id = :id
            """,
        ).param("id", snapshotId)
            .query(UUID::class.java).optional().orElseThrow { NotFoundException("snapshot", snapshotId) }

    /** Convenience: resolve the owning organization of a resource and require a permission there. */
    fun requireForProject(projectId: UUID, permission: Permission) = requireOwned("project", projectId, permission) { organizationOfProject(it) }

    fun requireForJob(jobId: UUID, permission: Permission) = requireOwned("job", jobId, permission) { organizationOfJob(it) }

    fun requireForRepository(repositoryId: UUID, permission: Permission) =
        requireOwned("repository", repositoryId, permission) { organizationOfRepository(it) }

    fun requireForSnapshot(snapshotId: UUID, permission: Permission) =
        requireOwned("snapshot", snapshotId, permission) { organizationOfSnapshot(it) }

    private fun requireOwned(resource: String, id: UUID, permission: Permission, owner: (UUID) -> UUID): OrgAccess {
        val orgId = owner(id)
        // Not a member of the owning org: report the resource itself as missing.
        resolve(orgId) ?: throw NotFoundException(resource, id)
        return require(orgId, permission)
    }
}
