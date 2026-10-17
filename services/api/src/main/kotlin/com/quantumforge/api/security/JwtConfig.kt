package com.quantumforge.api.security

import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.JWTParser
import com.quantumforge.api.config.QuantumForgeProperties
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.BadJwtException
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtDecoders
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.UUID

/**
 * Builds the JWT decoder. Tokens are routed by their (unverified) `iss` claim to
 * the decoder for that issuer; the selected decoder then verifies signature,
 * expiry, issuer and audience. Unknown issuers are rejected outright.
 */
@Configuration
class JwtConfig(private val properties: QuantumForgeProperties) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun devSigningKey(): DevSigningKey? {
        if (!properties.auth.devLogin.enabled) return null
        log.warn("Development login is ENABLED. Anyone who can reach the API can obtain a session. Never enable this in production.")
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        val pair = generator.generateKeyPair()
        val key = RSAKey.Builder(pair.public as RSAPublicKey)
            .privateKey(pair.private as RSAPrivateKey)
            .keyID(UUID.randomUUID().toString())
            .build()
        return DevSigningKey(key)
    }

    @Bean
    fun jwtDecoder(devSigningKey: DevSigningKey?): JwtDecoder {
        val decoders = mutableMapOf<String, Lazy<JwtDecoder>>()

        properties.auth.oidc.issuerUri?.takeIf { it.isNotBlank() }?.let { issuer ->
            decoders[issuer] = lazy {
                val decoder = JwtDecoders.fromIssuerLocation<JwtDecoder>(issuer) as NimbusJwtDecoder
                val validators = mutableListOf<OAuth2TokenValidator<Jwt>>(JwtValidators.createDefaultWithIssuer(issuer))
                properties.auth.oidc.audience?.takeIf { it.isNotBlank() }?.let { validators += AudienceValidator(it) }
                decoder.setJwtValidator(DelegatingOAuth2TokenValidator(validators))
                decoder
            }
        }

        if (devSigningKey != null) {
            val issuer = properties.auth.devLogin.issuer
            decoders[issuer] = lazy {
                val decoder = NimbusJwtDecoder.withPublicKey(devSigningKey.key.toRSAPublicKey())
                    .signatureAlgorithm(SignatureAlgorithm.RS256)
                    .build()
                decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer))
                decoder
            }
        }

        if (decoders.isEmpty()) {
            log.warn("No OIDC issuer configured and dev login disabled: only API tokens can authenticate.")
        }
        return IssuerRoutingJwtDecoder(decoders)
    }
}

class DevSigningKey(val key: RSAKey) {
    fun encoder(): NimbusJwtEncoder = NimbusJwtEncoder(ImmutableJWKSet<SecurityContext>(JWKSet(key)))
}

class IssuerRoutingJwtDecoder(private val decoders: Map<String, Lazy<JwtDecoder>>) : JwtDecoder {
    override fun decode(token: String): Jwt {
        val issuer = try {
            JWTParser.parse(token).jwtClaimsSet.issuer
        } catch (e: Exception) {
            throw BadJwtException("Malformed token")
        }
        val decoder = decoders[issuer] ?: throw BadJwtException("Untrusted issuer")
        return decoder.value.decode(token)
    }
}

class AudienceValidator(private val audience: String) : OAuth2TokenValidator<Jwt> {
    override fun validate(token: Jwt): OAuth2TokenValidatorResult =
        if (token.audience?.contains(audience) == true) {
            OAuth2TokenValidatorResult.success()
        } else {
            OAuth2TokenValidatorResult.failure(OAuth2Error("invalid_token", "Token audience does not include $audience", null))
        }
}
