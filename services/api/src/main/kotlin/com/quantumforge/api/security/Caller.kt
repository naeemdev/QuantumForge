package com.quantumforge.api.security

import com.quantumforge.api.common.UnauthenticatedException
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import java.util.UUID

enum class AuthMethod { OIDC, DEV_LOGIN, API_TOKEN }

/**
 * The authenticated human (or their API token) behind a request. Authorization
 * decisions are never made from this alone; see AccessService.
 */
data class Caller(
    val userId: UUID,
    val email: String?,
    val displayName: String?,
    val authMethod: AuthMethod,
    /** Set when authenticated by API token: the token is only valid in this organization. */
    val tokenId: UUID? = null,
    val tokenOrganizationId: UUID? = null,
)

class CallerAuthentication(val caller: Caller) : AbstractAuthenticationToken(listOf(SimpleGrantedAuthority("ROLE_USER"))) {
    init {
        isAuthenticated = true
    }

    override fun getCredentials(): Any? = null

    override fun getPrincipal(): Caller = caller
}

data class WorkerIdentity(val workerId: UUID, val name: String, val isolationMode: String)

class WorkerAuthentication(val worker: WorkerIdentity) : AbstractAuthenticationToken(listOf(SimpleGrantedAuthority("ROLE_WORKER"))) {
    init {
        isAuthenticated = true
    }

    override fun getCredentials(): Any? = null

    override fun getPrincipal(): WorkerIdentity = worker
}

object CurrentCaller {
    fun get(): Caller =
        (SecurityContextHolder.getContext().authentication as? CallerAuthentication)?.caller
            ?: throw UnauthenticatedException()

    fun worker(): WorkerIdentity =
        (SecurityContextHolder.getContext().authentication as? WorkerAuthentication)?.worker
            ?: throw UnauthenticatedException("Worker authentication required")
}
