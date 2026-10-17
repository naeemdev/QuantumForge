package com.quantumforge.api.jobs

import com.quantumforge.api.common.Json
import com.quantumforge.api.common.sql
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Appends to a job's timeline. Always called inside the transaction making the change. */
@Component
class JobEventWriter(private val jdbc: JdbcClient, private val clock: Clock) {
    fun append(tenantId: UUID, jobId: UUID, attemptId: UUID?, type: String, message: String, data: Map<String, Any?> = emptyMap()) {
        jdbc.sql(
            """
            INSERT INTO job_events (tenant_id, job_id, attempt_id, event_type, message, data, occurred_at)
            VALUES (:tenant, :job, :attempt, :type, :message, CAST(:data AS jsonb), :now)
            """,
        ).param("tenant", tenantId).param("job", jobId).param("attempt", attemptId)
            .param("type", type).param("message", message.take(2000)).param("data", Json.write(data))
            .param("now", Instant.now(clock).sql()).update()
    }
}
