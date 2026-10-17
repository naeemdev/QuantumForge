package com.quantumforge.api.common

import java.time.Instant

/** The single error envelope every endpoint returns. */
data class ApiError(
    val error: Body,
) {
    data class Body(
        val code: String,
        val message: String,
        val status: Int,
        val requestId: String?,
        val timestamp: Instant,
        val details: Map<String, Any?> = emptyMap(),
        val fieldErrors: List<FieldError> = emptyList(),
    )

    data class FieldError(val field: String, val message: String)
}
