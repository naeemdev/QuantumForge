package com.quantumforge.api.audit

import com.quantumforge.api.access.OrgAccess
import com.quantumforge.api.common.Ids
import com.quantumforge.api.common.Json
import com.quantumforge.api.common.RequestIdFilter
import com.quantumforge.api.common.sql
import com.quantumforge.api.security.AuthMethod
import com.quantumforge.api.security.Caller
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class AuditActor(val type: String, val id: UUID?) {
    companion object {
        fun of(caller: Caller) = AuditActor(if (caller.authMethod == AuthMethod.API_TOKEN) "API_TOKEN" else "USER", caller.tokenId ?: caller.userId)
        fun worker(id: UUID) = AuditActor("WORKER", id)
        val SYSTEM = AuditActor("SYSTEM", null)
    }
}

/**
 * Writes the append-only security audit trail. Successful actions are recorded in
 * the caller's transaction so the event and the change commit together. Denials
 * are recorded in a separate transaction because the request's own transaction
 * is about to roll back.
 */
@Service
class AuditService(
    private val jdbc: JdbcClient,
    private val clock: Clock,
    transactionManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger("audit")
    private val separateTx = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    fun record(
        access: OrgAccess,
        action: String,
        targetType: String?,
        targetId: UUID?,
        details: Map<String, Any?> = emptyMap(),
    ) = write(access.tenantId, access.organizationId, AuditActor.of(access.caller), action, targetType, targetId, "SUCCESS", details)

    fun recordDenied(access: OrgAccess, action: String, targetType: String?, targetId: UUID?, details: Map<String, Any?> = emptyMap()) {
        try {
            separateTx.executeWithoutResult {
                write(access.tenantId, access.organizationId, AuditActor.of(access.caller), action, targetType, targetId, "DENIED", details)
            }
        } catch (e: Exception) {
            // Losing a denial record must not turn a 403 into a 500, but it must be visible.
            log.error("Failed to persist DENIED audit event {}", action, e)
        }
    }

    fun write(
        tenantId: UUID?,
        organizationId: UUID?,
        actor: AuditActor,
        action: String,
        targetType: String?,
        targetId: UUID?,
        outcome: String,
        details: Map<String, Any?> = emptyMap(),
    ) {
        val request = (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request
        val now = Instant.now(clock)
        jdbc.sql(
            """
            INSERT INTO audit_events (event_id, tenant_id, organization_id, actor_type, actor_id, action, target_type, target_id,
                                      outcome, details, client_ip, user_agent, request_id, occurred_at)
            VALUES (:eventId, :tenant, :org, :actorType, :actorId, :action, :targetType, :targetId,
                    :outcome, CAST(:details AS jsonb), :ip, :ua, :requestId, :now)
            """,
        )
            .param("eventId", Ids.newId(clock))
            .param("tenant", tenantId).param("org", organizationId)
            .param("actorType", actor.type).param("actorId", actor.id)
            .param("action", action).param("targetType", targetType).param("targetId", targetId)
            .param("outcome", outcome)
            .param("details", Json.write(details))
            .param("ip", request?.remoteAddr)
            .param("ua", request?.getHeader("User-Agent")?.take(500))
            .param("requestId", RequestIdFilter.current(request))
            .param("now", now.sql())
            .update()
        log.info("audit action={} outcome={} org={} actor={}:{} target={}:{}", action, outcome, organizationId, actor.type, actor.id, targetType, targetId)
    }
}
