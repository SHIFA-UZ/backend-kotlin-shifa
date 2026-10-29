package com.shifa.shared

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RequestTimingFilterTest {
    @Test
    fun `route template wins, otherwise only the controller area survives`() {
        assertEquals("/api/patients/{id}", RequestTimingFilter.routeOf("/api/patients/{id}", "/api/patients/42"))
        assertEquals("/api/patients/*", RequestTimingFilter.routeOf(null, "/api/patients/42/documents/7"))
        assertEquals("/api/auth/*", RequestTimingFilter.routeOf(null, "/api/auth/verify/anna@example.com"))
        assertEquals("/api/video/*", RequestTimingFilter.routeOf(null, "/api/video/room/+998901234567"))
        assertEquals("<unmatched>", RequestTimingFilter.routeOf(null, "/api/+998901234567"))
        assertEquals("<unmatched>", RequestTimingFilter.routeOf(null, "/api/jane-doe"))
        assertEquals("<unmatched>", RequestTimingFilter.routeOf(null, "/api/jane-doe/anything"))
        assertEquals("<unmatched>", RequestTimingFilter.routeOf(null, "/wp-config.php"))
    }

    @Test
    fun `line is one json object without query or user data`() {
        assertEquals(
            """{"evt":"http","method":"GET","route":"/api/patients/{id}","status":200,"ms":12}""",
            RequestTimingFilter.line("GET", "/api/patients/{id}", 200, 12),
        )
    }
}
