package com.quantumforge.api.storage

import com.quantumforge.api.config.QuantumForgeProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import java.net.URI
import java.nio.file.Path

@Configuration
class StorageConfig {
    @Bean
    fun blobStore(properties: QuantumForgeProperties): BlobStore = when (properties.storage.type) {
        "filesystem" -> FilesystemBlobStore(Path.of(properties.storage.filesystemRoot))
        "s3" -> {
            val s3 = properties.storage.s3
            val builder = S3Client.builder()
                .region(Region.of(s3.region))
                .forcePathStyle(s3.pathStyle)
                .credentialsProvider(
                    if (!s3.accessKey.isNullOrBlank() && !s3.secretKey.isNullOrBlank()) {
                        StaticCredentialsProvider.create(AwsBasicCredentials.create(s3.accessKey, s3.secretKey))
                    } else {
                        DefaultCredentialsProvider.builder().build()
                    },
                )
            s3.endpoint?.takeIf { it.isNotBlank() }?.let { builder.endpointOverride(URI.create(it)) }
            S3BlobStore(builder.build(), s3.bucket)
        }
        else -> error("Unknown quantumforge.storage.type '${properties.storage.type}' (expected filesystem or s3)")
    }
}
