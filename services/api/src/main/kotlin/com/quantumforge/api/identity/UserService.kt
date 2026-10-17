package com.quantumforge.api.identity

import com.quantumforge.api.common.Ids
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class User(
    val id: UUID,
    val issuer: String,
    val subject: String,
    val email: String?,
    val displayName: String?,
    val status: String,
    val createdAt: Instant,
)

@Service
class UserService(private val jdbc: JdbcClient, private val clock: Clock) {
    /**
     * Just-in-time provisioning keyed by (issuer, subject). Profile fields are
     * refreshed from the token when they change. Returns null for disabled users.
     */
    @Transactional
    fun provision(issuer: String, subject: String, email: String?, displayName: String?): User? {
        val now = Instant.now(clock)
        val cleanEmail = email?.trim()?.take(320)?.takeIf { it.isNotEmpty() }
        val cleanName = displayName?.trim()?.take(200)?.takeIf { it.isNotEmpty() }

        val existing = findByIdentity(issuer, subject)
        if (existing != null) {
            if (existing.status != "ACTIVE") return null
            val profileChanged = existing.email != cleanEmail || existing.displayName != cleanName
            jdbc.sql(
                """
                UPDATE users SET email = :email, display_name = :name, updated_at = CASE WHEN :changed THEN :now ELSE updated_at END,
                       last_seen_at = :now
                WHERE id = :id AND (last_seen_at IS NULL OR last_seen_at < :staleBefore OR :changed)
                """,
            )
                .param("email", cleanEmail).param("name", cleanName).param("changed", profileChanged)
                .param("now", now.atOffset(java.time.ZoneOffset.UTC))
                .param("staleBefore", now.minus(Duration.ofMinutes(5)).atOffset(java.time.ZoneOffset.UTC))
                .param("id", existing.id)
                .update()
            return existing.copy(email = cleanEmail, displayName = cleanName)
        }

        val id = Ids.newId(clock)
        jdbc.sql(
            """
            INSERT INTO users (id, issuer, subject, email, display_name, status, created_at, updated_at, last_seen_at)
            VALUES (:id, :issuer, :subject, :email, :name, 'ACTIVE', :now, :now, :now)
            ON CONFLICT (issuer, subject) DO NOTHING
            """,
        )
            .param("id", id).param("issuer", issuer).param("subject", subject)
            .param("email", cleanEmail).param("name", cleanName)
            .param("now", now.atOffset(java.time.ZoneOffset.UTC))
            .update()
        // A concurrent first login may have won the insert; read whichever row exists.
        return findByIdentity(issuer, subject)?.takeIf { it.status == "ACTIVE" }
    }

    fun findById(id: UUID): User? =
        jdbc.sql("SELECT * FROM users WHERE id = :id").param("id", id).query(::map).optional().orElse(null)

    private fun findByIdentity(issuer: String, subject: String): User? =
        jdbc.sql("SELECT * FROM users WHERE issuer = :issuer AND subject = :subject")
            .param("issuer", issuer).param("subject", subject)
            .query(::map).optional().orElse(null)

    private fun map(rs: ResultSet, @Suppress("UNUSED_PARAMETER") row: Int) = User(
        id = rs.getObject("id", UUID::class.java),
        issuer = rs.getString("issuer"),
        subject = rs.getString("subject"),
        email = rs.getString("email"),
        displayName = rs.getString("display_name"),
        status = rs.getString("status"),
        createdAt = rs.getTimestamp("created_at").toInstant(),
    )
}
