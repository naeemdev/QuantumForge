package com.quantumforge.api.unit

import com.quantumforge.api.storage.BlobStore
import com.quantumforge.api.storage.FilesystemBlobStore
import com.quantumforge.api.storage.S3BlobStore
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The same behavioural contract for every BlobStore implementation. */
class BlobStoreContractTest {
    @TempDir
    lateinit var tmp: Path

    private fun exercise(store: BlobStore) {
        val content = "evidence ${UUID.randomUUID()}".toByteArray()
        val source = Files.write(tmp.resolve("src.bin"), content)
        val key = "tenants/t1/jobs/j1/a1/${UUID.randomUUID()}.log"

        assertFalse(store.exists(key))
        val stored = store.put(key, source, "text/plain")
        assertEquals(content.size.toLong(), stored.sizeBytes)
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)), stored.sha256)
        assertTrue(store.exists(key))
        assertTrue(store.open(key).use { it.readAllBytes() }.contentEquals(content))
        assertTrue(store.healthy())
        store.delete(key)
        assertFalse(store.exists(key))
    }

    @Test
    fun `filesystem store honours the contract and refuses keys outside its root`() {
        val store = FilesystemBlobStore(tmp.resolve("blobs"))
        exercise(store)
        assertFailsWith<IllegalArgumentException> { store.open("../outside") }
    }

    /** Runs when QF_TEST_S3_ENDPOINT points at an S3-compatible server (CI uses Adobe S3Mock). */
    @Test
    fun `s3 store honours the contract`() {
        val endpoint = System.getenv("QF_TEST_S3_ENDPOINT")
        assumeTrue(!endpoint.isNullOrBlank(), "QF_TEST_S3_ENDPOINT not set")
        val client = S3Client.builder()
            .endpointOverride(URI.create(endpoint))
            .region(Region.US_EAST_1)
            .forcePathStyle(true)
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(System.getenv("QF_TEST_S3_ACCESS_KEY"), System.getenv("QF_TEST_S3_SECRET_KEY"))))
            .build()
        val bucket = "qf-test-${UUID.randomUUID().toString().take(8)}"
        client.createBucket(CreateBucketRequest.builder().bucket(bucket).build())
        exercise(S3BlobStore(client, bucket))
    }
}
