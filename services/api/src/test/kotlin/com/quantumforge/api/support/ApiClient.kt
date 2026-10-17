package com.quantumforge.api.support

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

data class Response(val status: Int, val body: String, val headers: Map<String, List<String>>) {
    val json: JsonNode by lazy { ApiClient.mapper.readTree(body.ifEmpty { "null" }) }

    fun field(name: String): String = json.get(name)?.text() ?: error("Field '$name' missing in $body")

    val errorCode: String? get() = json.path("error").path("code").takeIf { !it.isMissingNode }?.text()
}

/** Small typed-enough HTTP client for exercising the public API from tests. */
class ApiClient(private val baseUrl: String) {
    private val http = HttpClient.newHttpClient()

    fun login(email: String = "user-${UUID.randomUUID()}@example.test", name: String? = null): String {
        val r = post("/api/v1/dev/session", null, mapOf("email" to email, "displayName" to name))
        check(r.status == 200) { "dev login failed: ${r.body}" }
        return r.field("accessToken")
    }

    fun get(path: String, token: String?, headers: Map<String, String> = emptyMap()) = send("GET", path, token, null, headers)

    fun post(path: String, token: String?, body: Any? = null, headers: Map<String, String> = emptyMap()) = send("POST", path, token, body, headers)

    fun patch(path: String, token: String?, body: Any?) = send("PATCH", path, token, body)

    fun delete(path: String, token: String?) = send("DELETE", path, token, null)

    fun upload(path: String, token: String, file: Path, filename: String = file.fileName.toString()): Response {
        val boundary = "qf-" + UUID.randomUUID()
        val head = "--$boundary\r\nContent-Disposition: form-data; name=\"archive\"; filename=\"$filename\"\r\nContent-Type: application/octet-stream\r\n\r\n"
        val body = head.toByteArray() + Files.readAllBytes(file) + "\r\n--$boundary--\r\n".toByteArray()
        val request = HttpRequest.newBuilder(URI.create(baseUrl + path))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build()
        return toResponse(http.send(request, HttpResponse.BodyHandlers.ofString()))
    }

    fun raw(url: String, token: String?, method: String = "GET", body: Any? = null): Response {
        val builder = HttpRequest.newBuilder(URI.create(url))
        token?.let { builder.header("Authorization", "Bearer $it") }
        builder.header("Content-Type", "application/json")
        builder.method(method, body?.let { HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(it)) } ?: HttpRequest.BodyPublishers.noBody())
        return toResponse(http.send(builder.build(), HttpResponse.BodyHandlers.ofString()))
    }

    fun bytes(path: String, token: String): Pair<Int, ByteArray> {
        val request = HttpRequest.newBuilder(URI.create(baseUrl + path)).header("Authorization", "Bearer $token").GET().build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofByteArray())
        return response.statusCode() to response.body()
    }

    private fun send(method: String, path: String, token: String?, body: Any?, headers: Map<String, String> = emptyMap()): Response {
        val builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
        token?.let { builder.header("Authorization", "Bearer $it") }
        headers.forEach { (k, v) -> builder.header(k, v) }
        val publisher = if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))
        if (body != null) builder.header("Content-Type", "application/json")
        builder.method(method, publisher)
        return toResponse(http.send(builder.build(), HttpResponse.BodyHandlers.ofString()))
    }

    private fun toResponse(r: HttpResponse<String>) = Response(r.statusCode(), r.body(), r.headers().map())

    // Convenience flows used by most tests.

    fun createOrganization(token: String, name: String = "Org ${UUID.randomUUID().toString().take(8)}"): String {
        val r = post("/api/v1/organizations", token, mapOf("name" to name))
        check(r.status == 201) { "create org failed: ${r.body}" }
        return r.field("id")
    }

    fun createProject(token: String, orgId: String, name: String = "Project ${UUID.randomUUID().toString().take(8)}"): String {
        val r = post("/api/v1/projects", token, mapOf("organizationId" to orgId, "name" to name))
        check(r.status == 201) { "create project failed: ${r.body}" }
        return r.field("id")
    }

    fun createUploadRepository(token: String, projectId: String, name: String = "main"): String {
        val r = post("/api/v1/projects/$projectId/repositories", token, mapOf("name" to name, "sourceType" to "UPLOAD"))
        check(r.status == 201) { "create repository failed: ${r.body}" }
        return r.field("id")
    }

    companion object {
        val mapper: JsonMapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
    }
}

/** Non-null string view of a JSON node, for readable assertions. */
fun JsonNode.text(): String = asString() ?: ""

/** Elements of a JSON array as a plain Kotlin list. */
fun JsonNode.elements(): List<JsonNode> = (0 until size()).map { get(it)!! }

fun JsonNode.texts(field: String): List<String> = elements().map { it.path(field).text() }
