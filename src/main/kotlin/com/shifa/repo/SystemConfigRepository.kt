package com.shifa.repo

import com.shifa.domain.SystemConfig
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.*

@Repository
interface SystemConfigRepository : JpaRepository<SystemConfig, Long> {
    fun findByKey(key: String): Optional<SystemConfig>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM SystemConfig c WHERE c.key = :key")
    fun findByKeyForUpdate(@Param("key") key: String): Optional<SystemConfig>
}
