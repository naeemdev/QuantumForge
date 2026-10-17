package com.quantumforge.api.storage

import java.io.InputStream
import java.nio.file.Path

data class StoredBlob(val key: String, val sizeBytes: Long, val sha256: String)

/**
 * Object storage for snapshot archives and execution evidence. Keys are always
 * built by the platform (see [BlobKeys]); user input never becomes part of a key
 * without validation.
 */
interface BlobStore {
    /** Stores the file at [source] under [key]. Writing an existing key with identical content is a no-op. */
    fun put(key: String, source: Path, contentType: String): StoredBlob

    fun open(key: String): InputStream

    fun exists(key: String): Boolean

    fun delete(key: String)

    /** Cheap reachability check used by the readiness probe. */
    fun healthy(): Boolean
}

object BlobKeys {
    private val segment = Regex("^[A-Za-z0-9._-]{1,128}$")

    fun snapshot(tenantId: Any, digest: String) = build("tenants", tenantId.toString(), "snapshots", "$digest.tar.gz")

    fun artifact(tenantId: Any, jobId: Any, attemptId: Any, name: String) =
        build("tenants", tenantId.toString(), "jobs", jobId.toString(), attemptId.toString(), name)

    private fun build(vararg parts: String): String {
        parts.forEach { require(segment.matches(it) && it != "." && it != "..") { "Invalid blob key segment" } }
        return parts.joinToString("/")
    }
}
