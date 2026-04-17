package com.shifa.repo

import com.shifa.domain.User
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
        roleName: String?,
        enabledVal: Boolean?,
        pageable: Pageable
    ): Page<User> {
        val searchTerm = if (q.isNullOrBlank()) "" else q.trim()
        val searchPattern = "%$searchTerm%".lowercase()
        // Positional params (?1, ?2, ...) avoid Hibernate native-query named-parameter issues on PostgreSQL
        val whereClause = """
            WHERE (COALESCE(?1, '') = '' OR
                LOWER(COALESCE(u.phone, '')) LIKE ?2 OR
                LOWER(COALESCE(u.email, '')) LIKE ?3 OR
                LOWER(COALESCE(u.username, '')) LIKE ?4 OR
                LOWER(CONCAT(COALESCE(d.first_name, ''), ' ', COALESCE(d.last_name, ''))) LIKE ?5 OR
                LOWER(COALESCE(p.full_name, '')) LIKE ?6 OR
                LOWER(CONCAT(COALESCE(a.first_name, ''), ' ', COALESCE(a.last_name, ''))) LIKE ?7)
            AND (?8::text IS NULL OR CAST(u.role AS TEXT) = ?8)
            AND (?9 IS NULL OR u.enabled = ?9)
        """.trimIndent()
        val countSql = """
            SELECT COUNT(DISTINCT u.id) FROM users u
            LEFT JOIN doctor_profiles d ON d.user_id = u.id
            LEFT JOIN patient_profiles p ON p.user_id = u.id
            LEFT JOIN admin_profiles a ON a.user_id = u.id
            $whereClause
        """.trimIndent()
        val countQuery: Query = em.createNativeQuery(countSql)
        countQuery.setParameter(1, searchTerm)
        countQuery.setParameter(2, searchPattern)
        countQuery.setParameter(3, searchPattern)
        countQuery.setParameter(4, searchPattern)
        countQuery.setParameter(5, searchPattern)
        countQuery.setParameter(6, searchPattern)
        countQuery.setParameter(7, searchPattern)
        countQuery.setParameter(8, roleName)
        countQuery.setParameter(9, enabledVal)
        @Suppress("UNCHECKED_CAST")
        val total = (countQuery.singleResult as Number).toLong()

        val orderAndLimit = " ORDER BY u.id LIMIT ${pageable.pageSize} OFFSET ${pageable.offset}"
        val idSql = """
            SELECT DISTINCT u.id FROM users u
            LEFT JOIN doctor_profiles d ON d.user_id = u.id
            LEFT JOIN patient_profiles p ON p.user_id = u.id
            LEFT JOIN admin_profiles a ON a.user_id = u.id
            $whereClause
            $orderAndLimit
        """.trimIndent()
        val idQuery: Query = em.createNativeQuery(idSql)
        idQuery.setParameter(1, searchTerm)
        idQuery.setParameter(2, searchPattern)
        idQuery.setParameter(3, searchPattern)
        idQuery.setParameter(4, searchPattern)
        idQuery.setParameter(5, searchPattern)
        idQuery.setParameter(6, searchPattern)
        idQuery.setParameter(7, searchPattern)
        idQuery.setParameter(8, roleName)
        idQuery.setParameter(9, enabledVal)
        @Suppress("UNCHECKED_CAST")
        val rawIds = idQuery.resultList as List<Any>
        val ids = rawIds.map { (it as Number).toLong() }
        if (ids.isEmpty()) {
            return PageImpl(emptyList(), pageable, total)
        }
        val ordered = ids.map { id -> em.find(User::class.java, id)!! }
        return PageImpl(ordered, pageable, total)
    }
}
