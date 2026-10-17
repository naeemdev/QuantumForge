package com.quantumforge.api.security

import com.quantumforge.api.common.ApiError
import com.quantumforge.api.common.RequestIdFilter
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.Instant

/** Writes the standard error envelope from filters, which run before MVC advice. */
@Component
class SecurityErrorWriter(private val objectMapper: ObjectMapper) {
    fun write(request: HttpServletRequest, response: HttpServletResponse, status: Int, code: String, message: String) {
        response.status = status
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        if (status == 401) response.setHeader("WWW-Authenticate", "Bearer")
        val body = ApiError(ApiError.Body(code, message, status, RequestIdFilter.current(request), Instant.now()))
        objectMapper.writeValue(response.outputStream, body)
    }
}
