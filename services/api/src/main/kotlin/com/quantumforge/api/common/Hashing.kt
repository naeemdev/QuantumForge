package com.quantumforge.api.common

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.HexFormat

object Hashing {
    private val random = SecureRandom()
    private val hex = HexFormat.of()

    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    fun sha256(text: String): ByteArray = sha256(text.toByteArray(Charsets.UTF_8))

    fun sha256Hex(bytes: ByteArray): String = hex.formatHex(sha256(bytes))

    fun toHex(bytes: ByteArray): String = hex.formatHex(bytes)

    /** URL-safe random secret with [byteCount] bytes of entropy. */
    fun randomSecret(byteCount: Int = 32): String {
        val bytes = ByteArray(byteCount)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)
}
