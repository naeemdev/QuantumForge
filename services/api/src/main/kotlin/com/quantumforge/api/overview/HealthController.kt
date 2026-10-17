package com.quantumforge.api.overview

import com.quantumforge.api.storage.BlobStore
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class HealthStatus(val status: String, val checks: Map<String, String> = emptyMap())

@RestController
@RequestMapping("/health")
@Tag(name = "Health")
class HealthController(private val jdbc: JdbcClient, private val blobs: BlobStore) {
    /** The process is up. Never touches dependencies, so orchestrators don't restart us for a DB blip. */
    @GetMapping("/live")
    fun live() = HealthStatus("UP")

    /** Ready to serve traffic: database and object storage reachable. */
    @GetMapping("/ready")
    fun ready(): ResponseEntity<HealthStatus> {
        val database = runCatching { jdbc.sql("SELECT 1").query(Int::class.java).single() == 1 }.getOrDefault(false)
        val storage = runCatching { blobs.healthy() }.getOrDefault(false)
        val checks = mapOf("database" to if (database) "UP" else "DOWN", "storage" to if (storage) "UP" else "DOWN")
        val up = database && storage
        return ResponseEntity.status(if (up) HttpStatus.OK else HttpStatus.SERVICE_UNAVAILABLE).body(HealthStatus(if (up) "UP" else "DOWN", checks))
    }
}
