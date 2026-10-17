package com.quantumforge.api.config

import org.springframework.beans.factory.InitializingBean
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.stereotype.Component

/**
 * Fails startup when the prod profile is combined with settings that are only
 * acceptable on a developer machine.
 */
@Component
class ProductionSafetyCheck(private val properties: QuantumForgeProperties, private val environment: Environment) : InitializingBean {
    override fun afterPropertiesSet() {
        if (!environment.acceptsProfiles(Profiles.of("prod"))) return
        val problems = buildList {
            if (properties.auth.devLogin.enabled) add("quantumforge.auth.dev-login.enabled must be false")
            if (properties.execution.allowDevIsolation) add("quantumforge.execution.allow-dev-isolation must be false")
            if (properties.auth.oidc.issuerUri.isNullOrBlank()) add("quantumforge.auth.oidc.issuer-uri must be set")
            if (properties.storage.type == "filesystem") add("quantumforge.storage.type must be s3 in production")
            properties.execution.bootstrapWorkers.filter { it.token.length < 32 }.forEach { add("bootstrap worker '${it.name}' token is too short") }
        }
        check(problems.isEmpty()) { "Refusing to start with the prod profile:\n - " + problems.joinToString("\n - ") }
    }
}
