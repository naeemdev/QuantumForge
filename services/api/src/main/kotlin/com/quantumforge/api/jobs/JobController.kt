package com.quantumforge.api.jobs

import com.quantumforge.api.access.AccessService
import com.quantumforge.api.access.Permission
import com.quantumforge.api.common.BadRequestException
import com.quantumforge.api.common.Page
import com.quantumforge.api.common.PageRequest
import com.quantumforge.api.testing.TestResultStore
import com.quantumforge.api.testing.TestRunView
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.slf4j.LoggerFactory
import org.springframework.core.io.InputStreamResource
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.net.URI
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class SubmitTestRunRequest(
    val snapshotId: UUID? = null,
    val config: TestExecutionConfig = TestExecutionConfig(),
    @field:Min(-100) @field:Max(100) val priority: Int = 0,
)

data class JobDetail(val job: Job, val attempts: List<JobAttempt>)

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Jobs")
class JobController(
    private val jobs: JobService,
    private val tests: TestResultStore,
    private val access: AccessService,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val streamExecutor = Executors.newScheduledThreadPool(2) { r -> Thread(r, "job-sse").apply { isDaemon = true } }

    @PostMapping("/projects/{projectId}/test-runs")
    @Operation(summary = "Queue a test execution job for a snapshot (defaults to the latest). Supports the Idempotency-Key header.")
    fun submitTestRun(
        @PathVariable projectId: UUID,
        @RequestHeader(name = "Idempotency-Key", required = false) idempotencyKey: String?,
        @Valid @RequestBody(required = false) body: SubmitTestRunRequest?,
    ): ResponseEntity<Job> {
        val ctx = access.requireForProject(projectId, Permission.JOB_SUBMIT)
        val key = idempotencyKey?.trim()?.also {
            if (it.isEmpty() || it.length > 200 || !Regex("^[\\x21-\\x7E]+$").matches(it)) {
                throw BadRequestException("invalid_idempotency_key", "Idempotency-Key must be 1-200 printable ASCII characters")
            }
        }
        val request = body ?: SubmitTestRunRequest()
        val result = jobs.submit(ctx, SubmitJobCommand(projectId, request.snapshotId, request.config, request.priority, key))
        val status = if (result.created) HttpStatus.CREATED else HttpStatus.OK
        return ResponseEntity.status(status).location(URI.create("/api/v1/jobs/${result.job.id}")).body(result.job)
    }

    @GetMapping("/jobs")
    fun list(
        @RequestParam organizationId: UUID,
        @RequestParam(required = false) projectId: UUID?,
        @RequestParam(required = false) state: List<JobState>?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?,
    ): Page<Job> = jobs.list(access.require(organizationId, Permission.JOB_READ), JobFilter(projectId, state.orEmpty()), PageRequest.of(page, size))

    @GetMapping("/jobs/{id}")
    fun get(@PathVariable id: UUID): JobDetail {
        val ctx = access.requireForJob(id, Permission.JOB_READ)
        return JobDetail(jobs.get(ctx, id), jobs.attempts(ctx, id))
    }

    @PostMapping("/jobs/{id}/cancel")
    fun cancel(@PathVariable id: UUID): Job = jobs.cancel(access.requireForJob(id, Permission.JOB_CANCEL), id)

    @GetMapping("/jobs/{id}/events")
    fun events(
        @PathVariable id: UUID,
        @RequestParam(required = false) after: Long?,
        @RequestParam(required = false) limit: Int?,
    ): List<JobEvent> = jobs.events(access.requireForJob(id, Permission.JOB_READ), id, after, limit ?: 500)

    /**
     * Server-Sent Events stream of the job timeline. Polls the event table every
     * second for at most 10 minutes and closes once the job is terminal and drained.
     */
    @GetMapping("/jobs/{id}/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun stream(@PathVariable id: UUID, @RequestHeader(name = "Last-Event-ID", required = false) lastEventId: Long?): SseEmitter {
        val ctx = access.requireForJob(id, Permission.JOB_READ)
        jobs.get(ctx, id)
        val emitter = SseEmitter(Duration.ofMinutes(10).toMillis())
        var cursor = lastEventId ?: 0L
        val future = streamExecutor.scheduleWithFixedDelay({
            try {
                val batch = jobs.eventsUnchecked(ctx.tenantId, id, cursor, 200)
                batch.forEach { event ->
                    emitter.send(SseEmitter.event().id(event.id.toString()).name(event.eventType).data(event, MediaType.APPLICATION_JSON))
                    cursor = event.id
                }
                if (batch.isEmpty() && jobs.stateUnchecked(ctx.tenantId, id).terminal) {
                    emitter.send(SseEmitter.event().name("end").data("{}", MediaType.APPLICATION_JSON))
                    emitter.complete()
                    throw StopStream()
                }
            } catch (e: StopStream) {
                throw e
            } catch (e: Exception) {
                log.debug("Closing job stream {}: {}", id, e.message)
                emitter.completeWithError(e)
                throw StopStream()
            }
        }, 0, 1, TimeUnit.SECONDS)
        val stop = Runnable { future.cancel(false) }
        emitter.onCompletion(stop)
        emitter.onTimeout(stop)
        emitter.onError { stop.run() }
        return emitter
    }

    @GetMapping("/jobs/{id}/test-results")
    fun testResults(@PathVariable id: UUID): List<TestRunView> = tests.forJob(access.requireForJob(id, Permission.JOB_READ), id)

    @GetMapping("/jobs/{id}/artifacts")
    fun artifacts(@PathVariable id: UUID): List<JobArtifact> = jobs.artifacts(access.requireForJob(id, Permission.JOB_READ), id)

    /** Streams artifact bytes after authorization; blobs are never exposed by direct URL. */
    @GetMapping("/jobs/{id}/artifacts/{artifactId}/content")
    fun artifactContent(@PathVariable id: UUID, @PathVariable artifactId: UUID): ResponseEntity<InputStreamResource> {
        val (artifact, stream) = jobs.openArtifact(access.requireForJob(id, Permission.JOB_READ), id, artifactId)
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(artifact.mediaType))
            .contentLength(artifact.sizeBytes)
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(artifact.name).build().toString())
            .header("X-Content-Type-Options", "nosniff")
            .header("X-Artifact-Sha256", artifact.sha256)
            .body(InputStreamResource(stream))
    }

    private class StopStream : RuntimeException(null, null, false, false)
}
