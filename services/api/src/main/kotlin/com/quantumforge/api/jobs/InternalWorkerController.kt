package com.quantumforge.api.jobs

import com.quantumforge.api.security.CurrentCaller
import io.swagger.v3.oas.annotations.Hidden
import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.io.InputStreamResource
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class HeartbeatRequest(val message: String? = null)

data class StartRequest(val environment: Map<String, Any?> = emptyMap())

/**
 * Worker protocol. Served only on the internal port (PortSeparationFilter) and
 * only to authenticated workers (WorkerAuthenticationFilter). Excluded from the
 * public OpenAPI document.
 */
@Hidden
@RestController
@RequestMapping("/internal/v1")
class InternalWorkerController(private val dispatch: DispatchService) {
    @PostMapping("/leases")
    fun lease(): ResponseEntity<LeasedJob> {
        val job = dispatch.lease(CurrentCaller.worker()) ?: return ResponseEntity.noContent().build()
        return ResponseEntity.ok(job)
    }

    @PostMapping("/attempts/{id}/heartbeat")
    fun heartbeat(@PathVariable id: UUID, @RequestHeader(LEASE_HEADER) token: String, @RequestBody(required = false) body: HeartbeatRequest?) =
        dispatch.heartbeat(CurrentCaller.worker(), id, token, body?.message)

    @PostMapping("/attempts/{id}/start")
    fun start(@PathVariable id: UUID, @RequestHeader(LEASE_HEADER) token: String, @RequestBody body: StartRequest): ResponseEntity<Void> {
        dispatch.start(CurrentCaller.worker(), id, token, body.environment)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/attempts/{id}/snapshot")
    fun snapshot(@PathVariable id: UUID, @RequestHeader(LEASE_HEADER) token: String): ResponseEntity<InputStreamResource> =
        ResponseEntity.ok().contentType(MediaType.parseMediaType("application/gzip"))
            .body(InputStreamResource(dispatch.snapshotStream(CurrentCaller.worker(), id, token)))

    @PutMapping("/attempts/{id}/artifacts/{name}")
    fun upload(
        @PathVariable id: UUID,
        @PathVariable name: String,
        @RequestHeader(LEASE_HEADER) token: String,
        @RequestParam kind: String,
        @RequestParam(defaultValue = "false") truncated: Boolean,
        request: HttpServletRequest,
    ): Map<String, UUID> {
        val mediaType = request.contentType?.substringBefore(';')?.trim()?.lowercase() ?: "application/octet-stream"
        val artifactId = request.inputStream.use { dispatch.uploadArtifact(CurrentCaller.worker(), id, token, name, kind, mediaType, truncated, it) }
        return mapOf("artifactId" to artifactId)
    }

    @PostMapping("/attempts/{id}/complete")
    fun complete(@PathVariable id: UUID, @RequestHeader(LEASE_HEADER) token: String, @RequestBody body: CompletionReport): CompletionResult =
        dispatch.complete(CurrentCaller.worker(), id, token, body)

    companion object {
        const val LEASE_HEADER = "X-Lease-Token"
    }
}
