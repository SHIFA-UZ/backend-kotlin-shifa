package com.shifa.clinics.infrastructure

import com.shifa.clinics.domain.Clinic
import org.springframework.data.jpa.repository.JpaRepository

interface ClinicRepository : JpaRepository<Clinic, Long>
