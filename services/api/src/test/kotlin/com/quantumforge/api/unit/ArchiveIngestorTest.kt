package com.quantumforge.api.unit

import com.quantumforge.api.common.ApiException
import com.quantumforge.api.config.QuantumForgeProperties
import com.quantumforge.api.source.ArchiveIngestor
import com.quantumforge.api.source.LanguageDetector
import com.quantumforge.api.support.Archives
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ArchiveIngestorTest {
    @TempDir
    lateinit var tmp: Path

    private fun ingestor(ingestion: QuantumForgeProperties.Ingestion = QuantumForgeProperties.Ingestion()) =
        ArchiveIngestor(QuantumForgeProperties(ingestion = ingestion), LanguageDetector())

    private fun work() = Files.createTempDirectory(tmp, "w")

    private fun code(block: () -> Unit): String = assertFailsWith<ApiException> { block() }.code

    @Test
    fun `normalises safe names and rejects unsafe ones`() {
        val i = ingestor()
        assertEquals("a/b/c.ts", i.normalizeEntryName("./a//b/./c.ts"))
        assertEquals("a/b", i.normalizeEntryName("a\\b"))
        assertEquals("path_traversal", code { i.normalizeEntryName("a/../../b") })
        assertEquals("absolute_path", code { i.normalizeEntryName("/etc/passwd") })
        assertEquals("absolute_path", code { i.normalizeEntryName("C:/Windows/x") })
        assertEquals("invalid_path", code { i.normalizeEntryName("a\u0000b") })
        assertEquals("invalid_path", code { i.normalizeEntryName("a\nb") })
    }

    @Test
    fun `refuses highly compressible archives`() {
        val bomb = Archives.tarGz(tmp.resolve("bomb.tar.gz"), listOf(Archives.FileEntry("zeros.txt", ByteArray(8 * 1024 * 1024))))
        val i = ingestor(QuantumForgeProperties.Ingestion(maxCompressionRatio = 50, maxFileBytes = 16L * 1024 * 1024))
        assertEquals("suspicious_compression", code { i.ingestArchive(bomb, "bomb.tar.gz", work()) })
    }

    @Test
    fun `enforces entry count and total size limits`() {
        val many = Archives.tarGz(tmp.resolve("many.tar.gz"), (1..11).map { Archives.file("f$it.txt", "x") })
        assertEquals("payload_too_large", code { ingestor(QuantumForgeProperties.Ingestion(maxEntries = 10)).ingestArchive(many, null, work()) })

        val big = Archives.tarGz(tmp.resolve("big.tar.gz"), (1..3).map { Archives.file("f$it.txt", "y".repeat(400)) })
        assertEquals("payload_too_large", code { ingestor(QuantumForgeProperties.Ingestion(maxUncompressedBytes = 1000)).ingestArchive(big, null, work()) })
    }

    @Test
    fun `skips oversized files instead of failing the import`() {
        val archive = Archives.tarGz(tmp.resolve("mixed.tar.gz"), listOf(Archives.file("small.ts", "ok"), Archives.file("huge.bin", "z".repeat(5000))))
        val result = ingestor(QuantumForgeProperties.Ingestion(maxFileBytes = 1000)).ingestArchive(archive, null, work())
        assertEquals(listOf("small.ts"), result.files.map { it.path })
        assertEquals("huge.bin", result.skipped.single().path)
    }

    @Test
    fun `canonical digest ignores packing order and archive format`() {
        val entries = listOf(Archives.file("b.ts", "b"), Archives.file("a/x.ts", "x"))
        val one = ingestor().ingestArchive(Archives.tarGz(tmp.resolve("1.tar.gz"), entries), null, work())
        val two = ingestor().ingestArchive(Archives.zip(tmp.resolve("2.zip"), entries.reversed()), null, work())
        assertEquals(one.contentDigest, two.contentDigest)
        val three = ingestor().ingestArchive(Archives.tarGz(tmp.resolve("3.tar.gz"), listOf(Archives.file("b.ts", "B"), Archives.file("a/x.ts", "x"))), null, work())
        assertTrue(three.contentDigest != one.contentDigest)
    }

    @Test
    fun `counts lines and classifies binaries`() {
        val archive = Archives.tarGz(tmp.resolve("c.tar.gz"), listOf(
            Archives.file("three.ts", "a\nb\nc"),
            Archives.file("two.ts", "a\nb\n"),
            Archives.file("empty.ts", ""),
            Archives.FileEntry("blob.dat", byteArrayOf(1, 0, 2)),
        ))
        val byPath = ingestor().ingestArchive(archive, null, work()).files.associateBy { it.path }
        assertEquals(3, byPath.getValue("three.ts").lineCount)
        assertEquals(2, byPath.getValue("two.ts").lineCount)
        assertEquals(0, byPath.getValue("empty.ts").lineCount)
        assertEquals(true, byPath.getValue("blob.dat").isBinary)
        assertEquals(null, byPath.getValue("blob.dat").language)
        assertEquals("TypeScript", byPath.getValue("two.ts").language)
    }

    @Test
    fun `directory ingestion skips symlinks without following them`() {
        val repo = Files.createDirectories(tmp.resolve("repo"))
        Files.writeString(repo.resolve("main.ts"), "x")
        Files.createDirectories(repo.resolve(".git"))
        Files.writeString(repo.resolve(".git/HEAD"), "ref")
        Files.createSymbolicLink(repo.resolve("escape"), Path.of("/etc/passwd"))
        val result = ingestor().ingestDirectory(repo, work())
        assertEquals(listOf("main.ts"), result.files.map { it.path })
        assertEquals("escape", result.skipped.single().path)
    }
}
