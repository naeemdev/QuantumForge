package com.quantumforge.api.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Worker endpoints are served only on the internal port and user endpoints only
 * on the public port. Ingress forwards the public port alone, so the `/internal/` routes
 * are unreachable from the internet even if routing is misconfigured upstream.
 */
class PortSeparationFilter(
    private val internalPort: () -> Int,
    private val errorWriter: SecurityErrorWriter,
) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val onInternalPort = request.localPort == internalPort()
        val path = request.requestURI
        val isInternalPath = path.startsWith("/internal/")
        val isHealth = path.startsWith("/health/")
        if (!isHealth && onInternalPort != isInternalPath) {
            errorWriter.write(request, response, 404, "not_found", "No such endpoint")
            return
        }
        chain.doFilter(request, response)
    }
}
