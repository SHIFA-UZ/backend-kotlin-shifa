package com.shifa.shared.security

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * `@AuthenticationPrincipal` for `@WebMvcTest` slices with `addFilters = false`: the slice doesn't load SecurityConfig,
 * so nothing registers the resolver. Tests put the principal in `SecurityContextHolder` themselves.
 */
@TestConfiguration
class AuthenticationPrincipalTestConfig : WebMvcConfigurer {
    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers.add(AuthenticationPrincipalArgumentResolver())
    }
}
