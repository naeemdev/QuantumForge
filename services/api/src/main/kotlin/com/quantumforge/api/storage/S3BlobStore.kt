package com.quantumforge.api.storage

import com.quantumforge.api.common.Hashing
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadBucketRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

/** S3-compatible store (AWS S3, Ceph RGW, SeaweedFS, Garage, ...). */
class S3BlobStore(private val client: S3Client, private val bucket: String) : BlobStore {
    override fun put(key: String, source: Path, contentType: String): StoredBlob {
        val sha256 = Files.newInputStream(source).use { Hashing.toHex(digest(it)) }
        client.putObject(
            PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType)
                .metadata(mapOf("sha256" to sha256)).build(),
            RequestBody.fromFile(source),
        )
        return StoredBlob(key, Files.size(source), sha256)
    }

    override fun open(key: String): InputStream =
        client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build())

    override fun exists(key: String): Boolean = try {
        client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build())
        true
    } catch (e: NoSuchKeyException) {
        false
    } catch (e: S3Exception) {
        if (e.statusCode() == 404) false else throw e
    }

    override fun delete(key: String) {
        client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build())
    }

    override fun healthy(): Boolean = try {
        client.headBucket(HeadBucketRequest.builder().bucket(bucket).build())
        true
    } catch (e: Exception) {
        false
    }

    private fun digest(input: InputStream): ByteArray {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            md.update(buffer, 0, n)
        }
        return md.digest()
    }
}
