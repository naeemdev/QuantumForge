package com.quantumforge.api.analysis

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
import com.quantumforge.api.jobs.JobService
import com.quantumforge.api.jobs.JobType
import com.quantumforge.api.jobs.SubmitJobCommand
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
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

data class SubmitAnalysisRequest(
    val snapshotId: UUID? = null,
    @field:Min(1) @field:Max(1800) val timeoutSeconds: Int? = null,
    @field:Min(-100) @field:Max(100) val priority: Int = 0,
)

data class AnalysisDetail(val job: Job, val attempts: List<JobAttempt>, val index: CodeIndexView?, val findings: FindingCounts?)

data class FindingStatusRequest(@field:NotNull val status: FindingStatus, @field:Size(max = 2000) val reason: String? = null, val version: Long)

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Code intelligence")
class AnalysisController(
    private val jobs: JobService,
    private val code: CodeIntelligenceService,
    private val findings: FindingService,
    private val access: AccessService,
) {
    @PostMapping("/projects/{projectId}/analyses")
    @Operation(summary = "Queue a static analysis of a snapshot (defaults to the latest). Supports Idempotency-Key.")
    fun submit(
        @PathVariable projectId: UUID,
        @RequestHeader(name = "Idempotency-Key", required = false) idempotencyKey: String?,
        @Valid @RequestBody(required = false) body: SubmitAnalysisRequest?,
    ): ResponseEntity<Job> {
        val ctx = access.requireForProject(projectId, Permission.JOB_SUBMIT)
        val request = body ?: SubmitAnalysisRequest()
        val key = idempotencyKey?.trim()?.also {
            if (it.isEmpty() || it.length > 200 || !Regex("^[\\x21-\\x7E]+$").matches(it)) {
                throw BadRequestException("invalid_idempotency_key", "Idempotency-Key must be 1-200 printable ASCII characters")
            }
        }
        val config = ExecutionConfig(preset = ExecutionPreset.TS_ANALYSIS, timeoutSeconds = request.timeoutSeconds, memoryMb = 1024)
        val result = jobs.submit(ctx, SubmitJobCommand(JobType.STATIC_ANALYSIS, projectId, request.snapshotId, config, request.priority, key))
        return ResponseEntity.status(if (result.created) HttpStatus.CREATED else HttpStatus.OK)
            .location(URI.create("/api/v1/analyses/${result.job.id}")).body(result.job)
    }

    @GetMapping("/analyses/{id}")
    fun get(@PathVariable id: UUID): AnalysisDetail {
        val ctx = access.requireForJob(id, Permission.JOB_READ)
        val job = jobs.get(ctx, id)
        if (job.jobType != JobType.STATIC_ANALYSIS) throw NotFoundException("analysis", id)
        val index = code.indexForJob(ctx, id)
        return AnalysisDetail(job, jobs.attempts(ctx, id), index, index?.let { analysisCounts(ctx, id) })
    }

    private fun analysisCounts(ctx: com.quantumforge.api.access.OrgAccess, jobId: UUID): FindingCounts {
        val occurrences = findings.occurrencesForJob(ctx, jobId, PageRequest(0, PageRequest.MAX_SIZE)).items
        return FindingCounts(
            occurrences.groupingBy { it.severity }.eachCount().mapValues { it.value.toLong() },
            occurrences.groupingBy { it.status.name }.eachCount().mapValues { it.value.toLong() },
            occurrences.groupingBy { it.ruleId }.eachCount().mapValues { it.value.toLong() },
        )
    }

    @GetMapping("/analyses/{id}/findings")
    fun analysisFindings(@PathVariable id: UUID, @RequestParam(required = false) page: Int?, @RequestParam(required = false) size: Int?): Page<FindingOccurrenceView> =
        findings.occurrencesForJob(access.requireForJob(id, Permission.JOB_READ), id, PageRequest.of(page, size))

    @GetMapping("/analyses/{id}/artifacts")
    fun artifacts(@PathVariable id: UUID): List<JobArtifact> = jobs.artifacts(access.requireForJob(id, Permission.JOB_READ), id)

    @GetMapping("/projects/{id}/code-index")
    fun index(@PathVariable id: UUID, @RequestParam(required = false) snapshotId: UUID?): CodeIndexView =
        code.latestIndex(access.requireForProject(id, Permission.PROJECT_READ), id, snapshotId) ?: throw NotFoundException("code index", snapshotId ?: id)

    @GetMapping("/projects/{id}/symbols")
    fun symbols(
        @PathVariable id: UUID,
        @RequestParam(required = false) snapshotId: UUID?,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) kind: String?,
        @RequestParam(required = false) path: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?,
    ): Page<CodeSymbolView> = code.symbols(access.requireForProject(id, Permission.PROJECT_READ), id, snapshotId, q, kind, path, PageRequest.of(page, size))

    @GetMapping("/projects/{id}/dependencies")
    fun dependencies(@PathVariable id: UUID, @RequestParam(required = false) snapshotId: UUID?): DependencyGraph =
        code.dependencies(access.requireForProject(id, Permission.PROJECT_READ), id, snapshotId)

    @GetMapping("/projects/{id}/calls")
    fun calls(@PathVariable id: UUID, @RequestParam(required = false) snapshotId: UUID?, @RequestParam(required = false) symbol: String?): List<CallEdgeView> =
        code.calls(access.requireForProject(id, Permission.PROJECT_READ), id, snapshotId, symbol)

    @GetMapping("/projects/{id}/annotations")
    fun annotations(@PathVariable id: UUID, @RequestParam snapshotId: UUID, @RequestParam path: String): FileAnnotations =
        code.annotations(access.requireForProject(id, Permission.PROJECT_READ), id, snapshotId, path, findings)

    @GetMapping("/projects/{id}/findings")
    fun projectFindings(
        @PathVariable id: UUID,
        @RequestParam(required = false) status: List<FindingStatus>?,
        @RequestParam(required = false) severity: List<String>?,
        @RequestParam(required = false) ruleId: String?,
        @RequestParam(required = false) path: String?,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) source: FindingSource?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?,
    ): Page<FindingView> {
        val ctx = access.requireForProject(id, Permission.PROJECT_READ)
        return findings.list(ctx, id, FindingFilter(status.orEmpty(), severity.orEmpty(), ruleId, path, q, source), PageRequest.of(page, size))
    }

    @GetMapping("/projects/{id}/finding-counts")
    fun projectFindingCounts(@PathVariable id: UUID, @RequestParam(defaultValue = "true") active: Boolean): FindingCounts =
        findings.counts(access.requireForProject(id, Permission.PROJECT_READ), id, active)

    @GetMapping("/findings/{id}")
    fun finding(@PathVariable id: UUID): FindingDetail {
        val orgId = access.organizationOfFinding(id)
        return findings.get(access.requireOwned("finding", id, orgId, Permission.PROJECT_READ), id)
    }

    @PostMapping("/findings/{id}/status")
    fun changeStatus(@PathVariable id: UUID, @Valid @RequestBody body: FindingStatusRequest): FindingDetail {
        val orgId = access.organizationOfFinding(id)
        return findings.changeStatus(access.requireOwned("finding", id, orgId, Permission.FINDING_TRIAGE), id, body.status, body.reason, body.version)
    }

    @GetMapping("/rules")
    fun rules(): List<RuleView> = findings.rules()
}
