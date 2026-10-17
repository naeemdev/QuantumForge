package com.quantumforge.api.storage

import com.quantumforge.api.common.Hashing
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.DigestInputStream
import java.security.MessageDigest

/** Local-disk blob store for development, tests and single-node self-hosting. */
class FilesystemBlobStore(root: Path) : BlobStore {
    private val root: Path = root.toAbsolutePath().normalize()

    init {
        Files.createDirectories(this.root)
    }

    override fun put(key: String, source: Path, contentType: String): StoredBlob {
        val target = resolve(key)
        Files.createDirectories(target.parent)
        val digest = MessageDigest.getInstance("SHA-256")
        val tmp = Files.createTempFile(target.parent, ".upload-", ".tmp")
        try {
            DigestInputStream(Files.newInputStream(source), digest).use { input ->
                Files.copy(input, tmp, StandardCopyOption.REPLACE_EXISTING)
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(tmp)
        }
        return StoredBlob(key, Files.size(target), Hashing.toHex(digest.digest()))
    }

    override fun open(key: String): InputStream = Files.newInputStream(resolve(key))

    override fun exists(key: String) = Files.exists(resolve(key))

    override fun delete(key: String) {
        Files.deleteIfExists(resolve(key))
    }

    override fun healthy() = Files.isDirectory(root) && Files.isWritable(root)

    private fun resolve(key: String): Path {
        val path = root.resolve(key).normalize()
        require(path.startsWith(root)) { "Blob key escapes storage root" }
        return path
    }
}
