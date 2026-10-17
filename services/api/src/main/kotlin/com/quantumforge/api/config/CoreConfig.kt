package com.quantumforge.api.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.info.License
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springdoc.core.models.GroupedOpenApi
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration
class CoreConfig {
    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun openApi(): OpenAPI = OpenAPI()
        .info(
            Info().title("QuantumForge API").version("v1")
                .description("Public REST API of the QuantumForge control plane. Worker endpoints are not part of this document.")
                .license(License().name("Proprietary")),
        )
        .components(Components().addSecuritySchemes("bearer", SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer")))
        .addSecurityItem(SecurityRequirement().addList("bearer"))

    /** Only the public API is documented; internal worker routes stay out of the contract. */
    @Bean
    fun publicApiGroup(): GroupedOpenApi = GroupedOpenApi.builder()
        .group("public")
        .pathsToMatch("/api/v1/**", "/health/**")
        .addOpenApiCustomizer(requiredResponseFields())
        .build()

    /**
     * Responses always serialise every property, so a property whose schema does
     * not allow null is present in every response: mark it required. Request
     * schemas are left alone because Kotlin defaults make their fields optional.
     */
    private fun requiredResponseFields() = OpenApiCustomizer { api ->
        api.components?.schemas?.forEach { (name, schema) ->
            if (name.endsWith("Request")) return@forEach
            val required = schema.properties.orEmpty()
                .filter { (_, property) -> property.types?.contains("null") != true && property.nullable != true }
                .keys.sorted()
            if (required.isNotEmpty()) schema.required = required
        }
    }
}
