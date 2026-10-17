package com.quantumforge.api.security

import com.quantumforge.api.jobs.WorkerRegistry
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

/** Authenticates execution workers on `/internal/` routes with their worker token. */
class WorkerAuthenticationFilter(
    private val workerRegistry: WorkerRegistry,
    private val errorWriter: SecurityErrorWriter,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest) = !request.requestURI.startsWith("/internal/")

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val token = ApiAuthenticationFilter.bearerToken(request)
        val worker = token?.let { workerRegistry.authenticate(it) }
        if (worker == null) {
            errorWriter.write(request, response, 401, "invalid_worker_token", "Worker authentication failed")
            return
        }
        SecurityContextHolder.getContext().authentication = WorkerAuthentication(worker)
        try {
            chain.doFilter(request, response)
        } finally {
            SecurityContextHolder.clearContext()
        }
    }
}
