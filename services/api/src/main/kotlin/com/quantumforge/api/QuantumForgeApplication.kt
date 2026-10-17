package com.quantumforge.api

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
class QuantumForgeApplication

fun main(args: Array<String>) {
    runApplication<QuantumForgeApplication>(*args)
}
