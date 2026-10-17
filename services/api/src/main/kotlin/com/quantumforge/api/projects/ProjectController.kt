package com.quantumforge.api.projects

import com.quantumforge.api.access.AccessService
import com.quantumforge.api.access.Permission
import com.quantumforge.api.common.Page
import com.quantumforge.api.common.PageRequest
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.util.UUID

data class CreateProjectRequest(
    @field:NotNull val organizationId: UUID,
    @field:NotBlank @field:Size(max = 200) val name: String,
    @field:Size(min = 2, max = 63) val slug: String? = null,
    @field:Size(max = 2000) val description: String? = null,
    val workspaceId: UUID? = null,
)

data class UpdateProjectRequest(
    @field:Size(min = 1, max = 200) val name: String? = null,
    @field:Size(max = 2000) val description: String? = null,
    @field:Pattern(regexp = "ACTIVE|ARCHIVED") val status: String? = null,
    @field:NotNull val version: Long,
)

data class CreateRepositoryRequest(
    @field:NotBlank @field:Size(max = 200) val name: String,
    @field:NotBlank @field:Pattern(regexp = "UPLOAD|GIT") val sourceType: String,
    @field:Size(max = 2000) val remoteUrl: String? = null,
    @field:Size(max = 255) val defaultBranch: String? = null,
)

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Projects")
class ProjectController(private val projects: ProjectService, private val access: AccessService) {
    @GetMapping("/projects")
    fun list(
        @RequestParam organizationId: UUID,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?,
    ): Page<Project> = projects.list(access.require(organizationId, Permission.PROJECT_READ), PageRequest.of(page, size), q)

    @PostMapping("/projects")
    fun create(@Valid @RequestBody body: CreateProjectRequest): ResponseEntity<Project> {
        val ctx = access.require(body.organizationId, Permission.PROJECT_CREATE)
        val project = projects.create(ctx, body.name.trim(), body.slug, body.description?.trim(), body.workspaceId)
        return ResponseEntity.created(URI.create("/api/v1/projects/${project.id}")).body(project)
    }

    @GetMapping("/projects/{id}")
    fun get(@PathVariable id: UUID): Project = projects.get(access.requireForProject(id, Permission.PROJECT_READ), id)

    @PatchMapping("/projects/{id}")
    fun update(@PathVariable id: UUID, @Valid @RequestBody body: UpdateProjectRequest): Project =
        projects.update(
            access.requireForProject(id, Permission.PROJECT_UPDATE),
            id,
            ProjectPatch(body.name?.trim(), body.description?.trim(), body.status, body.version),
        )

    @GetMapping("/projects/{id}/repositories")
    fun repositories(@PathVariable id: UUID): List<CodeRepository> =
        projects.repositories(access.requireForProject(id, Permission.PROJECT_READ), id)

    @PostMapping("/projects/{id}/repositories")
    fun addRepository(@PathVariable id: UUID, @Valid @RequestBody body: CreateRepositoryRequest): ResponseEntity<CodeRepository> {
        val ctx = access.requireForProject(id, Permission.PROJECT_UPDATE)
        val repo = projects.addRepository(ctx, id, body.name.trim(), body.sourceType, body.remoteUrl?.trim(), body.defaultBranch?.trim())
        return ResponseEntity.created(URI.create("/api/v1/repositories/${repo.id}")).body(repo)
    }

    @GetMapping("/repositories/{id}")
    fun repository(@PathVariable id: UUID): CodeRepository =
        projects.getRepository(access.requireForRepository(id, Permission.PROJECT_READ), id)
}
