package com.quantumforge.api.unit

import com.quantumforge.api.common.ApiException
import com.quantumforge.api.common.Ids
import com.quantumforge.api.common.Slugs
import com.quantumforge.api.jobs.ExecutionConfig
import com.quantumforge.api.security.RateLimitFilter
import com.quantumforge.api.security.SecurityErrorWriter
import com.quantumforge.api.source.GitUrls
import com.quantumforge.api.source.LanguageDetector
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SmallUnitsTest {
    @Test
    fun `uuid v7 ids are time ordered and carry version and variant bits`() {
        val earlier = Ids.newId(Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC))
        val later = Ids.newId(Clock.fixed(Instant.parse("2026-01-01T00:00:01Z"), ZoneOffset.UTC))
        assertEquals(7, earlier.version())
        assertEquals(2, earlier.variant())
        assertTrue(earlier.toString() < later.toString(), "string order must follow time order")
        assertEquals(Instant.parse("2026-01-01T00:00:00Z").toEpochMilli(), earlier.mostSignificantBits ushr 16)
    }

    @Test
    fun `slugs are derived from names`() {
        assertEquals("demo-engineering", Slugs.fromName("Demo Engineering"))
        assertEquals("cafe-deja-vu", Slugs.fromName("Café Déjà Vu!"))
        assertEquals("x-0", Slugs.fromName("X"))
        assertEquals("item", Slugs.fromName("!!!"))
        assertTrue(Slugs.fromName("a".repeat(100)).length <= 63)
        assertFalse(Slugs.isValid("-leading"))
    }

    @Test
    fun `git refs that could be parsed as options or escape ref rules are rejected`() {
        listOf("main", "release/1.2", "feature/x-y_z", "v1.0.0").forEach { GitUrls.requireSafeRef(it) }
        listOf("--upload-pack=x", "-b", "a..b", "a//b", "x.lock", "a@{1}", "trailing/", "with space", "").forEach {
            assertFailsWith<ApiException>(it) { GitUrls.requireSafeRef(it) }
        }
    }

    @Test
    fun `test execution config is bounded`() {
        ExecutionConfig(testPatterns = listOf("tests/**/*.test.ts")).validated(600)
        assertFailsWith<ApiException> { ExecutionConfig(testPatterns = listOf("../x")).validated(600) }
        assertFailsWith<ApiException> { ExecutionConfig(testPatterns = listOf("-r")).validated(600) }
        assertFailsWith<ApiException> { ExecutionConfig(testPatterns = listOf("a;rm -rf /")).validated(600) }
        assertFailsWith<ApiException> { ExecutionConfig(timeoutSeconds = 601).validated(600) }
        assertFailsWith<ApiException> { ExecutionConfig(memoryMb = 10).validated(600) }
        assertEquals(listOf("**/*.test.ts", "**/*.test.js", "**/*.test.mjs"), ExecutionConfig().effectivePatterns())
    }

    @Test
    fun `token bucket allows a burst and then refills over time`() {
        val filter = RateLimitFilter(60, 3, SecurityErrorWriter(JsonMapper.builder().build()))
        val t0 = 1_000_000_000L
        repeat(3) { assertTrue(filter.tryAcquire("k", t0)) }
        assertFalse(filter.tryAcquire("k", t0))
        assertTrue(filter.tryAcquire("k", t0 + 1_000_000_000L), "one token per second at 60 rpm")
        assertTrue(filter.tryAcquire("other", t0), "buckets are per key")
    }

    @Test
    fun `languages are detected from file names`() {
        val d = LanguageDetector()
        assertEquals("TypeScript", d.detect("src/a.tsx"))
        assertEquals("COBOL", d.detect("legacy/LEDGER.CBL"))
        assertEquals("Fortran", d.detect("num/solve.f90"))
        assertEquals("OCaml", d.detect("lib/dune"))
        assertEquals(null, d.detect("LICENSE"))
        assertTrue(d.isTestPath("tests/cart.test.ts"))
        assertFalse(d.isTestPath("src/contest.ts"))
    }
}
