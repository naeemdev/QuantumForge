package com.quantumforge.api.audit

import com.quantumforge.api.access.AccessService
import com.quantumforge.api.access.Permission
import com.quantumforge.api.common.Json
import com.quantumforge.api.common.Page
import com.quantumforge.api.common.PageRequest
import com.quantumforge.api.common.instant
import com.quantumforge.api.common.uuidOrNull
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

data class AuditEventView(
    val eventId: UUID,
    val actorType: String,
    val actorId: UUID?,
    val action: String,
    val targetType: String?,
    val targetId: UUID?,
    val outcome: String,
    val details: Map<String, Any?>,
    val requestId: String?,
    val occurredAt: Instant,
)

@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/audit-events")
@Tag(name = "Audit")
class AuditController(private val access: AccessService, private val jdbc: JdbcClient) {
    @GetMapping
    fun list(
        @PathVariable organizationId: UUID,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?,
    ): Page<AuditEventView> {
        val ctx = access.require(organizationId, Permission.ORG_AUDIT_READ)
        val pr = PageRequest.of(page, size)
        val items = jdbc.sql(
            """
            SELECT * FROM audit_events WHERE tenant_id = :tenant AND organization_id = :org
            ORDER BY occurred_at DESC, id DESC LIMIT :limit OFFSET :offset
            """,
        )
            .param("tenant", ctx.tenantId).param("org", organizationId)
            .param("limit", pr.size).param("offset", pr.offset)
            .query { rs, _ ->
                AuditEventView(
                    eventId = rs.getObject("event_id", UUID::class.java),
                    actorType = rs.getString("actor_type"),
                    actorId = rs.uuidOrNull("actor_id"),
                    action = rs.getString("action"),
                    targetType = rs.getString("target_type"),
                    targetId = rs.uuidOrNull("target_id"),
                    outcome = rs.getString("outcome"),
                    details = Json.readMap(rs.getString("details")),
                    requestId = rs.getString("request_id"),
                    occurredAt = rs.instant("occurred_at"),
                )
            }.list()
        val total = jdbc.sql("SELECT count(*) FROM audit_events WHERE tenant_id = :tenant AND organization_id = :org")
            .param("tenant", ctx.tenantId).param("org", organizationId).query(Long::class.java).single()
        return Page.of(items, pr, total)
    }
}
