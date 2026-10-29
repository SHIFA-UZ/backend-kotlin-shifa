package com.shifa.auth.infrastructure

import com.shifa.auth.domain.User
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable

interface UserRepositoryCustom {
    /**
     * Search users by query (phone, email, username, or name from doctor/patient/admin profile).
     * roleName and enabledVal are optional filters.
     */
    fun searchUsersAdmin(
        q: String?,
        roleName: String?,
        enabledVal: Boolean?,
        pageable: Pageable
    ): Page<User>
}
