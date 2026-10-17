package com.quantumforge.api.projects

import com.quantumforge.api.access.OrgAccess
import com.quantumforge.api.audit.AuditService
import com.quantumforge.api.common.BadRequestException
import com.quantumforge.api.common.ConflictException
import com.quantumforge.api.common.Ids
import com.quantumforge.api.common.NotFoundException
import com.quantumforge.api.common.Page
import com.quantumforge.api.common.PageRequest
import com.quantumforge.api.common.Slugs
import com.quantumforge.api.common.StaleVersionException
import com.quantumforge.api.common.instant
import com.quantumforge.api.common.sql
import com.quantumforge.api.common.uuid
import com.quantumforge.api.source.GitUrls
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class Project(
    val id: UUID,
    val organizationId: UUID,
    val workspaceId: UUID,
    val name: String,
    val slug: String,
    val description: String?,
    val status: String,
    val createdAt: Instant,
    val createdBy: UUID,
    val updatedAt: Instant,
    val updatedBy: UUID,
    val version: Long,
)

data class CodeRepository(
    val id: UUID,
    val projectId: UUID,
    val name: String,
    val sourceType: String,
    val remoteUrl: String?,
    val defaultBranch: String?,
    val createdAt: Instant,
    val createdBy: UUID,
    val updatedAt: Instant,
    val version: Long,
)

data class ProjectPatch(val name: String?, val description: String?, val status: String?, val version: Long)

