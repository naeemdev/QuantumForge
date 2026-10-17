package com.quantumforge.api.source

import com.quantumforge.api.access.OrgAccess
import com.quantumforge.api.audit.AuditService
import com.quantumforge.api.common.BadRequestException
import com.quantumforge.api.common.ConflictException
import com.quantumforge.api.common.Ids
import com.quantumforge.api.common.NotFoundException
import com.quantumforge.api.common.Page
import com.quantumforge.api.common.PageRequest
import com.quantumforge.api.common.PayloadTooLargeException
import com.quantumforge.api.common.instant
import com.quantumforge.api.common.intOrNull
import com.quantumforge.api.common.sql
import com.quantumforge.api.common.uuid
import com.quantumforge.api.config.QuantumForgeProperties
import com.quantumforge.api.projects.CodeRepository
import com.quantumforge.api.projects.ProjectService
import com.quantumforge.api.storage.BlobKeys
import com.quantumforge.api.storage.BlobStore
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.sql.ResultSet
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class Snapshot(
    val id: UUID,
    val repositoryId: UUID,
    val origin: String,
    val gitRef: String?,
    val gitCommit: String?,
    val originalFilename: String?,
    val contentDigest: String,
    val archiveSize: Long,
    val fileCount: Int,
    val totalBytes: Long,
    val createdAt: Instant,
    val createdBy: UUID,
)

data class SnapshotImportResult(val snapshot: Snapshot, val created: Boolean, val skipped: List<SkippedEntry>)

data class SourceFile(val path: String, val sizeBytes: Long, val sha256: String, val language: String?, val isBinary: Boolean, val lineCount: Int?)

data class LanguageStat(val language: String, val fileCount: Int, val lineCount: Long, val byteCount: Long)

data class FileContent(val path: String, val sizeBytes: Long, val language: String?, val content: String)

/**
 * Turns uploaded archives and Git checkouts into immutable snapshots. Heavy work
 * (validation, re-packing, upload to object storage) happens outside any
 * database transaction; only the final metadata insert is transactional.
 */
