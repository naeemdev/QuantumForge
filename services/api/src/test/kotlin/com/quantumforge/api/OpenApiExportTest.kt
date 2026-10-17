package com.quantumforge.api

import com.quantumforge.api.support.IntegrationTest
import com.quantumforge.api.support.ApiClient
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals

/** Run with `./gradlew :services:api:exportOpenApi` to refresh packages/contracts/openapi.json. */
@Tag("openapi-export")
class OpenApiExportTest : IntegrationTest() {
    @Test
    fun `export public api document`() {
        val response = api.get("/v3/api-docs/public", null)
        assertEquals(200, response.status)
        val pretty = ApiClient.mapper.writerWithDefaultPrettyPrinter().writeValueAsString(ApiClient.mapper.readTree(response.body)) + "\n"
        val out = Path.of(System.getProperty("qf.openapi.out") ?: error("qf.openapi.out not set"))
        Files.createDirectories(out.parent)
        Files.writeString(out, pretty)
    }
}
