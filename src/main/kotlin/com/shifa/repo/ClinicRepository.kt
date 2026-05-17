package com.shifa.repo

import com.shifa.domain.Clinic
import org.springframework.data.jpa.repository.JpaRepository

interface ClinicRepository : JpaRepository<Clinic, Long>
