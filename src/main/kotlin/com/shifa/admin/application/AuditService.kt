package com.shifa.admin.application


import com.shifa.admin.domain.AuditLog
import com.shifa.auth.domain.User
import com.shifa.admin.infrastructure.AdminProfileRepository
import com.shifa.admin.infrastructure.AuditLogRepository
import com.shifa.shared.util.LogSearch
import jakarta.persistence.criteria.JoinType
import jakarta.servlet.http.HttpServletRequest
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.ZoneOffset

@Service
class AuditService(
    private val auditLogRepository: AuditLogRepository,
    private val adminProfileRepository: AdminProfileRepository,
) {
    
    /**
     * Uses a new read-write transaction so callers inside @Transactional(readOnly = true)
     * (e.g. admin export) can still persist audit rows.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun logAction(
        adminUser: User,
        actionType: String,
        entityType: String,
        entityId: Long? = null,
        details: Map<String, Any>? = null,
        request: HttpServletRequest? = null
    ) {
        auditLogRepository.save(entry(adminUser, actionType, entityType, entityId, details, request))
    }

    /**
     * Runs an admin write and writes its audit row in one transaction: both commit or neither does, so a write can
     * not end up done but unaudited. [details] sees what [action] returned.
     */
    @Transactional
    fun <T> audited(
        adminUser: User,
        actionType: String,
        entityType: String,
        entityId: Long?,
        request: HttpServletRequest?,
        details: (T) -> Map<String, Any>?,
        action: () -> T,
    ): T {
        val result = action()
        // Flushed here so a row the database refuses fails the write, not a later commit.
        auditLogRepository.saveAndFlush(entry(adminUser, actionType, entityType, entityId, details(result), request))
        return result
    }

    private fun entry(
        adminUser: User,
        actionType: String,
        entityType: String,
        entityId: Long?,
        details: Map<String, Any>?,
        request: HttpServletRequest?,
    ) = AuditLog(
        adminUser = adminUser,
        actionType = actionType,
        entityType = entityType,
        entityId = entityId,
        details = details?.let { map -> map.entries.associate { storable(it.key) to storableValue(it.value) } },
        ipAddress = request?.remoteAddr?.let(::storable),
        userAgent = request?.getHeader("User-Agent")?.let(::storable),
    )

    private fun storableValue(value: Any): Any = when (value) {
        is String -> storable(value)
        is Map<*, *> -> value.entries.associate { storable(it.key.toString()) to it.value?.let(::storableValue) }
        is Iterable<*> -> value.map { it?.let(::storableValue) }
        else -> value
    }

    /** PostgreSQL refuses NUL and unpaired surrogates in text and JSONB: drop them so no value can fail the audit insert. */
    internal fun storable(text: String): String {
        if (text.none { it == '\u0000' || it.isSurrogate() }) return text
        return buildString(text.length) {
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
                    append(c).append(text[i + 1])
                    i++
                } else if (c != '\u0000' && !c.isSurrogate()) {
                    append(c)
                }
                i++
            }
        }
    }
    
    /**
     * The audit log with every given filter applied together. Without `from` the last 30 days are read, unless
     * the admin or the record (entity type and id) is given: those two read all time, as they always did.
     */
    @Transactional(readOnly = true)
    fun search(filter: AuditLogFilter, pageable: Pageable): Page<AuditLogEntry> {
        val (from, to) = LogSearch.window(
            filter.from,
            filter.to,
            unboundedWhenNoFrom = filter.adminUserId != null || (filter.entityType != null && filter.entityId != null),
        )
        val spec = Specification<AuditLog> { root, query, cb ->
            // The admin's e-mail is shown per row: fetch it with the page, not once per row (the count query has no rows).
            if (query?.resultType != Long::class.javaObjectType && query?.resultType != Long::class.javaPrimitiveType) {
                root.fetch<AuditLog, User>("adminUser", JoinType.LEFT)
            }
            cb.and(
                listOfNotNull(
                    filter.adminUserId?.let { cb.equal(root.get<User>("adminUser").get<Long>("id"), it) },
                    filter.entityType?.let { cb.equal(root.get<String>("entityType"), it) },
                    filter.entityId?.let { cb.equal(root.get<Long>("entityId"), it) },
                    filter.actionType?.let { cb.equal(root.get<String>("actionType"), it) },
                    from?.let { cb.greaterThanOrEqualTo(root.get("createdAt"), it.atOffset(ZoneOffset.UTC)) },
                    to?.let { cb.lessThan(root.get("createdAt"), it.atOffset(ZoneOffset.UTC)) },
                ),
            )
        }
        val page = auditLogRepository.findAll(spec, LogSearch.pageable(pageable, SORT_KEYS))
        val names = adminProfileRepository.findByUserIdIn(page.content.map { it.adminUser.id }.distinct())
            .associate { it.user.id to it.getFullName() }
        return page.map { AuditLogEntry(it, adminEmail = it.adminUser.email, adminName = names[it.adminUser.id]) }
    }

    /** Every action and entity type the log holds, for the filter lists of a client. */
    @Transactional(readOnly = true)
    fun types(): AuditLogTypes =
        AuditLogTypes(auditLogRepository.findDistinctActionTypes(), auditLogRepository.findDistinctEntityTypes())

    companion object {
        /** Sort keys of the audit list: API name to entity path. */
        private val SORT_KEYS = mapOf(
            "createdAt" to "createdAt",
            "id" to "id",
            "actionType" to "actionType",
            "entityType" to "entityType",
            "entityId" to "entityId",
            "adminUserId" to "adminUser.id",
        )
    }
}

data class AuditLogFilter(
    val adminUserId: Long? = null,
    val entityType: String? = null,
    val entityId: Long? = null,
    val actionType: String? = null,
    val from: Instant? = null,
    val to: Instant? = null,
)

class AuditLogEntry(val log: AuditLog, val adminEmail: String?, val adminName: String?)

data class AuditLogTypes(val actionTypes: List<String>, val entityTypes: List<String>)
