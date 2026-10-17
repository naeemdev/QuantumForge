package com.quantumforge.api.common

import java.text.Normalizer

object Slugs {
    private val pattern = Regex("^[a-z0-9][a-z0-9-]{1,62}$")

    fun isValid(slug: String) = pattern.matches(slug)

    /** Derives a slug from a display name: "Demo Engineering" -> "demo-engineering". */
    fun fromName(name: String): String {
        val ascii = Normalizer.normalize(name, Normalizer.Form.NFKD).replace(Regex("\\p{M}+"), "")
        val slug = ascii.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(63)
            .trimEnd('-')
        return when {
            slug.length >= 2 -> slug
            slug.length == 1 -> "$slug-0"
            else -> "item"
        }
    }

    fun require(slug: String, field: String = "slug"): String {
        if (!isValid(slug)) {
            throw BadRequestException(
                "invalid_slug",
                "$field must be 2-63 characters of lowercase letters, digits and hyphens, starting with a letter or digit",
            )
        }
        return slug
    }
}
