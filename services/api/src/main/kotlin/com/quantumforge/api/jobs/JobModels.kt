package com.quantumforge.api.jobs

import com.quantumforge.api.common.BadRequestException
import java.time.Instant
import java.util.UUID

enum class JobState(val terminal: Boolean) {
    QUEUED(false),
    LEASED(false),
    RUNNING(false),
    SUCCEEDED(true),
    FAILED(true),
    TIMED_OUT(true),
    CANCELLED(true),
    INFRA_ERROR(true),
    ;

    companion object {
        val ACTIVE = entries.filter { !it.terminal }
    }
}

enum class JobType { TEST_EXECUTION }

/** What a worker may report for an attempt. LEASE_EXPIRED is set only by the reaper. */
enum class AttemptOutcome { SUCCEEDED, FAILED, TIMED_OUT, CANCELLED, INFRA_ERROR, LEASE_EXPIRED }

/**
 * Commands are chosen from presets rather than accepted as free text: the API
 * decides what runs, the worker only fills in paths. New toolchains are added as
 * presets with their own image and limits.
 */
enum class TestPreset(val runtime: String, val description: String) {
    NODE_TEST("node", "Node.js built-in test runner over TypeScript (type stripping) and JavaScript test files"),
}

data class TestExecutionConfig(
    val preset: TestPreset = TestPreset.NODE_TEST,
    /** Glob patterns relative to the snapshot root; defaults to the preset's conventions. */
    val testPatterns: List<String> = emptyList(),
    val timeoutSeconds: Int? = null,
    val memoryMb: Int = 512,
    val cpuMillis: Int = 1000,
    val maxOutputBytes: Long = 1024 * 1024,
) {
    fun validated(maxTimeout: Int): TestExecutionConfig {
        if (testPatterns.size > 20) throw BadRequestException("invalid_config", "At most 20 test patterns are allowed")
        testPatterns.forEach {
            if (!PATTERN.matches(it) || it.contains("..") || it.startsWith("/") || it.startsWith("-")) {
                throw BadRequestException("invalid_config", "Invalid test pattern '$it'")
            }
        }
        if (timeoutSeconds != null && timeoutSeconds !in 1..maxTimeout) {
            throw BadRequestException("invalid_config", "timeoutSeconds must be between 1 and $maxTimeout")
        }
        if (memoryMb !in 64..8192) throw BadRequestException("invalid_config", "memoryMb must be between 64 and 8192")
        if (cpuMillis !in 100..8000) throw BadRequestException("invalid_config", "cpuMillis must be between 100 and 8000")
        if (maxOutputBytes !in 1024..(16L * 1024 * 1024)) throw BadRequestException("invalid_config", "maxOutputBytes must be between 1 KiB and 16 MiB")
        return this
    }

    fun effectivePatterns(): List<String> = testPatterns.ifEmpty { listOf("**/*.test.ts", "**/*.test.js", "**/*.test.mjs") }

    companion object {
        private val PATTERN = Regex("^[A-Za-z0-9_./*{},@-]{1,200}$")
    }
}

data class Job(
    val id: UUID,
    val organizationId: UUID,
    val projectId: UUID,
    val snapshotId: UUID,
    val jobType: JobType,
    val state: JobState,
    val priority: Int,
    val config: TestExecutionConfig,
    val idempotencyKey: String?,
    val maxAttempts: Int,
    val attemptCount: Int,
    val timeoutSeconds: Int,
    val cancelRequested: Boolean,
    val queuedAt: Instant,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    val resultSummary: Map<String, Any?>?,
    val failureReason: String?,
    val createdAt: Instant,
    val createdBy: UUID,
    val updatedAt: Instant,
    val version: Long,
)

data class JobAttempt(
    val id: UUID,
    val attemptNumber: Int,
    val workerName: String,
    val isolationMode: String,
    val leasedAt: Instant,
    val leaseExpiresAt: Instant,
    val heartbeatAt: Instant?,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    val outcome: String?,
    val exitCode: Int?,
    val durationMs: Long?,
    val environment: Map<String, Any?>?,
    val errorMessage: String?,
)

data class JobEvent(
    val id: Long,
    val attemptId: UUID?,
    val eventType: String,
    val message: String,
    val data: Map<String, Any?>,
    val occurredAt: Instant,
)

data class JobArtifact(
    val id: UUID,
    val attemptId: UUID,
    val kind: String,
    val name: String,
    val mediaType: String,
    val sizeBytes: Long,
    val sha256: String,
    val truncated: Boolean,
    val createdAt: Instant,
)
