package com.quantumforge.api.source

import com.quantumforge.api.common.BadRequestException
import com.quantumforge.api.common.Hashing
import com.quantumforge.api.common.PayloadTooLargeException
import com.quantumforge.api.config.QuantumForgeProperties
import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipParameters
import org.springframework.stereotype.Component
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest

/** One regular file accepted into a snapshot. */
data class IngestedFile(
    val path: String,
    val sizeBytes: Long,
    val sha256: String,
    val isBinary: Boolean,
    val lineCount: Int?,
    val language: String?,
)

/**
 * The validated, canonicalised result of an ingestion. [canonicalArchive] is a
 * deterministic tar.gz (sorted paths, fixed mtimes and modes) so that the same
 * tree always produces the same [contentDigest] regardless of how it was packed.
 */
data class IngestionResult(
    val files: List<IngestedFile>,
    val totalBytes: Long,
    val canonicalArchive: Path,
    val contentDigest: String,
    val skipped: List<SkippedEntry>,
)

data class SkippedEntry(val path: String, val reason: String)

enum class ArchiveFormat { ZIP, TAR_GZ }

/**
 * Validates untrusted source archives entry by entry. Nothing from the archive is
 * ever written to disk under its own name: entries are buffered into a temporary
 * staging area keyed by index, then re-packed. That removes path traversal and
 * symlink-follow attacks by construction; the checks below reject such archives
 * explicitly so the user gets a clear error.
 */
@Component
class ArchiveIngestor(private val properties: QuantumForgeProperties, private val languages: LanguageDetector) {
    private val limits get() = properties.ingestion

    fun detectFormat(filename: String?, head: ByteArray): ArchiveFormat {
        if (head.size >= 4 && head[0] == 0x50.toByte() && head[1] == 0x4B.toByte() && head[2] == 0x03.toByte() && head[3] == 0x04.toByte()) {
            return ArchiveFormat.ZIP
        }
        if (head.size >= 2 && head[0] == 0x1F.toByte() && head[1] == 0x8B.toByte()) return ArchiveFormat.TAR_GZ
        throw BadRequestException(
            "unsupported_archive",
            "Unsupported archive format${filename?.let { " for '$it'" } ?: ""}. Upload a .zip or .tar.gz file.",
        )
    }

    fun ingestArchive(archive: Path, filename: String?, workDir: Path): IngestionResult {
        val archiveSize = Files.size(archive)
        if (archiveSize > limits.maxArchiveBytes) {
            throw PayloadTooLargeException("Archive is larger than ${limits.maxArchiveBytes} bytes")
        }
        val head = Files.newInputStream(archive).use { it.readNBytes(4) }
        val format = detectFormat(filename, head)
        val staging = Files.createDirectories(workDir.resolve("staging"))
        val accepted = mutableListOf<Staged>()
        val skipped = mutableListOf<SkippedEntry>()

        BufferedInputStream(Files.newInputStream(archive)).use { raw ->
            val counting = CountingInputStream(raw)
            val stream: ArchiveInputStream<out ArchiveEntry> = when (format) {
                // Zip entries are read in stream order; the central directory is not
                // trusted, so a crafted directory cannot hide entries from validation.
                ArchiveFormat.ZIP -> ZipArchiveInputStream(counting, "UTF-8", false, true)
                ArchiveFormat.TAR_GZ -> TarArchiveInputStream(GzipCompressorInputStream(counting, false))
            }
            stream.use { readEntries(it, counting, archiveSize, staging, accepted, skipped) }
        }
        return finish(stripSingleRoot(accepted), skipped, workDir)
    }