@Service
class ProjectService(
    private val jdbc: JdbcClient,
    private val audit: AuditService,
    private val gitUrls: GitUrls,
    private val clock: Clock,
) {
    @Transactional
    fun create(ctx: OrgAccess, name: String, requestedSlug: String?, description: String?, workspaceId: UUID?): Project {
        val workspace = workspaceId?.let { id ->
            jdbc.sql("SELECT id FROM workspaces WHERE id = :id AND tenant_id = :tenant AND organization_id = :org")
                .param("id", id).param("tenant", ctx.tenantId).param("org", ctx.organizationId)
                .query(UUID::class.java).optional().orElseThrow { NotFoundException("workspace", id) }
        } ?: jdbc.sql("SELECT id FROM workspaces WHERE tenant_id = :tenant AND organization_id = :org ORDER BY created_at LIMIT 1")
            .param("tenant", ctx.tenantId).param("org", ctx.organizationId).query(UUID::class.java).single()

        val slug = requestedSlug?.let { Slugs.require(it) } ?: Slugs.fromName(name)
        val id = Ids.newId(clock)
        val now = Instant.now(clock)
        try {
            jdbc.sql(
                """
                INSERT INTO projects (id, tenant_id, organization_id, workspace_id, name, slug, description,
                                      created_at, created_by, updated_at, updated_by)
                VALUES (:id, :tenant, :org, :ws, :name, :slug, :description, :now, :user, :now, :user)
                """,
            ).param("id", id).param("tenant", ctx.tenantId).param("org", ctx.organizationId).param("ws", workspace)
                .param("name", name).param("slug", slug).param("description", description)
                .param("now", now.sql()).param("user", ctx.userId).update()
        } catch (e: DuplicateKeyException) {
            throw ConflictException("slug_taken", "A project with slug '$slug' already exists in this workspace", mapOf("slug" to slug))
        }
        audit.record(ctx, "project.created", "project", id, mapOf("name" to name, "slug" to slug))
        return get(ctx, id)
    }

    fun list(ctx: OrgAccess, page: PageRequest, query: String?): Page<Project> {
        val q = query?.trim()?.takeIf { it.isNotEmpty() }?.let { "%" + it.lowercase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%" }
        val filter = if (q != null) "AND (lower(name) LIKE :q OR slug LIKE :q)" else ""
        val items = jdbc.sql(
            "SELECT * FROM projects WHERE tenant_id = :tenant AND organization_id = :org $filter ORDER BY updated_at DESC, id LIMIT :limit OFFSET :offset",
        ).param("tenant", ctx.tenantId).param("org", ctx.organizationId).apply { if (q != null) param("q", q) }
            .param("limit", page.size).param("offset", page.offset).query(::mapProject).list()
        val total = jdbc.sql("SELECT count(*) FROM projects WHERE tenant_id = :tenant AND organization_id = :org $filter")
            .param("tenant", ctx.tenantId).param("org", ctx.organizationId).apply { if (q != null) param("q", q) }
            .query(Long::class.java).single()
        return Page.of(items, page, total)
    }

    fun get(ctx: OrgAccess, id: UUID): Project =
        jdbc.sql("SELECT * FROM projects WHERE id = :id AND tenant_id = :tenant AND organization_id = :org")
            .param("id", id).param("tenant", ctx.tenantId).param("org", ctx.organizationId)
            .query(::mapProject).optional().orElseThrow { NotFoundException("project", id) }

    @Transactional
    fun update(ctx: OrgAccess, id: UUID, patch: ProjectPatch): Project {
        val current = get(ctx, id)
        if (patch.status != null && patch.status !in setOf("ACTIVE", "ARCHIVED")) {
            throw BadRequestException("invalid_status", "status must be ACTIVE or ARCHIVED")
        }
        val n = jdbc.sql(
            """
            UPDATE projects SET name = :name, description = :description, status = :status,
                   updated_at = :now, updated_by = :user, version = version + 1
            WHERE id = :id AND tenant_id = :tenant AND version = :version
            """,
        ).param("name", patch.name ?: current.name)
            .param("description", if (patch.description != null) patch.description.ifBlank { null } else current.description)
            .param("status", patch.status ?: current.status)
            .param("now", Instant.now(clock).sql()).param("user", ctx.userId)
            .param("id", id).param("tenant", ctx.tenantId).param("version", patch.version).update()
        if (n == 0) throw StaleVersionException("project")
        audit.record(ctx, "project.updated", "project", id, mapOf("name" to patch.name, "status" to patch.status))
        return get(ctx, id)
    }

    @Transactional
    fun addRepository(ctx: OrgAccess, projectId: UUID, name: String, sourceType: String, remoteUrl: String?, defaultBranch: String?): CodeRepository {
        val project = get(ctx, projectId)
        if (project.status != "ACTIVE") throw ConflictException("project_archived", "Archived projects cannot receive repositories")
        val url = when (sourceType) {
            "UPLOAD" -> {
                if (remoteUrl != null) throw BadRequestException("unexpected_remote_url", "UPLOAD repositories have no remote URL")
                null
            }
            "GIT" -> gitUrls.validate(remoteUrl ?: throw BadRequestException("remote_url_required", "GIT repositories need remoteUrl"))
            else -> throw BadRequestException("invalid_source_type", "sourceType must be UPLOAD or GIT")
        }
        defaultBranch?.let { GitUrls.requireSafeRef(it) }
        val id = Ids.newId(clock)
        val now = Instant.now(clock)
        try {
            jdbc.sql(
                """
                INSERT INTO repositories (id, tenant_id, project_id, name, source_type, remote_url, default_branch,
                                          created_at, created_by, updated_at, updated_by)
                VALUES (:id, :tenant, :project, :name, :type, :url, :branch, :now, :user, :now, :user)
                """,
            ).param("id", id).param("tenant", ctx.tenantId).param("project", projectId).param("name", name)
                .param("type", sourceType).param("url", url).param("branch", defaultBranch)
                .param("now", now.sql()).param("user", ctx.userId).update()
        } catch (e: DuplicateKeyException) {
            throw ConflictException("repository_exists", "A repository named '$name' already exists in this project")
        }
        audit.record(ctx, "repository.registered", "repository", id, mapOf("projectId" to projectId, "sourceType" to sourceType, "remoteUrl" to url))
        return getRepository(ctx, id)
    }

    fun repositories(ctx: OrgAccess, projectId: UUID): List<CodeRepository> {
        get(ctx, projectId)
        return jdbc.sql("SELECT * FROM repositories WHERE project_id = :project AND tenant_id = :tenant ORDER BY created_at")
            .param("project", projectId).param("tenant", ctx.tenantId).query(::mapRepository).list()
    }

    fun getRepository(ctx: OrgAccess, id: UUID): CodeRepository =
        jdbc.sql(
            """
            SELECT r.* FROM repositories r JOIN projects p ON p.id = r.project_id AND p.tenant_id = r.tenant_id
            WHERE r.id = :id AND r.tenant_id = :tenant AND p.organization_id = :org
            """,
        ).param("id", id).param("tenant", ctx.tenantId).param("org", ctx.organizationId)
            .query(::mapRepository).optional().orElseThrow { NotFoundException("repository", id) }

    private fun mapProject(rs: ResultSet, @Suppress("UNUSED_PARAMETER") i: Int) = Project(
        id = rs.uuid("id"),
        organizationId = rs.uuid("organization_id"),
        workspaceId = rs.uuid("workspace_id"),
        name = rs.getString("name"),
        slug = rs.getString("slug"),
        description = rs.getString("description"),
        status = rs.getString("status"),
        createdAt = rs.instant("created_at"),
        createdBy = rs.uuid("created_by"),
        updatedAt = rs.instant("updated_at"),
        updatedBy = rs.uuid("updated_by"),
        version = rs.getLong("version"),
    )

    private fun mapRepository(rs: ResultSet, @Suppress("UNUSED_PARAMETER") i: Int) = CodeRepository(
        id = rs.uuid("id"),
        projectId = rs.uuid("project_id"),
        name = rs.getString("name"),
        sourceType = rs.getString("source_type"),
        remoteUrl = rs.getString("remote_url"),
        defaultBranch = rs.getString("default_branch"),
        createdAt = rs.instant("created_at"),
        createdBy = rs.uuid("created_by"),
        updatedAt = rs.instant("updated_at"),
        version = rs.getLong("version"),
    )
}
