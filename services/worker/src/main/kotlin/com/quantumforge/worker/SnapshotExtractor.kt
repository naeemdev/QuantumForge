package com.quantumforge.worker

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Unpacks a canonical snapshot archive. The control plane already validated it,
 * but the worker does not trust its input either: only regular files with
 * normalised relative names are written, and the archive digest must match the
 * one the lease promised.
 */
object SnapshotExtractor {
    fun verifyDigest(archive: Path, expectedSha256: String) {
        val md = MessageDigest.getInstance("SHA-256")
        DigestInputStream(Files.newInputStream(archive), md).use { it.transferTo(java.io.OutputStream.nullOutputStream()) }
        val actual = HexFormat.of().formatHex(md.digest())
        check(actual == expectedSha256) { "Snapshot digest mismatch: expected $expectedSha256, got $actual" }
    }

    fun extract(archive: Path, target: Path, maxFiles: Int = 50_000, maxBytes: Long = 512L * 1024 * 1024): Int {
        val root = Files.createDirectories(target).toAbsolutePath().normalize()
        var files = 0
        var bytes = 0L
        TarArchiveInputStream(GzipCompressorInputStream(Files.newInputStream(archive).buffered())).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                if (entry.isPaxHeader || entry.isGlobalPaxHeader || entry.isDirectory) continue
                check(entry.isFile) { "Unexpected non-regular entry ${entry.name}" }
                val name = entry.name
                check(name.isNotEmpty() && !name.startsWith("/") && name.split('/').none { it == ".." || it.isEmpty() }) { "Unsafe entry name $name" }
                val out = root.resolve(name).normalize()
                check(out.startsWith(root)) { "Entry escapes extraction root: $name" }
                Files.createDirectories(out.parent)
                Files.newOutputStream(out, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { tar.transferTo(it) }
                files++
                bytes += entry.size
                check(files <= maxFiles && bytes <= maxBytes) { "Snapshot exceeds worker extraction limits" }
            }
        }
        return files
    }
}
