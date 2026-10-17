package com.quantumforge.api.common

import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

/** Mapper for jsonb columns. Kept separate from the HTTP mapper on purpose. */
object Json {
    val mapper: JsonMapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

    fun write(value: Any?): String = mapper.writeValueAsString(value)

    inline fun <reified T> read(text: String): T = mapper.readValue(text, T::class.java)

    fun readMap(text: String?): Map<String, Any?> {
        if (text.isNullOrBlank()) return emptyMap()
        @Suppress("UNCHECKED_CAST")
        return mapper.readValue(text, Map::class.java) as Map<String, Any?>
    }
}
