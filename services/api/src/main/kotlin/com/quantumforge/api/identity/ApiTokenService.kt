package com.quantumforge.api.identity

import com.quantumforge.api.access.OrgAccess
import com.quantumforge.api.audit.AuditService
import com.quantumforge.api.common.BadRequestException
import com.quantumforge.api.common.ForbiddenException
import com.quantumforge.api.common.Hashing
import com.quantumforge.api.common.Ids
import com.quantumforge.api.common.NotFoundException
import com.quantumforge.api.common.instant
import com.quantumforge.api.common.instantOrNull
import com.quantumforge.api.common.sql
import com.quantumforge.api.common.uuid
import com.quantumforge.api.config.QuantumForgeProperties
import com.quantumforge.api.security.AuthMethod
import com.quantumforge.api.security.Caller
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class ApiToken(
    val id: UUID,
    val organizationId: UUID,
    val name: String,
    val tokenPrefix: String,
    val expiresAt: Instant,
    val revokedAt: Instant?,
    val lastUsedAt: Instant?,
    val createdAt: Instant,
)

data class CreatedApiToken(val token: ApiToken, val secret: String)

/**
 * Personal access tokens for the CLI and CI. A token acts as its owner, inside one
 * organization only, and never with more permissions than the owner currently has:
 * permissions are evaluated live from the membership on every request.
 */
@Service
class ApiTokenService(
    private val jdbc: JdbcClient,
    private val audit: AuditService,
    private val properties: QuantumForgeProperties,
    private val clock: Clock,
) {
    @Transactional
    fun create(ctx: OrgAccess, name: String, lifetime: Duration): CreatedApiToken {
        if (ctx.caller.authMethod == AuthMethod.API_TOKEN) throw ForbiddenException("API tokens cannot mint other API tokens")
        if (lifetime.isNegative || lifetime.isZero || lifetime > properties.auth.apiTokenMaxLifetime) {
            throw BadRequestException("invalid_lifetime", "Token lifetime must be between 1 day and ${properties.auth.apiTokenMaxLifetime.toDays()} days")
        }
        val secret = TOKEN_PREFIX + Hashing.randomSecret()
        val id = Ids.newId(clock)
        val now = Instant.now(clock)
        jdbc.sql(
            """
            INSERT INTO api_tokens (id, tenant_id, organization_id, user_id, name, token_prefix, token_hash, expires_at,
                                    created_at, created_by, updated_at, updated_by)
            VALUES (:id, :tenant, :org, :user, :name, :prefix, :hash, :expires, :now, :user, :now, :user)
            """,
        ).param("id", id).param("tenant", ctx.tenantId).param("org", ctx.organizationId).param("user", ctx.userId)
            .param("name", name).param("prefix", secret.take(TOKEN_PREFIX.length + 6)).param("hash", Hashing.sha256(secret))
            .param("expires", now.plus(lifetime).sql()).param("now", now.sql()).update()
        audit.record(ctx, "api_token.created", "api_token", id, mapOf("name" to name, "expiresInDays" to lifetime.toDays()))
        return CreatedApiToken(get(id), secret)
    }

    fun listOwn(ctx: OrgAccess): List<ApiToken> =
        jdbc.sql("SELECT * FROM api_tokens WHERE tenant_id = :tenant AND organization_id = :org AND user_id = :user ORDER BY created_at DESC")
            .param("tenant", ctx.tenantId).param("org", ctx.organizationId).param("user", ctx.userId).query(::map).list()

    @Transactional
    fun revoke(ctx: OrgAccess, tokenId: UUID) {
        val n = jdbc.sql(
            """
            UPDATE api_tokens SET revoked_at = :now, revoked_by = :user, updated_at = :now, updated_by = :user, version = version + 1
            WHERE id = :id AND tenant_id = :tenant AND organization_id = :org AND user_id = :user AND revoked_at IS NULL
            """,
        ).param("now", Instant.now(clock).sql()).param("user", ctx.userId)
            .param("id", tokenId).param("tenant", ctx.tenantId).param("org", ctx.organizationId).update()
        if (n == 0) throw NotFoundException("api_token", tokenId)
        audit.record(ctx, "api_token.revoked", "api_token", tokenId)
    }

    /** Resolves a presented secret to a caller, or null when unknown, expired or revoked. */
    fun authenticate(secret: String): Caller? {
        data class Row(val id: UUID, val orgId: UUID, val userId: UUID, val email: String?, val name: String?, val userStatus: String)
        val now = Instant.now(clock)
        val row = jdbc.sql(
            """
            SELECT t.id, t.organization_id, t.user_id, u.email, u.display_name, u.status
            FROM api_tokens t JOIN users u ON u.id = t.user_id
            WHERE t.token_hash = :hash AND t.revoked_at IS NULL AND t.expires_at > :now
            """,
        ).param("hash", Hashing.sha256(secret)).param("now", now.sql())
            .query { rs, _ -> Row(rs.uuid("id"), rs.uuid("organization_id"), rs.uuid("user_id"), rs.getString("email"), rs.getString("display_name"), rs.getString("status")) }
            .optional().orElse(null) ?: return null
        if (row.userStatus != "ACTIVE") return null
        jdbc.sql("UPDATE api_tokens SET last_used_at = :now WHERE id = :id AND (last_used_at IS NULL OR last_used_at < :stale)")
            .param("now", now.sql()).param("stale", now.minus(Duration.ofMinutes(1)).sql()).param("id", row.id).update()
        return Caller(row.userId, row.email, row.name, AuthMethod.API_TOKEN, tokenId = row.id, tokenOrganizationId = row.orgId)
    }

    private fun get(id: UUID) = jdbc.sql("SELECT * FROM api_tokens WHERE id = :id").param("id", id).query(::map).single()

    private fun map(rs: ResultSet, @Suppress("UNUSED_PARAMETER") i: Int) = ApiToken(
        id = rs.uuid("id"),
        organizationId = rs.uuid("organization_id"),
        name = rs.getString("name"),
        tokenPrefix = rs.getString("token_prefix"),
        expiresAt = rs.instant("expires_at"),
        revokedAt = rs.instantOrNull("revoked_at"),
        lastUsedAt = rs.instantOrNull("last_used_at"),
        createdAt = rs.instant("created_at"),
    )

    companion object {
        const val TOKEN_PREFIX = "qf_pat_"
    }
}
