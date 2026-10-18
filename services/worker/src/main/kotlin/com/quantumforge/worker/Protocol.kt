package com.quantumforge.worker

import java.time.Instant
import java.util.UUID

// Wire types of the internal worker protocol. They mirror the control plane's
// DispatchService DTOs; unknown fields are ignored so the API can add fields.

data class LeasedJob(
    val attemptId: UUID,
    val attemptNumber: Int,
    val leaseToken: String,
    val leaseExpiresAt: Instant,
    val heartbeatIntervalSeconds: Long,
    val jobId: UUID,
    val jobType: String,
    val config: TestExecutionConfig,
    val testPatterns: List<String>,
    val timeoutSeconds: Int,
    val snapshotDigest: String,
    val snapshotSizeBytes: Long,
)

data class TestExecutionConfig(
    val preset: String = "NODE_TEST",
    val testPatterns: List<String> = emptyList(),
    val timeoutSeconds: Int? = null,
    val memoryMb: Int = 512,
    val cpuMillis: Int = 1000,
    val maxOutputBytes: Long = 1024 * 1024,
    val fuzz: FuzzSettings? = null,
    val mutation: MutationSettings? = null,
)

data class MutationSettings(val seed: Long = 1, val maxMutants: Int = 500)

data class FuzzSettings(
    val seed: Long = 1,
    val iterationsPerTarget: Int = 300,
    val callTimeoutMs: Int = 1000,
    /** Previously failing inputs; passed to the fuzzer as-is. */
    val corpus: List<Map<String, Any?>> = emptyList(),
)

data class HeartbeatResult(val cancelRequested: Boolean, val leaseExpiresAt: Instant)

enum class AttemptOutcome { SUCCEEDED, FAILED, TIMED_OUT, CANCELLED, INFRA_ERROR }

enum class TestStatus { PASSED, FAILED, SKIPPED, ERRORED }

data class TestCaseReport(
    val suite: String?,
    val name: String,
    val status: TestStatus,
    val durationMs: Long?,
    val failureMessage: String?,
    val failureDetail: String?,
)

data class TestRunReport(val framework: String, val durationMs: Long?, val cases: List<TestCaseReport>)

data class CompletionReport(
    val outcome: AttemptOutcome,
    val exitCode: Int?,
    val durationMs: Long?,
    val errorMessage: String?,
    val testRun: TestRunReport?,
)

data class CompletionResult(val jobState: String, val retried: Boolean)

/** The control plane says this attempt is no longer ours; stop and discard. */
class LeaseLostException(message: String) : RuntimeException(message)
