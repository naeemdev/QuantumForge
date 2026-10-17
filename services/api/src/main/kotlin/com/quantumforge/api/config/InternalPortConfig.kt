package com.quantumforge.api.config

import org.apache.catalina.connector.Connector
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory
import org.springframework.boot.web.server.WebServerFactoryCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component

/** Resolved port of the internal (worker) connector; 0 in config means "random", resolved after start. */
@Component
class InternalPortHolder {
    @Volatile
    var connector: Connector? = null

    val port: Int get() = connector?.localPort ?: -1
}

@Configuration
class InternalPortConfig(private val properties: QuantumForgeProperties, private val holder: InternalPortHolder) {
    @Bean
    fun internalConnectorCustomizer(): WebServerFactoryCustomizer<TomcatServletWebServerFactory> =
        WebServerFactoryCustomizer { factory ->
            val connector = Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL)
            connector.port = properties.internalApi.port
            holder.connector = connector
            factory.addAdditionalConnectors(connector)
        }
}
