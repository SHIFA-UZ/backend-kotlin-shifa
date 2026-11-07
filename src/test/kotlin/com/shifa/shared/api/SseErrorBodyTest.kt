package com.shifa.shared.api

import com.shifa.shared.CodedStatusException
import com.shifa.shared.ErrorCode
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

/**
 * BE-08: a refusal before an SSE stream opens (tier, validation) is the standard JSON error body even though the
 * route produces `text/event-stream` and the client asks for it.
 */
@WebMvcTest(
    controllers = [SseErrorBodyTest.ProbeController::class],
    excludeFilters = [
        ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE,
            classes = [
                com.shifa.shared.security.AiRateLimitFilter::class,
                com.shifa.shared.security.JwtAuthFilter::class,
                com.shifa.shared.security.UserRateLimitFilter::class,
            ],
        ),
    ],
)
@AutoConfigureMockMvc(addFilters = false)
@Import(SseErrorBodyTest.ProbeController::class, ErrorHandler::class, com.shifa.shared.config.CacheConfig::class)
class SseErrorBodyTest {
    @Autowired lateinit var mockMvc: MockMvc

    @RestController
    class ProbeController {
        @PostMapping("/probe/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
        fun stream(): SseEmitter =
            throw CodedStatusException(HttpStatus.FORBIDDEN, "Feature requires PRO", ErrorCode.AI_TIER_REQUIRED)
    }

    @Test
    fun `refusal before the stream is the JSON API body`() {
        mockMvc.perform(post("/probe/stream").accept(MediaType.TEXT_EVENT_STREAM))
            .andExpect(status().isForbidden)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.status").value(403))
            .andExpect(jsonPath("$.code").value("AI_TIER_REQUIRED"))
            .andExpect(jsonPath("$.message").value("Feature requires PRO"))
    }
}
