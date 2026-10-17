package com.quantumforge.api.support

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import java.nio.file.Files
import java.nio.file.Path

/** Builds archives (including hostile ones) for ingestion tests. */
object Archives {
    sealed interface Entry { val name: String }
    data class FileEntry(override val name: String, val content: ByteArray) : Entry
    data class SymlinkEntry(override val name: String, val target: String) : Entry
    data class HardlinkEntry(override val name: String, val target: String) : Entry

    fun file(name: String, content: String) = FileEntry(name, content.toByteArray())

    fun tarGz(target: Path, entries: List<Entry>): Path {
        TarArchiveOutputStream(GzipCompressorOutputStream(Files.newOutputStream(target))).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            for (e in entries) {
                when (e) {
                    is FileEntry -> {
                        val entry = TarArchiveEntry(e.name, true)
                        entry.size = e.content.size.toLong()
                        tar.putArchiveEntry(entry)
                        tar.write(e.content)
                    }
                    is SymlinkEntry -> {
                        val entry = TarArchiveEntry(e.name, TarArchiveEntry.LF_SYMLINK, true)
                        entry.linkName = e.target
                        tar.putArchiveEntry(entry)
                    }
                    is HardlinkEntry -> {
                        val entry = TarArchiveEntry(e.name, TarArchiveEntry.LF_LINK, true)
                        entry.linkName = e.target
                        tar.putArchiveEntry(entry)
                    }
                }
                tar.closeArchiveEntry()
            }
        }
        return target
    }

    fun zip(target: Path, entries: List<FileEntry>): Path {
        ZipArchiveOutputStream(Files.newOutputStream(target)).use { zip ->
            for (e in entries) {
                zip.putArchiveEntry(ZipArchiveEntry(e.name))
                zip.write(e.content)
                zip.closeArchiveEntry()
            }
        }
        return target
    }

    /** Packs a directory tree as tar.gz (regular files only). */
    fun tarGzDirectory(root: Path, target: Path): Path {
        val files = Files.walk(root).use { s -> s.filter { Files.isRegularFile(it) }.sorted().toList() }
        return tarGz(target, files.map { FileEntry(root.relativize(it).toString().replace('\\', '/'), Files.readAllBytes(it)) })
    }

    fun repoRoot(): Path = Path.of(System.getProperty("qf.repoRoot") ?: "../..").toAbsolutePath().normalize()

    fun sampleRepository(): Path = repoRoot().resolve("benchmarks/bug-corpus/sample-commerce-api")
}
