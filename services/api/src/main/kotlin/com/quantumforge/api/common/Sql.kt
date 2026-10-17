package com.quantumforge.api.common

import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** PostgreSQL JDBC binds OffsetDateTime to timestamptz without timezone surprises. */
fun Instant.sql(): OffsetDateTime = atOffset(ZoneOffset.UTC)

fun ResultSet.uuid(column: String): UUID = getObject(column, UUID::class.java)

fun ResultSet.uuidOrNull(column: String): UUID? = getObject(column, UUID::class.java)

fun ResultSet.instant(column: String): Instant = getObject(column, OffsetDateTime::class.java).toInstant()

fun ResultSet.instantOrNull(column: String): Instant? = getObject(column, OffsetDateTime::class.java)?.toInstant()

fun ResultSet.intOrNull(column: String): Int? = getInt(column).takeUnless { wasNull() }

fun ResultSet.longOrNull(column: String): Long? = getLong(column).takeUnless { wasNull() }
