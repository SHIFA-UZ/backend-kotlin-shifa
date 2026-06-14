package com.shifa.service

import com.shifa.domain.PatientProfile
import com.shifa.domain.Role
import com.shifa.domain.User
import com.shifa.domain.UserRole
import com.shifa.repo.PatientProfileRepository
import com.shifa.repo.UserRepository
import com.shifa.repo.UserRoleRepository
import com.shifa.util.PhoneNormalizer
import org.springframework.http.HttpStatus
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.security.SecureRandom
import java.text.Normalizer
import java.util.*

@Service
class PatientAccountService(
    private val userRepository: UserRepository,
    private val patientProfileRepository: PatientProfileRepository,
    private val passwordEncoder: PasswordEncoder,
    private val userRoleRepository: UserRoleRepository
) {
    private val random = SecureRandom()

    data class AccountCreationResult(
        val username: String,
        val oneTimePassword: String
    )

    @Transactional
    fun createPatientAccount(patientId: Long): AccountCreationResult {
        val patient = patientProfileRepository.findById(patientId)
            .orElseThrow { IllegalArgumentException("Patient not found: $patientId") }

        assertCanCreateAccount(patient)

        val username = generateUniqueUsername(patient.fullName)
        val oneTimePassword = generateSecurePassword()

        val user = User(
            username = username,
            passwordHash = passwordEncoder.encode(oneTimePassword),
            role = Role.PATIENT,
            forcePasswordReset = true,
            email = patient.email?.trim()?.takeIf { it.isNotEmpty() },
            phone = patient.phone?.trim()?.takeIf { it.isNotEmpty() },
        )

        val savedUser = userRepository.save(user)

        userRoleRepository.save(
            UserRole(
                user = savedUser,
                role = Role.PATIENT
            )
        )

        patient.user = savedUser
        patientProfileRepository.save(patient)

        return AccountCreationResult(username, oneTimePassword)
    }

    internal fun assertCanCreateAccount(patient: PatientProfile) {
        if (patient.user != null) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Patient already has an account")
        }

        patient.email?.trim()?.takeIf { it.isNotEmpty() }?.let { email ->
            if (userRepository.findByEmailIgnoreCase(email).isPresent) {
                throw ResponseStatusException(HttpStatus.CONFLICT, "Email already registered")
            }
        }

        val phoneCandidates = buildList {
            patient.phone?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it) }
            patient.phoneNormalized?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it) }
            patient.phone?.trim()?.let { PhoneNormalizer.normalize(it) }?.let { add(it) }
        }.distinct()

        for (phone in phoneCandidates) {
            if (userRepository.findByPhone(phone).isPresent) {
                throw ResponseStatusException(HttpStatus.CONFLICT, "Phone number already registered")
            }
        }
    }

    private fun generateUniqueUsername(fullName: String): String {
        val base = slugify(fullName)
        var username = base
        var suffix = 1

        while (userRepository.findByUsername(username).isPresent) {
            username = "$base$suffix"
            suffix++
        }

        return username
    }

    private fun slugify(name: String): String {
        val normalized = Normalizer.normalize(name.lowercase(Locale.getDefault()), Normalizer.Form.NFD)
            .replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
        return normalized.trim()
            .replace("\\s+".toRegex(), ".")
            .replace("[^a-z0-9.]".toRegex(), "")
            .takeIf { it.isNotEmpty() } ?: "patient"
    }

    private fun generateSecurePassword(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*"
        return (1..12)
            .map { chars[random.nextInt(chars.length)] }
            .joinToString("")
    }
}
