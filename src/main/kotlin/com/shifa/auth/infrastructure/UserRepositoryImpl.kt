package com.shifa.auth.infrastructure

import com.shifa.auth.domain.Role
import com.shifa.auth.domain.User
import jakarta.persistence.EntityManager
import jakarta.persistence.Query
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Repository

@Repository
class UserRepositoryImpl(
    private val em: EntityManager
) : UserRepositoryCustom {

    override fun searchUsersAdmin(
        q: String?,
        role: Role?,
        enabled: Boolean?,
        deviceRegistered: Boolean?,
        pageable: Pageable,
    ): Page<User> {
        val filter = Filter(q, role, enabled, deviceRegistered)
        val total = filter.count()
        if (total == 0L || pageable.offset >= total) return PageImpl(emptyList(), pageable, total)
        val query = em.createNativeQuery(
            "SELECT u.* FROM users u ${filter.where} ORDER BY ${orderBy(pageable)} LIMIT :limit OFFSET :offset",
            User::class.java,
        )
        filter.bind(query)
        query.setParameter("limit", pageable.pageSize)
        query.setParameter("offset", pageable.offset)
        @Suppress("UNCHECKED_CAST")
        return PageImpl(query.resultList as List<User>, pageable, total)
    }

    override fun countUsersAdmin(q: String?, role: Role?, enabled: Boolean?, deviceRegistered: Boolean?): Long =
        Filter(q, role, enabled, deviceRegistered).count()

    /** Whitelisted columns only (never client text in the SQL); nulls last; id closes every order so pages are stable. */
    private fun orderBy(pageable: Pageable): String {
        val orders = pageable.sort.toList().mapNotNull { order ->
            UserRepositoryCustom.ADMIN_SORT_FIELDS[order.property]?.let { it to order.isAscending }
        }.ifEmpty { listOf("u.created_at" to false) }
        val columns = orders.map { (column, asc) -> "$column ${if (asc) "ASC" else "DESC"} NULLS LAST" }
        return if (orders.any { it.first == "u.id" }) columns.joinToString() else {
            (columns + "u.id ${if (orders.first().second) "ASC" else "DESC"}").joinToString()
        }
    }

    /** Only the clauses of the filters that are set, so no parameter is ever bound as an untyped null. */
    private inner class Filter(q: String?, role: Role?, enabled: Boolean?, deviceRegistered: Boolean?) {
        private val params = linkedMapOf<String, Any>()
        val where: String

        init {
            val clauses = mutableListOf<String>()
            q?.trim()?.takeIf { it.isNotEmpty() }?.let {
                params["q"] = "%${it.lowercase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")}%"
                clauses += """(LOWER(COALESCE(u.phone, '')) LIKE :q OR LOWER(COALESCE(u.email, '')) LIKE :q
                    OR LOWER(COALESCE(u.username, '')) LIKE :q
                    OR EXISTS (SELECT 1 FROM doctor_profiles d WHERE d.user_id = u.id
                        AND LOWER(CONCAT(COALESCE(d.first_name, ''), ' ', COALESCE(d.last_name, ''))) LIKE :q)
                    OR EXISTS (SELECT 1 FROM patient_profiles p WHERE p.user_id = u.id AND LOWER(COALESCE(p.full_name, '')) LIKE :q)
                    OR EXISTS (SELECT 1 FROM admin_profiles a WHERE a.user_id = u.id
                        AND LOWER(CONCAT(COALESCE(a.first_name, ''), ' ', COALESCE(a.last_name, ''))) LIKE :q))"""
            }
            role?.let {
                params["role"] = it.name
                clauses += "CAST(u.role AS TEXT) = :role"
            }
            enabled?.let {
                params["enabled"] = it
                clauses += "u.enabled = :enabled"
            }
            deviceRegistered?.let {
                clauses += "CAST(u.role AS TEXT) IN ('PATIENT', 'DOCTOR') AND ${if (it) "" else "NOT "}$HAS_DEVICE"
            }
            where = if (clauses.isEmpty()) "" else clauses.joinToString(" AND ", prefix = "WHERE ")
        }

        fun bind(query: Query) = params.forEach { (name, value) -> query.setParameter(name, value) }

        fun count(): Long {
            val query = em.createNativeQuery("SELECT COUNT(*) FROM users u $where")
            bind(query)
            return (query.singleResult as Number).toLong()
        }
    }

    private companion object {
        /** Same rule as the row's `deviceRegistered`: the profile of the account's own role holds a push token. */
        const val HAS_DEVICE = """(
            (CAST(u.role AS TEXT) = 'PATIENT' AND EXISTS (SELECT 1 FROM patient_profiles p
                WHERE p.user_id = u.id AND TRIM(COALESCE(p.fcm_token, '')) <> ''))
            OR (CAST(u.role AS TEXT) = 'DOCTOR' AND EXISTS (SELECT 1 FROM doctor_profiles d
                WHERE d.user_id = u.id AND TRIM(COALESCE(d.fcm_token, '')) <> '')))"""
    }
}
