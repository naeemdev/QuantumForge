package com.quantumforge.api.integration

import com.quantumforge.api.support.Archives
import com.quantumforge.api.support.IntegrationTest
import com.quantumforge.api.support.elements
import com.quantumforge.api.support.text
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SourceIngestionTest : IntegrationTest() {
    private fun setup(): Pair<String, String> {
        val token = api.login("src-${System.nanoTime()}@source.test")
        val org = api.createOrganization(token)
        val project = api.createProject(token, org)
        return token to api.createUploadRepository(token, project)
    }

    @Test
    fun `hostile archives are rejected with a specific error`() {
        val (token, repo) = setup()
        fun upload(entries: List<Archives.Entry>) =
            api.upload("/api/v1/repositories/$repo/snapshots", token, Archives.tarGz(Files.createTempFile("bad", ".tar.gz"), entries))

        assertEquals("path_traversal", upload(listOf(Archives.file("../../etc/cron.d/x", "boom"))).errorCode)
        assertEquals("absolute_path", upload(listOf(Archives.file("/etc/passwd", "boom"))).errorCode)
        assertEquals("symlink_rejected", upload(listOf(Archives.file("a.txt", "a"), Archives.SymlinkEntry("link", "/etc/shadow"))).errorCode)
        assertEquals("symlink_rejected", upload(listOf(Archives.file("a.txt", "a"), Archives.HardlinkEntry("hard", "/etc/shadow"))).errorCode)
        assertEquals("duplicate_entry", upload(listOf(Archives.file("a.txt", "1"), Archives.file("./a.txt", "2"))).errorCode)

        val notAnArchive = Files.writeString(Files.createTempFile("plain", ".zip"), "just text")
        assertEquals("unsupported_archive", api.upload("/api/v1/repositories/$repo/snapshots", token, notAnArchive).errorCode)
        // Nothing was stored for any of these.
        assertEquals(0, api.get("/api/v1/repositories/$repo/snapshots", token).json.size())
    }

    @Test
    fun `zip and tar uploads of the same tree produce the same snapshot`() {
        val (token, repo) = setup()
        val files = listOf(Archives.file("src/b.ts", "export const b = 2;\n"), Archives.file("src/a.ts", "export const a = 1;\n"), Archives.file("README.md", "# x\n"))
        val tar = api.upload("/api/v1/repositories/$repo/snapshots", token, Archives.tarGz(Files.createTempFile("t", ".tar.gz"), files))
        val zip = api.upload("/api/v1/repositories/$repo/snapshots", token, Archives.zip(Files.createTempFile("z", ".zip"), files.reversed()))
        assertEquals(201, tar.status, tar.body)
        assertEquals(200, zip.status, zip.body)
        assertEquals(tar.json.path("snapshot").path("contentDigest").text(), zip.json.path("snapshot").path("contentDigest").text())

        val snapshotId = tar.json.path("snapshot").path("id").text()
        val content = api.get("/api/v1/snapshots/$snapshotId/content?path=src/a.ts", token)
        assertEquals("export const a = 1;\n", content.field("content"))
        assertEquals("TypeScript", content.field("language"))
        val filtered = api.get("/api/v1/snapshots/$snapshotId/files?prefix=src", token).json.path("items").elements().map { it.path("path").text() }
        assertEquals(listOf("src/a.ts", "src/b.ts"), filtered)
        assertEquals(404, api.get("/api/v1/snapshots/$snapshotId/content?path=../../etc/passwd", token).status)
    }

    @Test
    fun `binary files are indexed but not displayed and git metadata is skipped`() {
        val (token, repo) = setup()
        val entries = listOf(
            Archives.FileEntry("logo.png", byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0, 0, 0, 1)),
            Archives.file("main.ts", "console.log(1)\n"),
            Archives.file(".git/config", "[core]\n"),
        )
        val r = api.upload("/api/v1/repositories/$repo/snapshots", token, Archives.tarGz(Files.createTempFile("b", ".tar.gz"), entries))
        assertEquals(201, r.status, r.body)
        assertTrue(r.json.path("skipped").elements().any { it.path("path").text() == ".git/config" })
        val id = r.json.path("snapshot").path("id").text()
        val files = api.get("/api/v1/snapshots/$id/files", token).json.path("items").elements()
        assertEquals(listOf("logo.png", "main.ts"), files.map { it.path("path").text() })
        assertEquals(true, files[0].path("isBinary").asBoolean())
        assertEquals("binary_file", api.get("/api/v1/snapshots/$id/content?path=logo.png", token).errorCode)
    }

    @Test
    fun `git repositories only accept https urls on allowed hosts`() {
        val token = api.login("git-${System.nanoTime()}@source.test")
        val org = api.createOrganization(token)
        val project = api.createProject(token, org)
        fun register(url: String) = api.post("/api/v1/projects/$project/repositories", token, mapOf("name" to "r${System.nanoTime()}", "sourceType" to "GIT", "remoteUrl" to url))
        assertEquals(400, register("http://github.com/a/b").status)
        assertEquals(400, register("https://user:pass@github.com/a/b").status)
        assertEquals("host_not_allowed", register("https://169.254.169.254/latest/meta-data").errorCode)
        assertEquals(400, register("ext::sh -c touch% /tmp/pwned").status)
        assertEquals(400, register("file:///etc").status)
        assertEquals(201, register("https://github.com/example/project.git").status)
        assertEquals(400, api.post("/api/v1/projects/$project/repositories", token,
            mapOf("name" to "branchy", "sourceType" to "GIT", "remoteUrl" to "https://github.com/a/b", "defaultBranch" to "--upload-pack=evil")).status)
    }
}
