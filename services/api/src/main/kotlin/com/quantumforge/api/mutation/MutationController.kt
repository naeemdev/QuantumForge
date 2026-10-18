package com.quantumforge.api.mutation

import com.quantumforge.api.access.AccessService
import com.quantumforge.api.access.Permission
import com.quantumforge.api.common.BadRequestException
import com.quantumforge.api.common.NotFoundException
import com.quantumforge.api.common.Page
import com.quantumforge.api.common.PageRequest
import com.quantumforge.api.jobs.ExecutionConfig
import com.quantumforge.api.jobs.ExecutionPreset
import com.quantumforge.api.jobs.Job
import com.quantumforge.api.jobs.JobArtifact
import com.quantumforge.api.jobs.JobAttempt
import com.quantumforge.api.jobs.JobFilter
import com.quantumforge.api.jobs.JobService
import com.quantumforge.api.jobs.JobType
import com.quantumforge.api.jobs.MutationSettings
import com.quantumforge.api.jobs.SubmitJobCommand
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
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

data class SubmitMutationRunRequest(
    val snapshotId: UUID? = null,
    /** Test files to run against each mutant; defaults to the node:test conventions. */
    @field:Size(max = 20) val testPatterns: List<String> = emptyList(),
    @field:Min(1) @field:Max(Int.MAX_VALUE.toLong()) val seed: Long = 1,
    @field:Min(1) @field:Max(5000) val maxMutants: Int = 500,
    @field:Min(1) @field:Max(1800) val timeoutSeconds: Int? = null,
    @field:Min(-100) @field:Max(100) val priority: Int = 0,
)

data class MutationRunSummary(val job: Job, val run: MutationRunView?)

data class MutationRunDetail(val job: Job, val attempts: List<JobAttempt>, val run: MutationRunView?, val mutants: List<MutantView>)

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Break My Code")
class MutationController(
    private val jobs: JobService,
    private val mutation: MutationService,
    private val access: AccessService,
) {
    @PostMapping("/projects/{projectId}/mutation-runs")
    @Operation(
        summary = "Queue a mutation testing run against a snapshot (defaults to the latest). Supports Idempotency-Key.",
        description = "Each mutant is checked against the project's node:test suite. Only tests that pass on the unmutated code can kill a mutant.",
    )
    fun submit(
        @PathVariable projectId: UUID,
        @RequestHeader(name = "Idempotency-Key", required = false) idempotencyKey: String?,
        @Valid @RequestBody(required = false) body: SubmitMutationRunRequest?,
    ): ResponseEntity<Job> {
        val ctx = access.requireForProject(projectId, Permission.JOB_SUBMIT)
        val request = body ?: SubmitMutationRunRequest()
        val key = idempotencyKey?.trim()?.also {
            if (it.isEmpty() || it.length > 200 || !Regex("^[\\x21-\\x7E]+$").matches(it)) {
                throw BadRequestException("invalid_idempotency_key", "Idempotency-Key must be 1-200 printable ASCII characters")
            }
        }
        val config = ExecutionConfig(
            preset = ExecutionPreset.TS_MUTATION,
            testPatterns = request.testPatterns,
            timeoutSeconds = request.timeoutSeconds ?: DEFAULT_TIMEOUT_SECONDS,
            memoryMb = 2048,
            cpuMillis = 2000,
            mutation = MutationSettings(request.seed, request.maxMutants),
        )
        val result = jobs.submit(ctx, SubmitJobCommand(JobType.MUTATION_TEST, projectId, request.snapshotId, config, request.priority, key))
        return ResponseEntity.status(if (result.created) HttpStatus.CREATED else HttpStatus.OK)
            .location(URI.create("/api/v1/mutation-runs/${result.job.id}")).body(result.job)
    }

    @GetMapping("/projects/{projectId}/mutation-runs")
    @Operation(summary = "Mutation testing runs of a project, newest first")
    fun list(@PathVariable projectId: UUID, @RequestParam(required = false) page: Int?, @RequestParam(required = false) size: Int?): Page<MutationRunSummary> {
        val ctx = access.requireForProject(projectId, Permission.PROJECT_READ)
        val result = jobs.list(ctx, JobFilter(projectId, emptyList(), JobType.MUTATION_TEST), PageRequest.of(page, size))
        val runs = mutation.runsForJobs(ctx, result.items.map { it.id })
        return Page(result.items.map { MutationRunSummary(it, runs[it.id]) }, result.page, result.size, result.total)
    }

    @GetMapping("/mutation-runs/{id}")
    @Operation(summary = "A mutation run by job id: baseline, score and every mutant's outcome (survivors first)")
    fun get(@PathVariable id: UUID): MutationRunDetail {
        val ctx = access.requireForJob(id, Permission.JOB_READ)
        val job = jobs.get(ctx, id)
        if (job.jobType != JobType.MUTATION_TEST) throw NotFoundException("mutation run", id)
        val run = mutation.runForJob(ctx, id)
        return MutationRunDetail(job, jobs.attempts(ctx, id), run, run?.let { mutation.mutants(ctx, it.id) }.orEmpty())
    }

    @GetMapping("/mutation-runs/{id}/artifacts")
    fun artifacts(@PathVariable id: UUID): List<JobArtifact> = jobs.artifacts(access.requireForJob(id, Permission.JOB_READ), id)

    companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 900
    }
}
