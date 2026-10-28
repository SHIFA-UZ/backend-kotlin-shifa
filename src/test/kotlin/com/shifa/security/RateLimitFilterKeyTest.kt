package com.shifa.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RateLimitFilterKeyTest {

    @Test
    fun `hops 0 is the legacy behaviour - first entry, even when blank`() {
        assertEquals("1.1.1.1", RateLimitFilter.clientKey("1.1.1.1, 10.0.0.1", "10.0.0.2", 0))
        assertEquals("", RateLimitFilter.clientKey(" , 203.0.113.9", "10.0.0.2", 0))
        assertEquals("10.0.0.2", RateLimitFilter.clientKey(" ", "10.0.0.2", 0))
        assertEquals("1.1.1.1", RateLimitFilter.clientKey("1.1.1.1", "10.0.0.2", -1))
    }

    @Test
    fun `hops 1 uses the entry appended by the edge, ignoring a forged prefix`() {
        assertEquals("203.0.113.9", RateLimitFilter.clientKey("1.1.1.1, 203.0.113.9", "10.0.0.2", 1))
        assertEquals("203.0.113.9", RateLimitFilter.clientKey("203.0.113.9", "10.0.0.2", 1))
        assertEquals("2001:db8::1", RateLimitFilter.clientKey("1.1.1.1,2001:db8::1", "10.0.0.2", 1))
    }

    @Test
    fun `hops 2 counts positions from the end, blanks included`() {
        assertEquals("edge1", RateLimitFilter.clientKey("forged, edge1, edge2", "10.0.0.2", 2))
        assertEquals("10.0.0.2", RateLimitFilter.clientKey("forged, , edge2", "10.0.0.2", 2))
    }

    @Test
    fun `blank trusted slot, too few entries or no header fall back to remote address, then unknown`() {
        assertEquals("10.0.0.2", RateLimitFilter.clientKey("forged, ", "10.0.0.2", 1))
        assertEquals("10.0.0.2", RateLimitFilter.clientKey("203.0.113.9", "10.0.0.2", 2))
        assertEquals("10.0.0.2", RateLimitFilter.clientKey(null, "10.0.0.2", 1))
        assertEquals("unknown", RateLimitFilter.clientKey("forged, ", null, 1))
    }
}
