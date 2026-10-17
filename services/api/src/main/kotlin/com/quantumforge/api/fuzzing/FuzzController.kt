package com.quantumforge.api.fuzzing

import com.quantumforge.api.access.AccessService
import com.quantumforge.api.access.Permission
import com.quantumforge.api.analysis.ReproductionView
import com.quantumforge.api.common.BadRequestException
import com.quantumforge.api.common.NotFoundException
import com.quantumforge.api.common.Page
import com.quantumforge.api.common.PageRequest
import com.quantumforge.api.jobs.ExecutionConfig
import com.quantumforge.api.jobs.ExecutionPreset
import com.quantumforge.api.jobs.FuzzSettings
import com.quantumforge.api.jobs.Job
import com.quantumforge.api.jobs.JobArtifact
import com.quantumforge.api.jobs.JobAttempt
import com.quantumforge.api.jobs.JobFilter
import com.quantumforge.api.jobs.JobService
import com.quantumforge.api.jobs.JobType
import com.quantumforge.api.jobs.SubmitJobCommand
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.util.UUID

data class SubmitFuzzCampaignRequest(
    val snapshotId: UUID? = null,
    @field:Min(1) @field:Max(Int.MAX_VALUE.toLong()) val seed: Long = 1,
    @field:Min(1) @field:Max(100_000) val iterationsPerTarget: Int = 300,
    @field:Min(50) @field:Max(60_000) val callTimeoutMs: Int = 1000,
    @field:Min(1) @field:Max(1800) val timeoutSeconds: Int? = null,
    @field:Min(-100) @field:Max(100) val priority: Int = 0,
)

data class FuzzCampaignSummary(val job: Job, val campaign: FuzzCampaignView?)

data class FuzzCampaignDetail(
    val job: Job,
    val attempts: List<JobAttempt>,
    val campaign: FuzzCampaignView?,
    val targets: List<FuzzTargetView>,
    val reproductions: List<ReproductionView>,
)

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Break My Code")
class FuzzController(
    private val jobs: JobService,
    private val fuzzing: FuzzingService,
    private val access: AccessService,
) {
    @PostMapping("/projects/{projectId}/fuzz-campaigns")
    @Operation(
        summary = "Queue a fuzz campaign against a snapshot (defaults to the latest). Supports Idempotency-Key.",
        description = "The project's open fuzzing findings are replayed first from their stored reproduction inputs, so a campaign on a fixed snapshot resolves them.",
    )
    fun submit(
        @PathVariable projectId: UUID,
        @RequestHeader(name = "Idempotency-Key", required = false) idempotencyKey: String?,
        @Valid @RequestBody(required = false) body: SubmitFuzzCampaignRequest?,
    ): ResponseEntity<Job> {
        val ctx = access.requireForProject(projectId, Permission.JOB_SUBMIT)
        val request = body ?: SubmitFuzzCampaignRequest()
        val key = idempotencyKey?.trim()?.also {
            if (it.isEmpty() || it.length > 200 || !Regex("^[\\x21-\\x7E]+$").matches(it)) {
                throw BadRequestException("invalid_idempotency_key", "Idempotency-Key must be 1-200 printable ASCII characters")
            }
        }
        val settings = FuzzSettings(request.seed, request.iterationsPerTarget, request.callTimeoutMs, fuzzing.corpusFor(ctx, projectId))
        val config = ExecutionConfig(preset = ExecutionPreset.TS_FUZZ, timeoutSeconds = request.timeoutSeconds ?: DEFAULT_TIMEOUT_SECONDS,
            memoryMb = 1536, cpuMillis = 2000, fuzz = settings)
        val result = jobs.submit(ctx, SubmitJobCommand(JobType.FUZZ_CAMPAIGN, projectId, request.snapshotId, config, request.priority, key))
        return ResponseEntity.status(if (result.created) HttpStatus.CREATED else HttpStatus.OK)
            .location(URI.create("/api/v1/fuzz-campaigns/${result.job.id}")).body(result.job)
    }

    @GetMapping("/projects/{projectId}/fuzz-campaigns")
    @Operation(summary = "Fuzz campaigns of a project, newest first")
    fun list(@PathVariable projectId: UUID, @RequestParam(required = false) page: Int?, @RequestParam(required = false) size: Int?): Page<FuzzCampaignSummary> {
        val ctx = access.requireForProject(projectId, Permission.PROJECT_READ)
        val result = jobs.list(ctx, JobFilter(projectId, emptyList(), JobType.FUZZ_CAMPAIGN), PageRequest.of(page, size))
        val campaigns = fuzzing.campaignsForJobs(ctx, result.items.map { it.id })
        return Page(result.items.map { FuzzCampaignSummary(it, campaigns[it.id]) }, result.page, result.size, result.total)
    }

    @GetMapping("/fuzz-campaigns/{id}")
    @Operation(summary = "A fuzz campaign by job id: targets tested and skipped, and every failure with its reproduction")
    fun get(@PathVariable id: UUID): FuzzCampaignDetail {
        val ctx = access.requireForJob(id, Permission.JOB_READ)
        val job = jobs.get(ctx, id)
        if (job.jobType != JobType.FUZZ_CAMPAIGN) throw NotFoundException("fuzz campaign", id)
        val campaign = fuzzing.campaignForJob(ctx, id)
        return FuzzCampaignDetail(
            job,
            jobs.attempts(ctx, id),
            campaign,
            campaign?.let { fuzzing.targets(ctx, it.id) }.orEmpty(),
            fuzzing.reproductionsForJob(ctx, id),
        )
    }

    @GetMapping("/fuzz-campaigns/{id}/artifacts")
    fun artifacts(@PathVariable id: UUID): List<JobArtifact> = jobs.artifacts(access.requireForJob(id, Permission.JOB_READ), id)

    companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 600
    }
}
