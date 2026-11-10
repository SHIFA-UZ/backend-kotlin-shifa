package com.shifa.auth.infrastructure

import com.shifa.auth.domain.Role
import com.shifa.auth.domain.User
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable

interface UserRepositoryCustom {
    /**
     * The admin user list: every filter is applied in one query, so the total is right for any combination.
     * [q] matches phone, e-mail, username and the doctor/patient/admin profile name. [deviceRegistered] is about
     * patient and doctor accounts only (the account's own profile holds a push token): with it set, other roles
     * never match. Order: the pageable's sort over [ADMIN_SORT_FIELDS] (unknown fields are ignored), newest first
     * by default, id as the tie-break.
     */
    fun searchUsersAdmin(
        q: String?,
        role: Role?,
        enabled: Boolean?,
        deviceRegistered: Boolean?,
        pageable: Pageable,
    ): Page<User>

    /** The total [searchUsersAdmin] reports for the same filters. */
    fun countUsersAdmin(q: String?, role: Role?, enabled: Boolean?, deviceRegistered: Boolean?): Long

    companion object {
        /** `sort` property of `GET /api/admin/users` to its column. */
        val ADMIN_SORT_FIELDS = mapOf(
            "id" to "u.id",
            "createdAt" to "u.created_at",
            "lastLoginAt" to "u.last_login_at",
            "email" to "u.email",
            "phone" to "u.phone",
            "role" to "u.role",
            "enabled" to "u.enabled",
        )
    }
}
