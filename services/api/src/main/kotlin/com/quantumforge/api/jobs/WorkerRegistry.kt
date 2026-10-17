package com.quantumforge.api.jobs

import com.quantumforge.api.common.Hashing
import com.quantumforge.api.common.Ids
import com.quantumforge.api.common.sql
import com.quantumforge.api.common.uuid
import com.quantumforge.api.config.QuantumForgeProperties
import com.quantumforge.api.security.WorkerIdentity
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant

@Service
class WorkerRegistry(
    private val jdbc: JdbcClient,
    private val properties: QuantumForgeProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Registers (or re-keys) the workers declared in configuration. */
    @EventListener(ApplicationReadyEvent::class)
    fun registerBootstrapWorkers() {
        properties.execution.bootstrapWorkers.forEach { w ->
            require(w.isolationMode in ISOLATION_MODES) { "Unknown isolation mode '${w.isolationMode}' for worker ${w.name}" }
            require(w.token.length >= 16) { "Worker token for ${w.name} is too short" }
            val now = Instant.now(clock).sql()
            jdbc.sql(
                """
                INSERT INTO workers (id, name, isolation_mode, token_hash, created_at, updated_at)
                VALUES (:id, :name, :mode, :hash, :now, :now)
                ON CONFLICT (name) DO UPDATE SET isolation_mode = EXCLUDED.isolation_mode, token_hash = EXCLUDED.token_hash, updated_at = EXCLUDED.updated_at
                """,
            ).param("id", Ids.newId(clock)).param("name", w.name).param("mode", w.isolationMode)
                .param("hash", Hashing.sha256(w.token)).param("now", now).update()
            log.info("Registered worker {} with isolation mode {}", w.name, w.isolationMode)
            if (w.isolationMode == "dev-process" && !properties.execution.allowDevIsolation) {
                log.warn("Worker {} uses dev-process isolation, which is disabled; it will not receive jobs", w.name)
            }
        }
    }

    fun authenticate(token: String): WorkerIdentity? {
        val now = Instant.now(clock)
        val worker = jdbc.sql("SELECT id, name, isolation_mode FROM workers WHERE token_hash = :hash AND status = 'ACTIVE'")
            .param("hash", Hashing.sha256(token))
            .query { rs, _ -> WorkerIdentity(rs.uuid("id"), rs.getString("name"), rs.getString("isolation_mode")) }
            .optional().orElse(null) ?: return null
        jdbc.sql("UPDATE workers SET last_seen_at = :now WHERE id = :id AND (last_seen_at IS NULL OR last_seen_at < :stale)")
            .param("now", now.sql()).param("stale", now.minus(Duration.ofSeconds(10)).sql()).param("id", worker.workerId).update()
        return worker
    }

    fun isolationAllowed(mode: String) = mode != "dev-process" || properties.execution.allowDevIsolation

    companion object {
        val ISOLATION_MODES = setOf("dev-process", "container", "container-gvisor", "microvm")
    }
}
