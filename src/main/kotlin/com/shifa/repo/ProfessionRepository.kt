package com.shifa.repo

import com.shifa.domain.Profession
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

@Repository
interface ProfessionRepository : JpaRepository<Profession, Long> {
    
    fun findByIsActiveTrueOrderByCategoryAscDisplayOrderAsc(): List<Profession>
    
    @Query("SELECT p FROM Profession p WHERE p.isActive = true AND " +
           "(LOWER(p.english) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(p.uzbek) LIKE LOWER(CONCAT('%', :query, '%'))) " +
           "ORDER BY p.category ASC, p.displayOrder ASC")
    fun searchProfessions(query: String): List<Profession>
    
    fun findByEnglish(english: String): Profession?
}
