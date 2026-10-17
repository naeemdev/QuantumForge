package com.quantumforge.api.source

import com.quantumforge.api.access.AccessService
import com.quantumforge.api.access.Permission
import com.quantumforge.api.common.BadRequestException
import com.quantumforge.api.common.NotFoundException
import com.quantumforge.api.common.Page
import com.quantumforge.api.common.PageRequest
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

data class GitImportRequest(@field:Size(max = 255) val ref: String? = null)

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Source")
class SourceController(private val snapshots: SnapshotService, private val access: AccessService) {
    @PostMapping("/repositories/{id}/snapshots", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun upload(@PathVariable id: UUID, @RequestPart("archive") archive: MultipartFile): ResponseEntity<SnapshotImportResult> {
        if (archive.isEmpty) throw BadRequestException("empty_upload", "The uploaded archive is empty")
        val ctx = access.requireForRepository(id, Permission.SOURCE_IMPORT)
        val result = archive.inputStream.use { snapshots.importArchive(ctx, id, archive.originalFilename, it) }
        return ResponseEntity.status(if (result.created) HttpStatus.CREATED else HttpStatus.OK).body(result)
    }

    @PostMapping("/repositories/{id}/git-imports")
    fun importGit(@PathVariable id: UUID, @Valid @RequestBody(required = false) body: GitImportRequest?): ResponseEntity<SnapshotImportResult> {
        val ctx = access.requireForRepository(id, Permission.SOURCE_IMPORT)
        val result = snapshots.importGit(ctx, id, body?.ref?.trim())
        return ResponseEntity.status(if (result.created) HttpStatus.CREATED else HttpStatus.OK).body(result)
    }

    @GetMapping("/repositories/{id}/snapshots")
    fun listForRepository(@PathVariable id: UUID): List<Snapshot> =
        snapshots.listForRepository(access.requireForRepository(id, Permission.PROJECT_READ), id)

    /** Revisions are the immutable snapshots imported into any repository of the project. */
    @GetMapping("/projects/{id}/revisions")
    fun revisions(@PathVariable id: UUID, @RequestParam(required = false) page: Int?, @RequestParam(required = false) size: Int?): Page<Snapshot> =
        snapshots.listForProject(access.requireForProject(id, Permission.PROJECT_READ), id, PageRequest.of(page, size))

    /** Files of a snapshot; defaults to the project's most recent snapshot. */
    @GetMapping("/projects/{id}/files")
    fun projectFiles(
        @PathVariable id: UUID,
        @RequestParam(required = false) snapshotId: UUID?,
        @RequestParam(required = false) prefix: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?,
    ): Page<SourceFile> {
        val ctx = access.requireForProject(id, Permission.PROJECT_READ)
        val snapshot = snapshotId?.let { snapshots.get(ctx, it) } ?: snapshots.latestForProject(ctx, id)
            ?: throw NotFoundException("snapshot", null)
        return snapshots.files(ctx, snapshot.id, prefix, PageRequest.of(page, size))
    }

    @GetMapping("/snapshots/{id}")
    fun snapshot(@PathVariable id: UUID): Snapshot = snapshots.get(access.requireForSnapshot(id, Permission.PROJECT_READ), id)

    @GetMapping("/snapshots/{id}/files")
    fun files(
        @PathVariable id: UUID,
        @RequestParam(required = false) prefix: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?,
    ): Page<SourceFile> = snapshots.files(access.requireForSnapshot(id, Permission.PROJECT_READ), id, prefix, PageRequest.of(page, size))

    @GetMapping("/snapshots/{id}/languages")
    fun languages(@PathVariable id: UUID): List<LanguageStat> = snapshots.languages(access.requireForSnapshot(id, Permission.PROJECT_READ), id)

    @GetMapping("/snapshots/{id}/content")
    fun content(@PathVariable id: UUID, @RequestParam path: String): FileContent =
        snapshots.fileContent(access.requireForSnapshot(id, Permission.PROJECT_READ), id, path)
}
