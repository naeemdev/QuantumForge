package com.quantumforge.api.security

import com.quantumforge.api.config.QuantumForgeProperties
import com.quantumforge.api.identity.ApiTokenService
import com.quantumforge.api.identity.UserService
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Authenticates requests under `/api/` from a bearer token: either an OIDC/dev JWT or
 * a QuantumForge personal access token. A missing token leaves the request
 * anonymous (the security chain then answers 401); an invalid token is rejected
 * immediately so a bad credential never silently degrades to anonymous.
 */
class ApiAuthenticationFilter(
    private val jwtDecoder: JwtDecoder,
    private val userService: UserService,
    private val apiTokenService: ApiTokenService,
    private val properties: QuantumForgeProperties,
    private val errorWriter: SecurityErrorWriter,
) : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun shouldNotFilter(request: HttpServletRequest) = !request.requestURI.startsWith("/api/")

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val token = bearerToken(request)
        if (token == null) {
            chain.doFilter(request, response)
            return
        }

        val caller = try {
            if (token.startsWith(ApiTokenService.TOKEN_PREFIX)) {
                apiTokenService.authenticate(token)
            } else {
                authenticateJwt(token)
            }
        } catch (e: JwtException) {
            log.debug("Rejected JWT: {}", e.message)
            null
        } catch (e: IllegalArgumentException) {
            log.info("Rejected malformed bearer token: {}", e.message)
            null
        }

        if (caller == null) {
            errorWriter.write(request, response, 401, "invalid_token", "The bearer token is invalid, expired or revoked")
            return
        }

        SecurityContextHolder.getContext().authentication = CallerAuthentication(caller)
        try {
            chain.doFilter(request, response)
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    private fun authenticateJwt(token: String): Caller? {
        val jwt = jwtDecoder.decode(token)
        val issuer = jwt.getClaimAsString("iss") ?: return null
        val subject = jwt.subject ?: return null
        val method = if (issuer == properties.auth.devLogin.issuer) AuthMethod.DEV_LOGIN else AuthMethod.OIDC
        val email = jwt.getClaimAsString(properties.auth.oidc.emailClaim)
        val name = jwt.getClaimAsString(properties.auth.oidc.nameClaim)
        val user = userService.provision(issuer, subject, email, name) ?: return null
        return Caller(user.id, user.email, user.displayName, method)
    }

    companion object {
        fun bearerToken(request: HttpServletRequest): String? {
            val header = request.getHeader("Authorization") ?: return null
            if (!header.regionMatches(0, "Bearer ", 0, 7, ignoreCase = true)) return null
            return header.substring(7).trim().takeIf { it.isNotEmpty() && it.length <= 8192 }
        }
    }
}
