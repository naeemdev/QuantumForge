package com.quantumforge.api.common

import java.security.SecureRandom
import java.time.Clock
import java.util.UUID

/**
 * UUIDv7 (RFC 9562) generator. The 48-bit millisecond timestamp prefix keeps
 * B-tree inserts mostly append-only, while 74 random bits keep IDs unguessable.
 */
object Ids {
    private val random = SecureRandom()

    fun newId(clock: Clock = Clock.systemUTC()): UUID {
        val millis = clock.millis()
        val randA = random.nextInt(1 shl 12).toLong()
        val randB = random.nextLong() and 0x3FFF_FFFF_FFFF_FFFFL

        val msb = (millis shl 16) or (0x7L shl 12) or randA
        val lsb = randB or Long.MIN_VALUE // variant bits 10xx
        return UUID(msb, lsb)
    }
}
