package com.quantumforge.api.common

import org.springframework.http.HttpStatus

/**
 * Base class for errors that map to a well-defined API response. Anything that is
 * not an [ApiException] becomes a 500 with no internal detail leaked.
 */
open class ApiException(
    val status: HttpStatus,
    val code: String,
    message: String,
    val details: Map<String, Any?> = emptyMap(),
) : RuntimeException(message)

/** Also used for resources in another tenant, so callers cannot probe for IDs. */
class NotFoundException(resource: String, id: Any?) :
    ApiException(HttpStatus.NOT_FOUND, "not_found", "$resource not found", mapOf("resource" to resource, "id" to id?.toString()))

class ForbiddenException(message: String = "You do not have permission to perform this action", details: Map<String, Any?> = emptyMap()) :
    ApiException(HttpStatus.FORBIDDEN, "forbidden", message, details)

class UnauthenticatedException(message: String = "Authentication required") :
    ApiException(HttpStatus.UNAUTHORIZED, "unauthenticated", message)

class ConflictException(code: String, message: String, details: Map<String, Any?> = emptyMap()) :
    ApiException(HttpStatus.CONFLICT, code, message, details)

class BadRequestException(code: String, message: String, details: Map<String, Any?> = emptyMap()) :
    ApiException(HttpStatus.BAD_REQUEST, code, message, details)

class PayloadTooLargeException(message: String, details: Map<String, Any?> = emptyMap()) :
    ApiException(HttpStatus.CONTENT_TOO_LARGE, "payload_too_large", message, details)

class QuotaExceededException(message: String, details: Map<String, Any?> = emptyMap()) :
    ApiException(HttpStatus.TOO_MANY_REQUESTS, "quota_exceeded", message, details)

/** The version a client sent no longer matches the stored row. */
class StaleVersionException(resource: String) :
    ApiException(HttpStatus.CONFLICT, "stale_version", "$resource was modified concurrently; reload and retry")
