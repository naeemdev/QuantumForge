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

enum class JobType(val presets: Set<String>) {
    TEST_EXECUTION(setOf("NODE_TEST")),
    STATIC_ANALYSIS(setOf("TS_ANALYSIS")),
    FUZZ_CAMPAIGN(setOf("TS_FUZZ")),
}

/** What a worker may report for an attempt. LEASE_EXPIRED is set only by the reaper. */
enum class AttemptOutcome { SUCCEEDED, FAILED, TIMED_OUT, CANCELLED, INFRA_ERROR, LEASE_EXPIRED }

/**
 * Commands are chosen from presets rather than accepted as free text: the API
 * decides what runs, the worker only fills in paths. New toolchains are added as
 * presets with their own image and limits.
 */
enum class ExecutionPreset(val runtime: String, val description: String) {
    NODE_TEST("node", "Node.js built-in test runner over TypeScript (type stripping) and JavaScript test files"),
    TS_ANALYSIS("node", "QuantumForge TypeScript adapter: indexing, dependency graph and static analysis rules"),
    TS_FUZZ("node", "QuantumForge TypeScript fuzzer: type-driven inputs, shrinking and reproduction of failures"),
}

/** A previously failing input that a campaign replays before generating new ones. */
data class FuzzCorpusEntry(val fingerprint: String, val path: String, val exportName: String, val input: List<Any?>)

data class FuzzSettings(
    val seed: Long = 1,
    val iterationsPerTarget: Int = 300,
    val callTimeoutMs: Int = 1000,
    /** Filled in by the API from the project's open fuzzing findings, never by the caller. */
    val corpus: List<FuzzCorpusEntry> = emptyList(),
) {
    fun validated(): FuzzSettings {
        if (seed !in 1..Int.MAX_VALUE.toLong()) throw BadRequestException("invalid_config", "seed must be between 1 and ${Int.MAX_VALUE}")
        if (iterationsPerTarget !in 1..100_000) throw BadRequestException("invalid_config", "iterationsPerTarget must be between 1 and 100000")
        if (callTimeoutMs !in 50..60_000) throw BadRequestException("invalid_config", "callTimeoutMs must be between 50 and 60000")
        return this
    }
}

/**
 * What a worker runs for a job. For NODE_TEST the patterns select test files;
 * analysis presets ignore them.
 */
data class ExecutionConfig(
    val preset: ExecutionPreset = ExecutionPreset.NODE_TEST,
    /** Glob patterns relative to the snapshot root; defaults to the preset's conventions. */
    val testPatterns: List<String> = emptyList(),
    val timeoutSeconds: Int? = null,
    val memoryMb: Int = 512,
    val cpuMillis: Int = 1000,
    val maxOutputBytes: Long = 1024 * 1024,
    val fuzz: FuzzSettings? = null,
) {
    fun validated(maxTimeout: Int): ExecutionConfig {
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
        fuzz?.validated()
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
    val config: ExecutionConfig,
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
