package com.quantumforge.api.config

import com.quantumforge.api.identity.ApiTokenService
import com.quantumforge.api.identity.UserService
import com.quantumforge.api.jobs.WorkerRegistry
import com.quantumforge.api.security.ApiAuthenticationFilter
import com.quantumforge.api.security.PortSeparationFilter
import com.quantumforge.api.security.RateLimitFilter
import com.quantumforge.api.security.SecurityErrorWriter
import com.quantumforge.api.security.WorkerAuthenticationFilter
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter
import org.springframework.security.web.context.SecurityContextHolderFilter
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

/**
 * The API is stateless and authenticated only by bearer tokens carried in the
 * Authorization header. No cookies are issued, so CSRF protection is not needed
 * and is disabled deliberately (see docs/SECURITY_MODEL.md).
 */
@Configuration
class SecurityConfig(
    private val properties: QuantumForgeProperties,
    private val internalPort: InternalPortHolder,
) {
    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        jwtDecoder: JwtDecoder,
        userService: UserService,
        apiTokenService: ApiTokenService,
        workerRegistry: WorkerRegistry,
        errorWriter: SecurityErrorWriter,
        rateLimitFilter: RateLimitFilter,
    ): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .cors { it.configurationSource(corsSource()) }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .logout { it.disable() }
            .requestCache { it.disable() }
            .headers { headers ->
                headers.contentSecurityPolicy { it.policyDirectives("default-src 'none'; frame-ancestors 'none'") }
                headers.referrerPolicy { it.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER) }
                headers.frameOptions { it.deny() }
            }
            .exceptionHandling { ex ->
                ex.authenticationEntryPoint { request, response, _ ->
                    errorWriter.write(request, response, 401, "unauthenticated", "Authentication required")
                }
                ex.accessDeniedHandler { request, response, _ ->
                    errorWriter.write(request, response, 403, "forbidden", "Access denied")
                }
            }
            .addFilterAfter(PortSeparationFilter({ internalPort.port }, errorWriter), SecurityContextHolderFilter::class.java)
            .addFilterBefore(
                ApiAuthenticationFilter(jwtDecoder, userService, apiTokenService, properties, errorWriter),
                AnonymousAuthenticationFilter::class.java,
            )
            .addFilterBefore(WorkerAuthenticationFilter(workerRegistry, errorWriter), AnonymousAuthenticationFilter::class.java)
            .addFilterAfter(rateLimitFilter, AnonymousAuthenticationFilter::class.java)
            .authorizeHttpRequests { auth ->
                auth.requestMatchers("/health/live", "/health/ready").permitAll()
                auth.requestMatchers("/v3/api-docs", "/v3/api-docs/**").permitAll()
                if (properties.auth.devLogin.enabled) {
                    auth.requestMatchers(HttpMethod.POST, "/api/v1/dev/session").permitAll()
                }
                auth.requestMatchers(HttpMethod.GET, "/api/v1/auth/config").permitAll()
                auth.requestMatchers("/internal/**").hasRole("WORKER")
                auth.requestMatchers("/api/**").hasRole("USER")
                auth.anyRequest().denyAll()
            }
        return http.build()
    }

    /** RateLimitFilter is a bean only so it can be configured; it must run inside the security chain, not twice. */
    @Bean
    fun rateLimitFilterRegistration(filter: RateLimitFilter) =
        FilterRegistrationBean(filter).apply { isEnabled = false }

    private fun corsSource(): UrlBasedCorsConfigurationSource {
        val config = CorsConfiguration().apply {
            allowedOrigins = properties.cors.allowedOrigins
            allowedMethods = listOf("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS")
            allowedHeaders = listOf("Authorization", "Content-Type", "Idempotency-Key", "X-Request-Id")
            exposedHeaders = listOf("X-Request-Id", "Location")
            allowCredentials = false
            maxAge = 3600
        }
        return UrlBasedCorsConfigurationSource().apply { registerCorsConfiguration("/api/**", config) }
    }
}
