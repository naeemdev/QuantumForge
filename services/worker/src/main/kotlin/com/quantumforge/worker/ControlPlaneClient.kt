package com.quantumforge.worker

import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID

/** HTTP client for the control plane's internal worker API. */
class ControlPlaneClient(private val baseUrl: String, private val token: String) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val json = JsonMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build()

    fun lease(): LeasedJob? {
        val response = send(request("/internal/v1/leases").POST(HttpRequest.BodyPublishers.noBody()))
        if (response.statusCode() == 204) return null
        return json.readValue(response.body(), LeasedJob::class.java)
    }

    fun heartbeat(job: LeasedJob, message: String?): HeartbeatResult {
        val body = json.writeValueAsString(mapOf("message" to message))
        val response = send(attempt(job, "heartbeat").POST(HttpRequest.BodyPublishers.ofString(body)))
        return json.readValue(response.body(), HeartbeatResult::class.java)
    }

    fun start(job: LeasedJob, environment: Map<String, Any?>) {
        val body = json.writeValueAsString(mapOf("environment" to environment))
        send(attempt(job, "start").POST(HttpRequest.BodyPublishers.ofString(body)))
    }

    fun downloadSnapshot(job: LeasedJob, target: Path, maxBytes: Long) {
        val response = http.send(attempt(job, "snapshot").GET().build(), HttpResponse.BodyHandlers.ofInputStream())
        response.body().use { input ->
            if (response.statusCode() != 200) throw failure(response.statusCode(), input.readNBytes(4096).toString(Charsets.UTF_8))
            Files.newOutputStream(target).use { out -> copyBounded(input, out, maxBytes) }
        }
    }

    fun uploadArtifact(job: LeasedJob, name: String, kind: String, mediaType: String, truncated: Boolean, file: Path): UUID {
        val builder = HttpRequest.newBuilder(URI.create("$baseUrl/internal/v1/attempts/${job.attemptId}/artifacts/$name?kind=$kind&truncated=$truncated"))
            .timeout(Duration.ofMinutes(2))
            .header("Authorization", "Bearer $token")
            .header(LEASE_HEADER, job.leaseToken)
            .header("Content-Type", mediaType)
            .PUT(HttpRequest.BodyPublishers.ofFile(file))
        val response = send(builder)
        return UUID.fromString(json.readTree(response.body()).get("artifactId").asString())
    }

    fun complete(job: LeasedJob, report: CompletionReport): CompletionResult {
        val body = json.writeValueAsString(report)
        val response = send(attempt(job, "complete").POST(HttpRequest.BodyPublishers.ofString(body)))
        return json.readValue(response.body(), CompletionResult::class.java)
    }

    private fun request(path: String) = HttpRequest.newBuilder(URI.create(baseUrl + path))
        .timeout(Duration.ofSeconds(30))
        .header("Authorization", "Bearer $token")
        .header("Content-Type", "application/json")

    private fun attempt(job: LeasedJob, action: String) =
        request("/internal/v1/attempts/${job.attemptId}/$action").header(LEASE_HEADER, job.leaseToken)

    private fun send(builder: HttpRequest.Builder): HttpResponse<String> {
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) throw failure(response.statusCode(), response.body())
        return response
    }

    private fun failure(status: Int, body: String): RuntimeException {
        val code = runCatching { json.readTree(body).path("error").path("code").asString() }.getOrNull()
        return if (status == 409 && code == "lease_lost" || status == 404) {
            LeaseLostException("Control plane rejected the lease ($status ${code ?: ""})")
        } else {
            ControlPlaneException(status, code, body.take(500))
        }
    }

    private fun copyBounded(input: InputStream, out: java.io.OutputStream, limit: Long) {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            check(total <= limit) { "Snapshot exceeds $limit bytes" }
            out.write(buffer, 0, n)
        }
    }

    companion object {
        const val LEASE_HEADER = "X-Lease-Token"
    }
}

class ControlPlaneException(val status: Int, val code: String?, body: String) : RuntimeException("Control plane returned $status: $body")
