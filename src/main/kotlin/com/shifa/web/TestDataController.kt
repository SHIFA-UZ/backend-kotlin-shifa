// src/main/kotlin/com/shifa/web/TestDataController.kt
package com.shifa.web

import com.shifa.domain.Role
import com.shifa.domain.UserRole
import com.shifa.repo.PatientProfileRepository
import com.shifa.repo.UserRepository
import com.shifa.repo.UserRoleRepository
import com.shifa.service.UserManagementService
import org.springframework.http.HttpStatus
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException

/**
 * Test endpoint to create test patient user with correct password hash.
 * Only available in development - remove or secure this in production.
 */
@RestController
@RequestMapping("/api/test")
class TestDataController(
    private val users: UserRepository,
    private val patients: PatientProfileRepository,
    private val encoder: PasswordEncoder,
    private val userManagementService: UserManagementService,
    private val userRoles: UserRoleRepository
) {

    @PostMapping("/create-test-patient")
    fun createTestPatient(): Map<String, String> {
        // Check if user already exists
        val existingUser = users.findByEmail("patient@test.com").orElseGet {
            users.findByPhone("+998901234567").orElse(null)
        }

        if (existingUser != null && existingUser.role == Role.PATIENT) {
            // Unlock the account if it's locked
            val wasLocked = existingUser.isLocked()
            val unlockedUser = if (wasLocked) {
                userManagementService.unlockUser(existingUser.id)
            } else {
                existingUser
            }
            
            return mapOf(
                "message" to "Test patient user already exists",
                "email" to (unlockedUser.email ?: ""),
                "phone" to (unlockedUser.phone ?: ""),
                "unlocked" to (if (wasLocked) "Account was locked and has been unlocked" else "Account is not locked")
            )
        }

        // Create user with correct password hash
        val passwordHash = encoder.encode("patient123")
        val user = users.save(
            com.shifa.domain.User(
                email = "patient@test.com",
                phone = "+998901234567",
                passwordHash = passwordHash,
                role = Role.PATIENT,
                enabled = true
            )
        )

        // Add PATIENT role to user_roles (multi-role support)
        userRoles.save(
            UserRole(
                user = user,
                role = Role.PATIENT
            )
        )

        // Create or update patient profile
        val existingProfile = patients.findByPhone("+998901234567").orElseGet {
            patients.findByEmail("patient@test.com").orElse(null)
        }

        if (existingProfile != null) {
            existingProfile.fullName = "Test Patient"
            existingProfile.email = "patient@test.com"
            existingProfile.phone = "+998901234567"
            existingProfile.address = "Tashkent, Uzbekistan"
            existingProfile.birthDate = java.time.LocalDate.parse("1990-01-01")
            existingProfile.language = "Uzbek"
            patients.save(existingProfile)
        } else {
            val profile = com.shifa.domain.PatientProfile(
                fullName = "Test Patient",
                phone = "+998901234567",
                email = "patient@test.com",
                address = "Tashkent, Uzbekistan",
                birthDate = java.time.LocalDate.parse("1990-01-01"),
                language = "Uzbek",
                documents = mutableListOf()
            )
            patients.save(profile)
        }

        return mapOf(
            "message" to "Test patient user created successfully",
            "email" to "patient@test.com",
            "phone" to "+998901234567",
            "password" to "patient123",
            "userId" to user.id.toString(),
            "unlocked" to "Account is ready for login"
        )
    }

    @PostMapping("/unlock-test-patient")
    fun unlockTestPatient(): Map<String, String> {
        val existingUser = users.findByEmail("patient@test.com").orElseGet {
            users.findByPhone("+998901234567").orElse(null)
        }

        if (existingUser == null) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Test patient user not found")
        }

        if (existingUser.role != Role.PATIENT) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "User is not a patient")
        }

        val wasLocked = existingUser.isLocked()
        val unlockedUser = userManagementService.unlockUser(existingUser.id)

        return mapOf(
            "message" to if (wasLocked) "Test patient account unlocked successfully" else "Test patient account was not locked",
            "email" to (unlockedUser.email ?: ""),
            "phone" to (unlockedUser.phone ?: ""),
            "userId" to unlockedUser.id.toString(),
            "lockedUntil" to (unlockedUser.lockedUntil?.toString() ?: "null"),
            "failedLoginAttempts" to unlockedUser.failedLoginAttempts.toString()
        )
    }
}
