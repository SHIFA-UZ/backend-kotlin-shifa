package com.shifa.shared.security

import com.shifa.shared.config.AppProperties
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Checks the signature on private file URLs (see [FileUrlSigner]).
 *
 * Roll-out: while `app.file-url-enforce` is false (the default) an unsigned or invalid link is only logged,
 * so we can confirm no client builds these URLs itself before we start refusing. With `true` it is a 403.
 *
 * Registered in [com.shifa.shared.config.FileUrlConfig], not as a component, so controller test slices do not load it.
 */
class SignedUrlFilter(
    private val signer: FileUrlSigner,
    private val appProperties: AppProperties,
) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(SignedUrlFilter::class.java)

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.method.equals("OPTIONS", ignoreCase = true) || !signer.isProtected(request.servletPath)

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val path = request.servletPath
        val valid = signer.verify(path, request.getParameter(FileUrlSigner.EXP_PARAM), request.getParameter(FileUrlSigner.SIG_PARAM))
        if (!valid) {
            // Log the prefix only: file names can contain patient names.
            val prefix = FileUrlSigner.PROTECTED_PREFIXES.first { path.startsWith(it) }
            val agent = (request.getHeader("User-Agent") ?: "-").take(60)
            if (appProperties.fileUrlEnforce) {
                log.warn("Unsigned file URL refused: prefix={} agent={}", prefix, agent)
                response.status = HttpServletResponse.SC_FORBIDDEN
                return
            }
            log.warn("Unsigned file URL served (enforcement off): prefix={} agent={}", prefix, agent)
        }
        filterChain.doFilter(request, response)
    }
}