    /**
     * Ingests a directory tree (a Git working copy). Symlinks are skipped, never
     * followed, and `.git` metadata is excluded.
     */
    fun ingestDirectory(root: Path, workDir: Path): IngestionResult {
        val staging = Files.createDirectories(workDir.resolve("staging"))
        val accepted = mutableListOf<Staged>()
        val skipped = mutableListOf<SkippedEntry>()
        val normalizedRoot = root.toAbsolutePath().normalize()
        var total = 0L
        Files.walk(normalizedRoot).use { paths ->
            for (path in paths.sorted().iterator()) {
                if (path == normalizedRoot) continue
                val relative = normalizedRoot.relativize(path).toString().replace('\\', '/')
                if (relative == ".git" || relative.startsWith(".git/")) continue
                if (Files.isSymbolicLink(path)) {
                    // Never followed: a link could point outside the checkout.
                    skipped += SkippedEntry(relative, "symbolic links are not imported")
                    continue
                }
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) continue
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw BadRequestException("special_file_rejected", "Special files are not allowed: $relative")
                }
                val clean = normalizeEntryName(relative)
                checkCount(accepted.size + 1)
                val size = Files.size(path)
                if (size > limits.maxFileBytes) {
                    skipped += SkippedEntry(clean, "file larger than ${limits.maxFileBytes} bytes")
                    continue
                }
                total += size
                checkTotal(total)
                val staged = staging.resolve(accepted.size.toString())
                Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input -> accepted += stage(clean, input, staged, size) }
            }
        }
        return finish(accepted, skipped, workDir)
    }

    private fun readEntries(
        stream: ArchiveInputStream<out ArchiveEntry>,
        counting: CountingInputStream,
        archiveSize: Long,
        staging: Path,
        accepted: MutableList<Staged>,
        skipped: MutableList<SkippedEntry>,
    ) {
        var total = 0L
        val seen = HashSet<String>()
        var entries = 0
        while (true) {
            val entry = stream.nextEntry ?: break
            entries++
            checkCount(entries)
            val name = normalizeEntryName(entry.name)
            rejectSpecial(entry, name)
            if (entry.isDirectory) continue
            if (name.startsWith("__MACOSX/") || name.endsWith("/.DS_Store") || name == ".DS_Store") continue
            if (name == ".git" || name.startsWith(".git/") || name.contains("/.git/")) {
                skipped += SkippedEntry(name, "git metadata is not imported")
                continue
            }
            if (!seen.add(name.lowercase())) {
                throw BadRequestException("duplicate_entry", "Archive contains '$name' more than once", mapOf("path" to name))
            }
            if (!stream.canReadEntryData(entry)) {
                throw BadRequestException("unsupported_entry", "Entry '$name' uses an unsupported compression method or encryption")
            }
            val staged = staging.resolve(accepted.size.toString())
            val result = stageBounded(name, stream, staged)
            if (result == null) {
                skipped += SkippedEntry(name, "file larger than ${limits.maxFileBytes} bytes")
                continue
            }
            total += result.sizeBytes
            checkTotal(total)
            // Compression-ratio guard against zip bombs, measured on real bytes read.
            val compressedSoFar = maxOf(counting.count, 1L)
            if (total > 1024 * 1024 && total / compressedSoFar > limits.maxCompressionRatio) {
                throw BadRequestException("suspicious_compression", "Archive expands more than ${limits.maxCompressionRatio}x; refusing to extract")
            }
            accepted += result
        }
        if (counting.count > archiveSize + 1) {
            throw BadRequestException("corrupt_archive", "Archive length mismatch")
        }
    }

    private fun rejectSpecial(entry: ArchiveEntry, name: String) {
        when (entry) {
            is TarArchiveEntry -> {
                if (entry.isSymbolicLink || entry.isLink) {
                    throw BadRequestException("symlink_rejected", "Links are not allowed in archives: $name", mapOf("path" to name))
                }
                if (entry.isCharacterDevice || entry.isBlockDevice || entry.isFIFO) {
                    throw BadRequestException("special_file_rejected", "Device and FIFO entries are not allowed: $name")
                }
                if (!entry.isFile && !entry.isDirectory && !entry.isPaxHeader && !entry.isGlobalPaxHeader) {
                    throw BadRequestException("special_file_rejected", "Unsupported tar entry type for $name")
                }
            }
            is ZipArchiveEntry -> {
                if (entry.isUnixSymlink) {
                    throw BadRequestException("symlink_rejected", "Links are not allowed in archives: $name", mapOf("path" to name))
                }
            }
        }
    }

    /**
     * Normalises an archive entry name to a safe relative path, or rejects it.
     * Rejects absolute paths, drive letters, `..` segments, NUL and control characters.
     */
    fun normalizeEntryName(raw: String): String {
        if (raw.isEmpty()) throw BadRequestException("invalid_path", "Archive contains an entry with an empty name")
        if (raw.length > limits.maxPathLength) throw BadRequestException("invalid_path", "Entry name exceeds ${limits.maxPathLength} characters")
        if (raw.any { it == '\u0000' || it.code < 0x20 }) {
            throw BadRequestException("invalid_path", "Entry name contains control characters")
        }
        val unified = raw.replace('\\', '/')
        if (unified.startsWith("/") || Regex("^[A-Za-z]:").containsMatchIn(unified)) {
            throw BadRequestException("absolute_path", "Absolute paths are not allowed: $raw", mapOf("path" to raw))
        }
        val segments = unified.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.any { it == ".." }) {
            throw BadRequestException("path_traversal", "Path traversal is not allowed: $raw", mapOf("path" to raw))
        }
        if (segments.isEmpty()) throw BadRequestException("invalid_path", "Invalid entry name: $raw")
        return segments.joinToString("/")
    }

    private fun checkCount(count: Int) {
        if (count > limits.maxEntries) throw PayloadTooLargeException("Archive has more than ${limits.maxEntries} entries")
    }

    private fun checkTotal(total: Long) {
        if (total > limits.maxUncompressedBytes) {
            throw PayloadTooLargeException("Uncompressed content exceeds ${limits.maxUncompressedBytes} bytes")
        }
    }

    private class Staged(val file: IngestedFile, val stagedPath: Path) {
        val sizeBytes get() = file.sizeBytes
    }

    /** Copies at most maxFileBytes; returns null (and drains the entry) if the file is larger. */
    private fun stageBounded(name: String, input: InputStream, target: Path): Staged? {
        val limit = limits.maxFileBytes
        val digest = MessageDigest.getInstance("SHA-256")
        val sniffer = ContentSniffer()
        var size = 0L
        var oversized = false
        Files.newOutputStream(target).use { out ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                size += n
                if (size > limit) {
                    oversized = true
                    continue // keep draining so the stream stays aligned; bytes are discarded
                }
                digest.update(buffer, 0, n)
                sniffer.update(buffer, n)
                out.write(buffer, 0, n)
            }
        }
        if (oversized) {
            Files.deleteIfExists(target)
            return null
        }
        return Staged(fileOf(name, size, digest, sniffer), target)
    }

    private fun stage(name: String, input: InputStream, target: Path, expectedSize: Long): Staged {
        val staged = stageBounded(name, input, target)
            ?: throw PayloadTooLargeException("File grew during import: $name")
        check(staged.sizeBytes == expectedSize) { "File changed during import: $name" }
        return staged
    }

    private fun fileOf(name: String, size: Long, digest: MessageDigest, sniffer: ContentSniffer): IngestedFile {
        val binary = sniffer.isBinary
        return IngestedFile(
            path = name,
            sizeBytes = size,
            sha256 = Hashing.toHex(digest.digest()),
            isBinary = binary,
            lineCount = if (binary) null else sniffer.lineCount(size),
            language = if (binary) null else languages.detect(name),
        )
    }

    /**
     * Archives downloaded from code hosts wrap everything in one top-level folder
     * ("project-main/..."). Strip it so snapshot paths match the repository root.
     */
    private fun stripSingleRoot(files: List<Staged>): List<Staged> {
        val roots = files.map { it.file.path.substringBefore('/', missingDelimiterValue = "") }.toSet()
        if (roots.size != 1 || roots.first().isEmpty()) return files
        val prefix = roots.first() + "/"
        return files.map { Staged(it.file.copy(path = it.file.path.removePrefix(prefix)), it.stagedPath) }
    }

    private fun finish(accepted: List<Staged>, skipped: List<SkippedEntry>, workDir: Path): IngestionResult {
        if (accepted.isEmpty()) throw BadRequestException("empty_archive", "The source contains no importable files")
        val sorted = accepted.sortedBy { it.file.path }
        val archive = workDir.resolve("canonical.tar.gz")
        val digest = MessageDigest.getInstance("SHA-256")
        DigestOutputStream(Files.newOutputStream(archive), digest).use { fileOut ->
            val gzipParams = GzipParameters().apply { modificationTime = 0 }
            GzipCompressorOutputStream(fileOut, gzipParams).use { gz ->
                TarArchiveOutputStream(gz).use { tar ->
                    tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                    tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
                    for (staged in sorted) {
                        val entry = TarArchiveEntry(staged.file.path)
                        entry.size = staged.file.sizeBytes
                        entry.mode = 0b110_100_100 // 0644: snapshots never carry executable bits
                        entry.setModTime(0L)
                        entry.userId = 0
                        entry.groupId = 0
                        entry.userName = ""
                        entry.groupName = ""
                        tar.putArchiveEntry(entry)
                        Files.newInputStream(staged.stagedPath).use { it.transferTo(tar) }
                        tar.closeArchiveEntry()
                    }
                }
            }
        }
        return IngestionResult(
            files = sorted.map { it.file },
            totalBytes = sorted.sumOf { it.file.sizeBytes },
            canonicalArchive = archive,
            contentDigest = Hashing.toHex(digest.digest()),
            skipped = skipped,
        )
    }
}

