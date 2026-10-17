package com.quantumforge.api.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-caller token bucket for the public API. In-memory, so limits apply per API
 * instance; a shared store (Redis or PostgreSQL) replaces this when the API is
 * scaled horizontally. Unauthenticated requests are keyed by client address.
 */
@Component
class RateLimitFilter(
    @Value("\${quantumforge.rate-limit.requests-per-minute:600}") private val requestsPerMinute: Int,
    @Value("\${quantumforge.rate-limit.burst:120}") private val burst: Int,
    private val errorWriter: SecurityErrorWriter,
) : OncePerRequestFilter() {
    private class Bucket(var tokens: Double, var updatedAtNanos: Long)

    private val buckets = ConcurrentHashMap<String, Bucket>()

    override fun shouldNotFilter(request: HttpServletRequest) = !request.requestURI.startsWith("/api/")

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        if (requestsPerMinute <= 0) {
            chain.doFilter(request, response)
            return
        }
        val auth = SecurityContextHolder.getContext().authentication
        val key = (auth as? CallerAuthentication)?.caller?.userId?.toString() ?: "ip:${request.remoteAddr}"
        if (!tryAcquire(key)) {
            response.setHeader("Retry-After", "1")
            errorWriter.write(request, response, 429, "rate_limited", "Too many requests")
            return
        }
        chain.doFilter(request, response)
    }

    internal fun tryAcquire(key: String, nowNanos: Long = System.nanoTime()): Boolean {
        if (buckets.size > 100_000) buckets.clear() // crude bound on memory under key spraying
        val bucket = buckets.computeIfAbsent(key) { Bucket(burst.toDouble(), nowNanos) }
        synchronized(bucket) {
            val refill = (nowNanos - bucket.updatedAtNanos) / 60_000_000_000.0 * requestsPerMinute
            bucket.tokens = minOf(burst.toDouble(), bucket.tokens + refill)
            bucket.updatedAtNanos = nowNanos
            if (bucket.tokens < 1.0) return false
            bucket.tokens -= 1.0
            return true
        }
    }
}