@Service
class SnapshotService(
    private val jdbc: JdbcClient,
    private val projects: ProjectService,
    private val ingestor: ArchiveIngestor,
    private val git: GitImporter,
    private val blobs: BlobStore,
    private val audit: AuditService,
    private val tx: TransactionTemplate,
    private val languages: LanguageDetector,
    private val properties: QuantumForgeProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun importArchive(ctx: OrgAccess, repositoryId: UUID, filename: String?, content: InputStream): SnapshotImportResult {
        val repo = projects.getRepository(ctx, repositoryId)
        if (repo.sourceType != "UPLOAD") throw ConflictException("wrong_source_type", "This repository imports from Git; use the Git import endpoint")
        return withWorkDir { dir ->
            val upload = dir.resolve("upload.bin")
            Files.newOutputStream(upload).use { out -> copyBounded(content, out, properties.ingestion.maxArchiveBytes) }
            val result = ingestor.ingestArchive(upload, filename, dir)
            persist(ctx, repo, result, "UPLOAD", null, null, safeFilename(filename))
        }
    }

    fun importGit(ctx: OrgAccess, repositoryId: UUID, ref: String?): SnapshotImportResult {
        val repo = projects.getRepository(ctx, repositoryId)
        if (repo.sourceType != "GIT" || repo.remoteUrl == null) throw ConflictException("wrong_source_type", "This repository is upload-only")
        val effectiveRef = ref?.takeIf { it.isNotBlank() } ?: repo.defaultBranch
        return withWorkDir { dir ->
            val checkout = git.clone(repo.remoteUrl, effectiveRef, dir)
            val result = ingestor.ingestDirectory(checkout.directory, dir)
            persist(ctx, repo, result, "GIT", checkout.ref, checkout.commit, null)
        }
    }

    private fun persist(
        ctx: OrgAccess,
        repo: CodeRepository,
        result: IngestionResult,
        origin: String,
        gitRef: String?,
        gitCommit: String?,
        filename: String?,
    ): SnapshotImportResult {
        val quota = jdbc.sql("SELECT max_snapshot_bytes FROM tenants WHERE id = :id").param("id", ctx.tenantId).query(Long::class.java).single()
        if (result.totalBytes > quota) throw PayloadTooLargeException("Snapshot exceeds your plan's limit of $quota bytes")

        findByDigest(ctx, repo.id, result.contentDigest)?.let { return SnapshotImportResult(it, false, result.skipped) }

        // Content-addressed key: identical trees share one blob within a tenant.
        val key = BlobKeys.snapshot(ctx.tenantId, result.contentDigest)
        val stored = if (blobs.exists(key)) null else blobs.put(key, result.canonicalArchive, "application/gzip")
        val archiveSize = stored?.sizeBytes ?: Files.size(result.canonicalArchive)

        val id = Ids.newId(clock)
        val inserted = tx.execute {
            val n = jdbc.sql(
                """
                INSERT INTO source_snapshots (id, tenant_id, repository_id, origin, git_ref, git_commit, original_filename,
                                              content_digest, archive_blob_key, archive_size, file_count, total_bytes, created_at, created_by)
                VALUES (:id, :tenant, :repo, :origin, :ref, :commit, :filename, :digest, :key, :size, :count, :total, :now, :user)
                ON CONFLICT (repository_id, content_digest) DO NOTHING
                """,
            ).param("id", id).param("tenant", ctx.tenantId).param("repo", repo.id).param("origin", origin)
                .param("ref", gitRef).param("commit", gitCommit).param("filename", filename)
                .param("digest", result.contentDigest).param("key", key).param("size", archiveSize)
                .param("count", result.files.size).param("total", result.totalBytes)
                .param("now", Instant.now(clock).sql()).param("user", ctx.userId).update()
            if (n == 0) return@execute false
            insertFiles(ctx.tenantId, id, result.files)
            insertLanguages(ctx.tenantId, id, result.files)
            audit.record(
                ctx, "snapshot.imported", "snapshot", id,
                mapOf("repositoryId" to repo.id, "origin" to origin, "digest" to result.contentDigest, "files" to result.files.size, "commit" to gitCommit),
            )
            true
        } ?: false

        if (!inserted) {
            // Lost a race with an identical concurrent import; return the winner.
            return SnapshotImportResult(findByDigest(ctx, repo.id, result.contentDigest)!!, false, result.skipped)
        }
        log.info("Imported snapshot {} ({} files, {} bytes) into repository {}", id, result.files.size, result.totalBytes, repo.id)
        return SnapshotImportResult(get(ctx, id), true, result.skipped)
    }

    private fun insertFiles(tenantId: UUID, snapshotId: UUID, files: List<IngestedFile>) {
        files.chunked(500).forEach { chunk ->
            val values = chunk.indices.joinToString(",") { i -> "(:id$i, :tenant, :snap, :path$i, :size$i, :sha$i, :lang$i, :bin$i, :lines$i)" }
            var stmt = jdbc.sql("INSERT INTO source_files (id, tenant_id, snapshot_id, path, size_bytes, sha256, language, is_binary, line_count) VALUES $values")
                .param("tenant", tenantId).param("snap", snapshotId)
            chunk.forEachIndexed { i, f ->
                stmt = stmt.param("id$i", Ids.newId(clock)).param("path$i", f.path).param("size$i", f.sizeBytes).param("sha$i", f.sha256)
                    .param("lang$i", f.language).param("bin$i", f.isBinary).param("lines$i", f.lineCount)
            }
            stmt.update()
        }
    }

    private fun insertLanguages(tenantId: UUID, snapshotId: UUID, files: List<IngestedFile>) {
        files.filter { it.language != null }.groupBy { it.language!! }.forEach { (language, group) ->
            jdbc.sql(
                """
                INSERT INTO language_detections (tenant_id, snapshot_id, language, file_count, line_count, byte_count)
                VALUES (:tenant, :snap, :lang, :files, :lines, :bytes)
                """,
            ).param("tenant", tenantId).param("snap", snapshotId).param("lang", language).param("files", group.size)
                .param("lines", group.sumOf { (it.lineCount ?: 0).toLong() }).param("bytes", group.sumOf { it.sizeBytes }).update()
        }
    }

    fun get(ctx: OrgAccess, id: UUID): Snapshot =
        jdbc.sql(
            """
            SELECT s.* FROM source_snapshots s
            JOIN repositories r ON r.id = s.repository_id AND r.tenant_id = s.tenant_id
            JOIN projects p ON p.id = r.project_id AND p.tenant_id = r.tenant_id
            WHERE s.id = :id AND s.tenant_id = :tenant AND p.organization_id = :org
            """,
        ).param("id", id).param("tenant", ctx.tenantId).param("org", ctx.organizationId)
            .query(::map).optional().orElseThrow { NotFoundException("snapshot", id) }

    fun listForRepository(ctx: OrgAccess, repositoryId: UUID): List<Snapshot> {
        projects.getRepository(ctx, repositoryId)
        return jdbc.sql("SELECT * FROM source_snapshots WHERE repository_id = :repo AND tenant_id = :tenant ORDER BY created_at DESC LIMIT 200")
            .param("repo", repositoryId).param("tenant", ctx.tenantId).query(::map).list()
    }

    fun listForProject(ctx: OrgAccess, projectId: UUID, page: PageRequest): Page<Snapshot> {
        projects.get(ctx, projectId)
        val items = jdbc.sql(
            """
            SELECT s.* FROM source_snapshots s JOIN repositories r ON r.id = s.repository_id AND r.tenant_id = s.tenant_id
            WHERE r.project_id = :project AND s.tenant_id = :tenant ORDER BY s.created_at DESC, s.id LIMIT :limit OFFSET :offset
            """,
        ).param("project", projectId).param("tenant", ctx.tenantId).param("limit", page.size).param("offset", page.offset).query(::map).list()
        val total = jdbc.sql(
            "SELECT count(*) FROM source_snapshots s JOIN repositories r ON r.id = s.repository_id AND r.tenant_id = s.tenant_id WHERE r.project_id = :project AND s.tenant_id = :tenant",
        ).param("project", projectId).param("tenant", ctx.tenantId).query(Long::class.java).single()
        return Page.of(items, page, total)
    }

    fun latestForProject(ctx: OrgAccess, projectId: UUID): Snapshot? = listForProject(ctx, projectId, PageRequest(0, 1)).items.firstOrNull()

    fun files(ctx: OrgAccess, snapshotId: UUID, prefix: String?, page: PageRequest): Page<SourceFile> {
        get(ctx, snapshotId)
        val like = prefix?.trim()?.trim('/')?.takeIf { it.isNotEmpty() }?.let { it.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "/%" }
        val filter = if (like != null) "AND path LIKE :prefix" else ""
        val items = jdbc.sql("SELECT * FROM source_files WHERE snapshot_id = :snap AND tenant_id = :tenant $filter ORDER BY path LIMIT :limit OFFSET :offset")
            .param("snap", snapshotId).param("tenant", ctx.tenantId).apply { if (like != null) param("prefix", like) }
            .param("limit", page.size).param("offset", page.offset)
            .query { rs, _ -> SourceFile(rs.getString("path"), rs.getLong("size_bytes"), rs.getString("sha256"), rs.getString("language"), rs.getBoolean("is_binary"), rs.intOrNull("line_count")) }
            .list()
        val total = jdbc.sql("SELECT count(*) FROM source_files WHERE snapshot_id = :snap AND tenant_id = :tenant $filter")
            .param("snap", snapshotId).param("tenant", ctx.tenantId).apply { if (like != null) param("prefix", like) }
            .query(Long::class.java).single()
        return Page.of(items, page, total)
    }

    fun languages(ctx: OrgAccess, snapshotId: UUID): List<LanguageStat> {
        get(ctx, snapshotId)
        return jdbc.sql("SELECT * FROM language_detections WHERE snapshot_id = :snap AND tenant_id = :tenant ORDER BY line_count DESC")
            .param("snap", snapshotId).param("tenant", ctx.tenantId)
            .query { rs, _ -> LanguageStat(rs.getString("language"), rs.getInt("file_count"), rs.getLong("line_count"), rs.getLong("byte_count")) }
            .list()
    }

    /** Reads one text file out of the stored canonical archive. */
    fun fileContent(ctx: OrgAccess, snapshotId: UUID, path: String): FileContent {
        val snapshot = get(ctx, snapshotId)
        val file = jdbc.sql("SELECT * FROM source_files WHERE snapshot_id = :snap AND tenant_id = :tenant AND path = :path")
            .param("snap", snapshotId).param("tenant", ctx.tenantId).param("path", path)
            .query { rs, _ -> SourceFile(rs.getString("path"), rs.getLong("size_bytes"), rs.getString("sha256"), rs.getString("language"), rs.getBoolean("is_binary"), rs.intOrNull("line_count")) }
            .optional().orElseThrow { NotFoundException("file", path) }
        if (file.isBinary) throw BadRequestException("binary_file", "Binary files cannot be displayed")
        if (file.sizeBytes > MAX_VIEW_BYTES) throw PayloadTooLargeException("File is too large to display")
        val key = jdbc.sql("SELECT archive_blob_key FROM source_snapshots WHERE id = :id").param("id", snapshot.id).query(String::class.java).single()
        TarArchiveInputStream(GzipCompressorInputStream(blobs.open(key).buffered())).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                if (entry.name == path) {
                    val bytes = tar.readNBytes(MAX_VIEW_BYTES.toInt())
                    return FileContent(path, file.sizeBytes, file.language ?: languages.detect(path), bytes.toString(Charsets.UTF_8))
                }
            }
        }
        throw IllegalStateException("File $path is indexed but missing from archive $key")
    }

    fun archiveKey(tenantId: UUID, snapshotId: UUID): String =
        jdbc.sql("SELECT archive_blob_key FROM source_snapshots WHERE id = :id AND tenant_id = :tenant")
            .param("id", snapshotId).param("tenant", tenantId).query(String::class.java).single()

    private fun findByDigest(ctx: OrgAccess, repositoryId: UUID, digest: String): Snapshot? =
        jdbc.sql("SELECT * FROM source_snapshots WHERE repository_id = :repo AND tenant_id = :tenant AND content_digest = :digest")
            .param("repo", repositoryId).param("tenant", ctx.tenantId).param("digest", digest).query(::map).optional().orElse(null)

    private fun copyBounded(input: InputStream, out: java.io.OutputStream, limit: Long) {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > limit) throw PayloadTooLargeException("Archive is larger than $limit bytes")
            out.write(buffer, 0, n)
        }
    }

    private fun <T> withWorkDir(block: (Path) -> T): T {
        val dir = Files.createTempDirectory("qf-ingest-")
        try {
            return block(dir)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun safeFilename(name: String?): String? =
        name?.substringAfterLast('/')?.substringAfterLast('\\')?.filter { it.isLetterOrDigit() || it in "._- " }?.take(200)?.takeIf { it.isNotBlank() }

    private fun map(rs: ResultSet, @Suppress("UNUSED_PARAMETER") i: Int) = Snapshot(
        id = rs.uuid("id"),
        repositoryId = rs.uuid("repository_id"),
        origin = rs.getString("origin"),
        gitRef = rs.getString("git_ref"),
        gitCommit = rs.getString("git_commit"),
        originalFilename = rs.getString("original_filename"),
        contentDigest = rs.getString("content_digest"),
        archiveSize = rs.getLong("archive_size"),
        fileCount = rs.getInt("file_count"),
        totalBytes = rs.getLong("total_bytes"),
        createdAt = rs.instant("created_at"),
        createdBy = rs.uuid("created_by"),
    )

    companion object {
        const val MAX_VIEW_BYTES = 2L * 1024 * 1024
    }
}
