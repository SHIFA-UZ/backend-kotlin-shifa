package com.shifa.security

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Signs URLs of private files (patient documents, patient photos, chat attachments) so a link only works
 * for a while and cannot be guessed. The key is derived from the JWT secret: no extra secret to manage,
 * and rotating the JWT secret (never done lightly) rotates this too.
 *
 * URL shape: `<any host>/<protected path>?exp=<unix seconds>&sig=<base64url hmac>`. Only the decoded path and
 * the expiry are signed, so a stored absolute URL keeps its host and still verifies after a hostname move.
 *
 * Callers pass the prefixes they are allowed to sign in their context: a profile-photo mapper may sign
 * `/patients/` only, so a `photoUrl` a client pointed at someone else's document never gets a signature.
 * Signed URLs are never persisted; see [canonical].
 */
@Component
class FileUrlSigner(jwtSecret: String, private val clock: Clock) {

    /** Spring uses this one; tests pass a fixed clock through the primary constructor. */
    @Autowired
    constructor(@Value("\${app.jwt.secret}") jwtSecret: String) : this(jwtSecret, Clock.systemUTC())

    private val key: ByteArray =
        MessageDigest.getInstance("SHA-256").digest("shifa-file-url:$jwtSecret".toByteArray(StandardCharsets.UTF_8))

    companion object {
        const val PATIENT_DOCUMENTS = "/patientdocuments/"
        const val PATIENT_PHOTOS = "/patients/"
        const val CHAT_ATTACHMENTS = "/chat-attachments/"

        /** URL path prefixes whose files are private. Doctor photos and certificates stay public. */
        val PROTECTED_PREFIXES: Set<String> = setOf(PATIENT_DOCUMENTS, PATIENT_PHOTOS, CHAT_ATTACHMENTS)
        val DEFAULT_TTL: Duration = Duration.ofHours(1)
        const val EXP_PARAM = "exp"
        const val SIG_PARAM = "sig"
    }

    fun isProtected(path: String): Boolean = PROTECTED_PREFIXES.any { path.startsWith(it) }

    /**
     * Returns [url] with a fresh `exp` and `sig` when its path starts with one of [allowed]; otherwise [url]
     * unchanged. Accepts absolute URLs and relative paths; an existing signature is replaced, a fragment kept.
     * A URL that cannot be parsed, or whose path contains dot segments or empty segments, is returned as is
     * (and the filter will log or refuse it).
     */
    fun sign(url: String, allowed: Set<String> = PROTECTED_PREFIXES, ttl: Duration = DEFAULT_TTL): String {
        val parts = Parts.parse(url) ?: return url
        if (allowed.none { parts.path.startsWith(it) }) return url
        val exp = clock.instant().plus(ttl).epochSecond
        return parts.withSignature(exp, signature(parts.path, exp))
    }

    fun signNullable(url: String?, allowed: Set<String> = PROTECTED_PREFIXES, ttl: Duration = DEFAULT_TTL): String? =
        url?.let { sign(it, allowed, ttl) }

    /** [url] without `exp` and `sig`: what gets stored. Works textually even when [url] is not a valid URI. */
    fun canonical(url: String?): String? {
        if (url.isNullOrBlank()) return url
        Parts.parse(url)?.let { return it.withoutSignature() }
        // Unparsable as a URI: still strip the reserved parameters textually so nothing signed is stored.
        val base = url.substringBefore('#').substringBefore('?')
        val fragment = url.substringAfter('#', "").let { if ('#' in url) "#$it" else "" }
        val kept = url.substringBefore('#').substringAfter('?', "").split('&').filter { Parts.isKeptParam(it) }
        return base + (if (kept.isEmpty()) "" else "?" + kept.joinToString("&")) + fragment
    }

    /** True when [sig] is the signature of [path] for [exp] and [exp] is still in the future. */
    fun verify(path: String, exp: String?, sig: String?): Boolean {
        val expSeconds = exp?.toLongOrNull() ?: return false
        if (sig.isNullOrBlank()) return false
        if (expSeconds <= clock.instant().epochSecond) return false
        val expected = signature(path, expSeconds).toByteArray(StandardCharsets.US_ASCII)
        return MessageDigest.isEqual(expected, sig.toByteArray(StandardCharsets.US_ASCII))
    }

    private fun signature(path: String, exp: Long): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
        val raw = mac.doFinal("$path\n$exp".toByteArray(StandardCharsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw) // URL-safe alphabet, no encoding needed
    }

    /** Structured view of a file URL: `prefix` (scheme + authority, or empty), decoded `path`, other query params, fragment. */
    private class Parts(
        private val prefix: String,
        private val rawPath: String,
        val path: String,
        private val otherQuery: List<String>,
        private val fragment: String?,
    ) {
        fun withSignature(exp: Long, sig: String): String =
            build(otherQuery + "$EXP_PARAM=$exp" + "$SIG_PARAM=$sig")

        fun withoutSignature(): String = build(otherQuery)

        private fun build(query: List<String>): String = buildString {
            append(prefix).append(rawPath)
            if (query.isNotEmpty()) append('?').append(query.joinToString("&"))
            if (fragment != null) append('#').append(fragment)
        }

        companion object {
            /** Everything except `exp`/`sig`; names compared decoded, as the servlet container decodes `%65xp` to `exp`. */
            fun isKeptParam(param: String): Boolean {
                if (param.isEmpty()) return false
                val name = runCatching { java.net.URLDecoder.decode(param.substringBefore('='), StandardCharsets.UTF_8) }.getOrDefault(param)
                return name != EXP_PARAM && name != SIG_PARAM
            }

            fun parse(url: String): Parts? {
                val trimmed = url.trim().replace("\\", "/")
                if (trimmed.isEmpty()) return null
                val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
                val absolute = uri.scheme != null
                // Output keeps the caller's own path spelling (relative or absolute, encoded as given).
                val rawPath = if (absolute) uri.rawPath else trimmed.substringBefore('#').substringBefore('?')
                val decoded = uri.path ?: return null
                // Reject anything the server could read two ways: dot segments (also percent-encoded ones), empty segments.
                if (decoded != uri.normalize().path || decoded.contains("//")) return null
                if (decoded.split('/').any { it == "." || it == ".." }) return null
                val path = if (decoded.startsWith("/")) decoded else "/$decoded"
                val prefix = if (absolute) "${uri.scheme}://${uri.rawAuthority}" else ""
                val otherQuery = (uri.rawQuery ?: "").split('&').filter { isKeptParam(it) }
                return Parts(prefix, rawPath, path, otherQuery, uri.rawFragment)
            }
        }
    }
}
