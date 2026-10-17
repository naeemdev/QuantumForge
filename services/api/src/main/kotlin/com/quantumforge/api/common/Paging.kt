package com.quantumforge.api.common

import io.swagger.v3.oas.annotations.media.Schema

/**
 * Offset pagination. Lists in M1 are small per organization; keyset pagination is
 * used where a list can grow without bound (job events).
 */
data class PageRequest(val page: Int, val size: Int) {
    val offset: Long get() = page.toLong() * size

    companion object {
        const val MAX_SIZE = 200

        fun of(page: Int?, size: Int?): PageRequest {
            val p = page ?: 0
            val s = size ?: 50
            if (p < 0) throw BadRequestException("invalid_page", "page must be >= 0")
            if (s !in 1..MAX_SIZE) throw BadRequestException("invalid_page_size", "size must be between 1 and $MAX_SIZE")
            return PageRequest(p, s)
        }
    }
}

@Schema(description = "A page of results")
data class Page<T>(
    val items: List<T>,
    val page: Int,
    val size: Int,
    val total: Long,
) {
    companion object {
        fun <T> of(items: List<T>, request: PageRequest, total: Long) = Page(items, request.page, request.size, total)
    }
}
