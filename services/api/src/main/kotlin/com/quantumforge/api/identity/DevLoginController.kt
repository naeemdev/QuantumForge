package com.quantumforge.api.identity

import com.quantumforge.api.common.BadRequestException
import com.quantumforge.api.config.QuantumForgeProperties
import com.quantumforge.api.security.DevSigningKey
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Instant

data class DevSessionRequest(
    @field:NotBlank @field:Email @field:Size(max = 320) val email: String,
    @field:Size(max = 200) val displayName: String? = null,
)

data class DevSessionResponse(val accessToken: String, val tokenType: String, val expiresAt: Instant)

/**
 * Issues signed JWTs for any email address so the product can be used locally
 * without an identity provider. Exists only when quantumforge.auth.dev-login.enabled
 * is true, and ProductionSafetyCheck refuses to start the prod profile with it on.
 */
@RestController
@RequestMapping("/api/v1/dev")
@ConditionalOnProperty(prefix = "quantumforge.auth.dev-login", name = ["enabled"], havingValue = "true")
@Tag(name = "Development")
class DevLoginController(
    private val key: DevSigningKey,
    private val properties: QuantumForgeProperties,
    private val clock: Clock,
) {
    @PostMapping("/session")
    @Operation(summary = "Development-only login. Disabled in production.")
    fun session(@Valid @RequestBody body: DevSessionRequest): DevSessionResponse {
        val email = body.email.trim().lowercase()
        if (email.isEmpty()) throw BadRequestException("invalid_email", "Email is required")
        val now = Instant.now(clock)
        val expires = now.plus(properties.auth.devLogin.tokenLifetime)
        val claims = JwtClaimsSet.builder()
            .issuer(properties.auth.devLogin.issuer)
            .subject("dev:$email")
            .issuedAt(now)
            .expiresAt(expires)
            .claim("email", email)
            .claim("name", body.displayName?.trim()?.takeIf { it.isNotEmpty() } ?: email.substringBefore('@'))
            .build()
        val header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(key.key.keyID).build()
        val token = key.encoder().encode(JwtEncoderParameters.from(header, claims)).tokenValue
        return DevSessionResponse(token, "Bearer", expires)
    }
}
