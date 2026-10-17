package com.quantumforge.api.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "quantumforge")
data class QuantumForgeProperties(
    val auth: Auth = Auth(),
    val storage: Storage = Storage(),
    val ingestion: Ingestion = Ingestion(),
    val execution: Execution = Execution(),
    val internalApi: InternalApi = InternalApi(),
    val cors: Cors = Cors(),
) {
    data class Auth(
        /** OIDC issuer used in production (Keycloak, Auth0, Entra ID, ...). */
        val oidc: Oidc = Oidc(),
        /** Local development login. Must never be enabled in the prod profile. */
        val devLogin: DevLogin = DevLogin(),
        val apiTokenMaxLifetime: Duration = Duration.ofDays(365),
    )

    data class Oidc(
        val issuerUri: String? = null,
        val audience: String? = null,
        val emailClaim: String = "email",
        val nameClaim: String = "name",
    )

    data class DevLogin(
        val enabled: Boolean = false,
        val issuer: String = "urn:quantumforge:dev",
        val tokenLifetime: Duration = Duration.ofHours(12),
    )

    data class Storage(
        val type: String = "filesystem",
        val filesystemRoot: String = "./data/blobs",
        val s3: S3 = S3(),
    )

    data class S3(
        val endpoint: String? = null,
        val region: String = "us-east-1",
        val bucket: String = "quantumforge",
        val accessKey: String? = null,
        val secretKey: String? = null,
        val pathStyle: Boolean = true,
    )

    data class Ingestion(
        val maxArchiveBytes: Long = 50L * 1024 * 1024,
        val maxUncompressedBytes: Long = 200L * 1024 * 1024,
        val maxFileBytes: Long = 10L * 1024 * 1024,
        val maxEntries: Int = 20_000,
        val maxPathLength: Int = 1024,
        val maxCompressionRatio: Int = 200,
        val git: Git = Git(),
    )

    data class Git(
        val enabled: Boolean = true,
        val executable: String = "git",
        val timeout: Duration = Duration.ofMinutes(2),
        val allowedHosts: List<String> = listOf("github.com", "gitlab.com", "bitbucket.org"),
    )

    data class Execution(
        /** Permit workers that run jobs as plain host processes. Development only. */
        val allowDevIsolation: Boolean = false,
        val leaseDuration: Duration = Duration.ofSeconds(60),
        val reaperInterval: Duration = Duration.ofSeconds(15),
        val defaultTimeoutSeconds: Int = 300,
        val maxTimeoutSeconds: Int = 1800,
        val maxArtifactBytes: Long = 32L * 1024 * 1024,
        val maxTestResults: Int = 5000,
        /** Workers registered at startup: name, isolation mode and token. */
        val bootstrapWorkers: List<BootstrapWorker> = emptyList(),
    )

    data class BootstrapWorker(val name: String, val isolationMode: String, val token: String)

    data class InternalApi(
        /** Separate port for worker traffic; never routed through the public ingress. */
        val port: Int = 8081,
    )

    data class Cors(val allowedOrigins: List<String> = listOf("http://localhost:3000"))
}
