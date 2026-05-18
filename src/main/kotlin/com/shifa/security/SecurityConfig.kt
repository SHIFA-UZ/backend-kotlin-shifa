package com.shifa.security

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.Customizer.withDefaults
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true) // NEW: Enables @PreAuthorize for defense-in-depth (e.g. controller-level role checks)
class SecurityConfig(
    private val jwtAuthFilter: JwtAuthFilter,
    private val rateLimitFilter: RateLimitFilter,
    private val userRateLimitFilter: UserRateLimitFilter,
    private val securityHeadersFilter: SecurityHeadersFilter,
    private val appProps: com.shifa.config.AppProperties
) {
    private val log = LoggerFactory.getLogger(SecurityConfig::class.java)

    @Bean
    fun filterChain(http: HttpSecurity): SecurityFilterChain {
        http
            // --- Disable defaults ---
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .logout { it.disable() }
            .csrf { it.disable() }

            // --- CORS ---
            .cors(withDefaults())

            // --- Stateless JWT ---
            .sessionManagement {
                it.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            }

            // --- Auth rules ---
            .authorizeHttpRequests {
                it.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                
                // Public auth endpoints
                it.requestMatchers(
                    "/api/auth/login",
                    "/api/auth/register",
                    "/api/auth/register-patient",
                    "/api/auth/verify-key",
                    "/api/auth/check-existing-patient",
                    "/api/auth/check-existing-doctor",
                    "/api/auth/check-identifier",
                    "/api/auth/create-patient-for-doctor",
                    "/api/auth/send-email-otp",
                    "/api/auth/verify",
                    "/api/auth/forgot-password-reset",
                    "/api/auth/send-login-otp",
                    "/api/auth/verify-email-otp",
                    "/api/auth/send-forgot-password-otp",
                    "/api/auth/admin/request-login-otp",
                    "/api/auth/admin/verify-login-otp"
                ).permitAll()
                
                // Protected auth endpoints
                it.requestMatchers("/api/auth/reset-password").authenticated()

                it.requestMatchers("/api/test/**").permitAll() // Test endpoints for development
                it.requestMatchers("/api/public/**").permitAll() // Public endpoints (doctor listings)
                it.requestMatchers("/api/webhooks/daily").permitAll() // Daily.co webhook (no auth)
                it.requestMatchers("/api/webhooks/stripe").permitAll() // Stripe webhook (signature verified)
                it.requestMatchers("/actuator/health/**").permitAll() // Health check endpoint
                it.requestMatchers("/error").permitAll()

                // Swagger UI + OpenAPI docs
                it.requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                
                // Allow public access to static resources (images, etc.)
                it.requestMatchers("/doctors/**", "/patients/**", "/patientdocuments/**", "/certificates/**", "/photos/**", "/chat-attachments/**").permitAll()
                
                // Chat attachment upload requires authentication
                it.requestMatchers("/api/messages/upload-attachment").authenticated()

                // Admin endpoints - require ADMIN role (ALREADY PRESENT)
                it.requestMatchers("/api/admin/**").hasRole("ADMIN")

                // --- RBAC (NEW): Restrict by role to prevent privilege escalation ---
                // Patient-only: /api/patients/me/** (patient's own profile, messages, schedule, photo)
                it.requestMatchers("/api/patients/me/**").hasRole("PATIENT")
                // Doctor app + clinic staff: shared patient roster / booking flows
                it.requestMatchers("/api/patients/**").hasAnyRole("DOCTOR", "CLINIC_STAFF")
                // Doctor-only: own profile & subscription endpoints under /api/doctors/me
                it.requestMatchers("/api/doctors/me", "/api/doctors/me/**").hasRole("DOCTOR")
                it.requestMatchers(HttpMethod.POST, "/api/schedule/book").hasAnyRole("DOCTOR", "CLINIC_STAFF")
                // Staff may GET schedule metadata (read-only); mutations remain doctor-only below.
                it.requestMatchers(HttpMethod.GET, "/api/schedule/**").hasAnyRole("DOCTOR", "CLINIC_STAFF")
                it.requestMatchers("/api/schedule/**").hasRole("DOCTOR")
                it.requestMatchers("/api/doctor/analytics/**").hasRole("DOCTOR")
                it.requestMatchers("/api/doctor/subscription/**").hasRole("DOCTOR")
                it.requestMatchers("/api/doctor/payments/**").hasRole("DOCTOR")
                it.requestMatchers("/api/calendar/**").hasAnyRole("DOCTOR", "CLINIC_STAFF")
                it.requestMatchers("/api/appointments/**").hasAnyRole("DOCTOR", "CLINIC_STAFF")
                it.requestMatchers("/api/consultations/**").hasRole("DOCTOR")
                it.requestMatchers("/api/messages/**").hasRole("DOCTOR")
                it.requestMatchers("/api/payments/**").hasRole("PATIENT")
                // Patient: own remote care tasks (my-tasks)
                it.requestMatchers("/api/tasks/my-tasks", "/api/tasks/my-tasks/**").hasRole("PATIENT")
                it.requestMatchers("/api/tasks/**").hasRole("DOCTOR")
                // Doctor-only: profile photo (doctor upload)
                it.requestMatchers("/api/profile/**").hasRole("DOCTOR")
                it.requestMatchers("/api/practice/me", "/api/practice/me/**").hasAnyRole("DOCTOR", "CLINIC_STAFF")
                it.requestMatchers("/api/me/clinics").hasAnyRole("DOCTOR", "CLINIC_STAFF")
                it.requestMatchers("/api/clinics/**").hasAnyRole("DOCTOR", "CLINIC_STAFF")
                it.requestMatchers("/api/treatment-plans/**").hasAnyRole("DOCTOR", "CLINIC_STAFF")
                it.requestMatchers("/api/prophylaxis/**").hasAnyRole("DOCTOR", "CLINIC_STAFF")
                // Shared (authenticated): notifications, AI, video
                it.requestMatchers("/api/notifications/**", "/api/ai/**", "/api/video/**").authenticated()

                it.anyRequest().authenticated()
            }
			
			// 🔴 THIS IS CRITICAL FOR SSE
			.securityContext { it.requireExplicitSave(false) }

            // --- Custom filters: all added before UsernamePasswordAuthenticationFilter so they have a registered order.
            // Order (first to run): securityHeaders -> rateLimit -> jwtAuth -> userRateLimit -> UsernamePassword...
            .addFilterBefore(
                userRateLimitFilter,
                UsernamePasswordAuthenticationFilter::class.java
            )
            .addFilterBefore(
                jwtAuthFilter,
                UsernamePasswordAuthenticationFilter::class.java
            )
            .addFilterBefore(
                rateLimitFilter,
                UsernamePasswordAuthenticationFilter::class.java
            )
            .addFilterBefore(
                securityHeadersFilter,
                UsernamePasswordAuthenticationFilter::class.java
            )

        return http.build()
    }

    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val config = CorsConfiguration().apply {
            // Build allowed origins list
            val exactOrigins = mutableListOf<String>()
            val originPatterns = mutableListOf<String>()
            
            // Always allow localhost for development
            originPatterns.addAll(listOf("http://localhost:*", "https://localhost:*"))
            
            // Add production/staging frontend URL if configured
            val frontendUrl = appProps.frontendUrl
            
            if (frontendUrl.isNotBlank()) {
                // Extract origin from full URL (use URI.toURL() to avoid deprecated URL(String))
                try {
                    val url = java.net.URI.create(frontendUrl).toURL()
                    val origin = "${url.protocol}://${url.host}${if (url.port != -1 && url.port != url.defaultPort) ":${url.port}" else ""}"
                    // Add exact origin (required when allowCredentials = true)
                    exactOrigins.add(origin)
                    originPatterns.add(origin)
                } catch (e: Exception) {
                    // If URL parsing fails, just use the string as-is
                    exactOrigins.add(frontendUrl)
                    originPatterns.add(frontendUrl)
                }
            }
            
            // Also allow the publicBaseUrl (backend itself) just in case
            val publicBaseUrl = appProps.publicBaseUrl
            
            if (publicBaseUrl.isNotBlank()) {
                try {
                    val url = java.net.URI.create(publicBaseUrl).toURL()
                    val origin = "${url.protocol}://${url.host}${if (url.port != -1 && url.port != url.defaultPort) ":${url.port}" else ""}"
                    exactOrigins.add(origin)
                    originPatterns.add(origin)
                } catch (e: Exception) {
                    exactOrigins.add(publicBaseUrl)
                    originPatterns.add(publicBaseUrl)
                }
            }
            
            // SECURITY: For production healthcare apps, prefer explicit origins (frontendBaseUrl) over wildcards.
            // Wildcards below are for staging (Firebase/Netlify/Vercel); restrict in prod via app.frontendUrl only if possible.
            // Always allow common Firebase/Netlify/Vercel patterns for staging
            // Note: When allowCredentials = true, wildcards in allowedOriginPatterns work in Spring Boot 2.4+
            originPatterns.addAll(listOf(
                "https://*.netlify.app",
                "https://*.vercel.app",
                "https://*.railway.app",
                "https://*.web.app",      // Explicitly add Firebase web.app
                "https://*.firebaseapp.com", // Explicitly add Firebase firebaseapp.com
                "https://*.run.app"       // Google Cloud Run
            ))
            
            // Use exact origins when available (required when allowCredentials = true with wildcards)
            if (exactOrigins.isNotEmpty()) {
                this.allowedOrigins = exactOrigins.distinct()
                // SECURITY: Log CORS config only at debug to avoid leaking origin list in prod
                if (log.isDebugEnabled) {
                    log.debug("CORS: Allowed exact origins: {}", this.allowedOrigins)
                }
            }
            
            // Patterns work with allowCredentials in Spring Boot 2.4+
            allowedOriginPatterns = originPatterns.distinct()
            if (log.isDebugEnabled) {
                log.debug("CORS: Allowed origin patterns: {}", allowedOriginPatterns)
            }

            allowedMethods = listOf(
                "GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"
            )

            allowedHeaders = listOf(
                "Authorization",
                "Content-Type",
                "Accept",
                "Origin",
                "X-Requested-With"
            )

            // 🔴 IMPORTANT FOR SSE + document downloads (so the doctor app
            // can read filename/extension from Content-Disposition on web).
            exposedHeaders = listOf(
                "Content-Type",
                "Authorization",
                "Content-Disposition",
                "Content-Length"
            )

            allowCredentials = true
            maxAge = 3600L // Cache preflight for 1 hour
        }

        val source = UrlBasedCorsConfigurationSource()
        source.registerCorsConfiguration("/**", config)
        return source
    }
}
