package com.quantumforge.api.common

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/**
 * Assigns every request an ID, exposes it in the X-Request-Id header and the
 * logging MDC, and lets error envelopes and audit events refer to it.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestIdFilter : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val incoming = request.getHeader(HEADER)
        val requestId = if (incoming != null && SAFE_ID.matches(incoming)) incoming else UUID.randomUUID().toString()
        request.setAttribute(ATTRIBUTE, requestId)
        response.setHeader(HEADER, requestId)
        MDC.put("requestId", requestId)
        try {
            chain.doFilter(request, response)
        } finally {
            MDC.remove("requestId")
        }
    }

    companion object {
        const val HEADER = "X-Request-Id"
        const val ATTRIBUTE = "qf.requestId"
        private val SAFE_ID = Regex("^[A-Za-z0-9._-]{8,64}$")

        fun current(request: HttpServletRequest?): String? = request?.getAttribute(ATTRIBUTE) as String?
    }
}
