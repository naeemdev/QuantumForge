package com.quantumforge.api.common

import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.multipart.MaxUploadSizeExceededException
import org.springframework.web.multipart.MultipartException
import org.springframework.web.multipart.support.MissingServletRequestPartException
import org.springframework.web.servlet.resource.NoResourceFoundException
import java.time.Instant

@RestControllerAdvice
class GlobalExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(ApiException::class)
    fun handleApi(ex: ApiException, request: HttpServletRequest): ResponseEntity<ApiError> {
        if (ex.status.is5xxServerError) log.error("API error {}", ex.code, ex) else log.debug("API error {}: {}", ex.code, ex.message)
        return build(ex.status, ex.code, ex.message ?: ex.code, request, ex.details)
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException, request: HttpServletRequest): ResponseEntity<ApiError> {
        val fields = ex.bindingResult.fieldErrors.map { ApiError.FieldError(it.field, it.defaultMessage ?: "invalid") }
        return build(HttpStatus.BAD_REQUEST, "validation_failed", "Request validation failed", request, fieldErrors = fields)
    }

    @ExceptionHandler(ConstraintViolationException::class)
    fun handleConstraint(ex: ConstraintViolationException, request: HttpServletRequest): ResponseEntity<ApiError> {
        val fields = ex.constraintViolations.map { ApiError.FieldError(it.propertyPath.toString(), it.message) }
        return build(HttpStatus.BAD_REQUEST, "validation_failed", "Request validation failed", request, fieldErrors = fields)
    }

    @ExceptionHandler(
        HttpMessageNotReadableException::class,
        MissingServletRequestParameterException::class,
        MissingServletRequestPartException::class,
        MethodArgumentTypeMismatchException::class,
    )
    fun handleMalformed(ex: Exception, request: HttpServletRequest): ResponseEntity<ApiError> {
        val message = when (ex) {
            is MissingServletRequestParameterException -> "Missing parameter '${ex.parameterName}'"
            is MissingServletRequestPartException -> "Missing multipart part '${ex.requestPartName}'"
            is MethodArgumentTypeMismatchException -> "Invalid value for '${ex.name}'"
            else -> "Malformed request body"
        }
        return build(HttpStatus.BAD_REQUEST, "bad_request", message, request)
    }

    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun handleUploadSize(ex: MaxUploadSizeExceededException, request: HttpServletRequest) =
        build(HttpStatus.CONTENT_TOO_LARGE, "payload_too_large", "Upload exceeds the configured size limit", request)

    @ExceptionHandler(MultipartException::class)
    fun handleMultipart(ex: MultipartException, request: HttpServletRequest) =
        build(HttpStatus.BAD_REQUEST, "bad_multipart", "Invalid multipart request", request)

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun handleMethod(ex: HttpRequestMethodNotSupportedException, request: HttpServletRequest) =
        build(HttpStatus.METHOD_NOT_ALLOWED, "method_not_allowed", "Method not allowed", request)

    @ExceptionHandler(HttpMediaTypeNotSupportedException::class)
    fun handleMediaType(ex: HttpMediaTypeNotSupportedException, request: HttpServletRequest) =
        build(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported_media_type", "Unsupported content type", request)

    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNoResource(ex: NoResourceFoundException, request: HttpServletRequest) =
        build(HttpStatus.NOT_FOUND, "not_found", "No such endpoint", request)

    @ExceptionHandler(DuplicateKeyException::class)
    fun handleDuplicate(ex: DuplicateKeyException, request: HttpServletRequest): ResponseEntity<ApiError> {
        log.info("Unique constraint violation: {}", ex.mostSpecificCause.message)
        return build(HttpStatus.CONFLICT, "conflict", "A resource with the same identifying values already exists", request)
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(ex: Exception, request: HttpServletRequest): ResponseEntity<ApiError> {
        log.error("Unhandled exception on {} {}", request.method, request.requestURI, ex)
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "An unexpected error occurred", request)
    }

    private fun build(
        status: HttpStatus,
        code: String,
        message: String,
        request: HttpServletRequest,
        details: Map<String, Any?> = emptyMap(),
        fieldErrors: List<ApiError.FieldError> = emptyList(),
    ): ResponseEntity<ApiError> = ResponseEntity.status(status).body(
        ApiError(
            ApiError.Body(
                code = code,
                message = message,
                status = status.value(),
                requestId = RequestIdFilter.current(request),
                timestamp = Instant.now(),
                details = details,
                fieldErrors = fieldErrors,
            ),
        ),
    )
}