private class CountingInputStream(private val delegate: InputStream) : InputStream() {
    var count = 0L
        private set

    override fun read(): Int = delegate.read().also { if (it >= 0) count++ }

    override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len).also { if (it > 0) count += it }

    override fun close() = delegate.close()
}

private class DigestOutputStream(private val delegate: OutputStream, private val digest: MessageDigest) : OutputStream() {
    override fun write(b: Int) {
        digest.update(b.toByte())
        delegate.write(b)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        digest.update(b, off, len)
        delegate.write(b, off, len)
    }

    override fun flush() = delegate.flush()

    override fun close() = delegate.close()
}

/**
 * Classifies content as binary when it contains NUL bytes in the first 8 KiB (the
 * same heuristic Git uses) and counts newlines for text files.
 */
private class ContentSniffer {
    private var inspected = 0
    var isBinary = false
        private set
    private var newlines = 0
    private var lastByte: Byte = '\n'.code.toByte()

    fun update(buffer: ByteArray, length: Int) {
        for (i in 0 until length) {
            val b = buffer[i]
            if (inspected < 8192) {
                if (b == 0.toByte()) isBinary = true
                inspected++
            }
            if (b == '\n'.code.toByte()) newlines++
        }
        if (length > 0) lastByte = buffer[length - 1]
    }

    fun lineCount(size: Long): Int = if (size == 0L) 0 else newlines + if (lastByte == '\n'.code.toByte()) 0 else 1
}
