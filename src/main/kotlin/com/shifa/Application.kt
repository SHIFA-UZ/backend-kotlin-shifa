package com.shifa

import com.shifa.config.AiRateLimitProperties
import com.shifa.config.OpenAiProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.cache.annotation.EnableCaching
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * Main Spring Boot application entry point.
 */
@SpringBootApplication
@EnableConfigurationProperties(
    OpenAiProperties::class,
    AiRateLimitProperties::class,
    com.shifa.config.ScribeProperties::class
)
@EnableScheduling
@EnableCaching
class Application

fun main(args: Array<String>) {
    // Load .env file for local development (ignored in git)
    val envFile = java.io.File(".env")
    if (envFile.exists()) {
        envFile.readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") && it.contains("=") }
            .forEach { line ->
                val idx = line.indexOf('=')
                val key = line.substring(0, idx).trim()
                val value = line.substring(idx + 1).trim()
                if (System.getenv(key) == null && value.isNotBlank()) {
                    System.setProperty(key, value)
                }
            }
        println(".env file loaded (${envFile.absolutePath})")
    }

    // Configure database connection
    val databaseUrl = System.getenv("DATABASE_URL")
    val cloudSqlInstance = System.getenv("CLOUD_SQL_INSTANCE")
    val activeProfile = System.getenv("SPRING_PROFILES_ACTIVE") ?: "dev"
    val isProduction = activeProfile.contains("prod", ignoreCase = true)
    val isQa = activeProfile.equals("qa", ignoreCase = true)
    val requiresDatabase = isProduction || isQa

    var databaseConfigured = false

    // GCP Cloud Run: connect via Cloud SQL socket factory
    if (!cloudSqlInstance.isNullOrBlank()) {
        val dbName = System.getenv("DB_NAME") ?: "shifa"
        val dbUser = System.getenv("DB_USER") ?: "shifa_app"
        val dbPass = System.getenv("DB_PASS") ?: ""
        val jdbcUrl = "jdbc:postgresql:///$dbName?cloudSqlInstance=$cloudSqlInstance&socketFactory=com.google.cloud.sql.postgres.SocketFactory"
        System.setProperty("spring.datasource.url", jdbcUrl)
        System.setProperty("spring.datasource.username", dbUser)
        System.setProperty("spring.datasource.password", dbPass)
        println("Cloud SQL: Connecting via socket factory to instance $cloudSqlInstance, database $dbName")
        databaseConfigured = true
    } else if (databaseUrl != null && databaseUrl.isNotBlank()) {
        // Log format without exposing sensitive data
        val urlPreview = when {
            databaseUrl.startsWith("jdbc:") -> "JDBC format"
            databaseUrl.startsWith("postgresql://") -> "postgresql:// format"
            databaseUrl.startsWith("postgres://") -> "postgres:// format"
            else -> {
                // Show first few chars to help debug format issues
                val preview = databaseUrl.take(50)
                "Unknown format (starts with: ${preview.replace(Regex("[^a-zA-Z0-9:/@.-]"), "?")}...)"
            }
        }
        println("DATABASE_URL from environment: SET ($urlPreview)")
        
        if (databaseUrl.startsWith("jdbc:")) {
            // Already in JDBC format, use it directly
            System.setProperty("spring.datasource.url", databaseUrl)
            println("DATABASE_URL already in JDBC format, using directly")
            databaseConfigured = true
        } else if (databaseUrl.startsWith("postgresql://") || databaseUrl.startsWith("postgres://")) {
            // Railway format: postgresql://user:pass@host:port/dbname
            // Convert to: jdbc:postgresql://host:port/dbname
            try {
                val url = databaseUrl.removePrefix("postgresql://").removePrefix("postgres://")
                val atIndex = url.indexOf('@')
                if (atIndex != -1) {
                    val credentials = url.substring(0, atIndex)
                    val hostAndDb = url.substring(atIndex + 1)
                    val colonIndex = credentials.indexOf(':')
                    if (colonIndex != -1) {
                        val username = credentials.substring(0, colonIndex)
                        // Handle URL-encoded passwords (Railway may encode special chars)
                        val password = try {
                            java.net.URLDecoder.decode(credentials.substring(colonIndex + 1), "UTF-8")
                        } catch (e: Exception) {
                            // If decoding fails, use as-is
                            credentials.substring(colonIndex + 1)
                        }
                        val slashIndex = hostAndDb.indexOf('/')
                        if (slashIndex != -1) {
                            val hostAndPort = hostAndDb.substring(0, slashIndex)
                            val database = hostAndDb.substring(slashIndex + 1)
                            // Remove query parameters if present
                            val dbName = database.split('?').first()
                            val hostPortParts = hostAndPort.split(':')
                            val host = hostPortParts[0]
                            val port = if (hostPortParts.size > 1) hostPortParts[1] else "5432"
                            val jdbcUrl = "jdbc:postgresql://$host:$port/$dbName"
                            System.setProperty("spring.datasource.url", jdbcUrl)
                            System.setProperty("spring.datasource.username", username)
                            System.setProperty("spring.datasource.password", password)
                            println("Converted Railway DATABASE_URL to JDBC format: jdbc:postgresql://$host:$port/$dbName")
                            println("Username: $username")
                            databaseConfigured = true
                        } else {
                            println("ERROR: Could not find database name in DATABASE_URL (format: host:port/dbname expected)")
                            println("DEBUG: hostAndDb = ${hostAndDb.take(50)}")
                        }
                    } else {
                        println("ERROR: Could not parse credentials from DATABASE_URL (format: user:pass@ expected)")
                        println("DEBUG: credentials = ${credentials.take(20)}")
                    }
                } else {
                    // Maybe it's in a different format - try parsing as host:port/dbname with separate env vars
                    println("WARNING: DATABASE_URL doesn't have @ separator, checking for separate env vars...")
                    val dbHost = System.getenv("PGHOST") ?: System.getenv("DB_HOST")
                    val dbPort = System.getenv("PGPORT") ?: System.getenv("DB_PORT") ?: "5432"
                    val dbName = System.getenv("PGDATABASE") ?: System.getenv("DB_NAME")
                    val dbUser = System.getenv("PGUSER") ?: System.getenv("DB_USERNAME")
                    val dbPass = System.getenv("PGPASSWORD") ?: System.getenv("DB_PASSWORD")
                    
                    if (dbHost != null && dbName != null && dbUser != null && dbPass != null) {
                        val jdbcUrl = "jdbc:postgresql://$dbHost:$dbPort/$dbName"
                        System.setProperty("spring.datasource.url", jdbcUrl)
                        System.setProperty("spring.datasource.username", dbUser)
                        System.setProperty("spring.datasource.password", dbPass)
                        println("Using separate environment variables for database connection")
                        databaseConfigured = true
                    } else {
                        println("ERROR: Could not parse DATABASE_URL and separate env vars not found")
                        println("DEBUG: DATABASE_URL format = ${databaseUrl.take(50)}...")
                        println("DEBUG: Looking for PGHOST/PGPORT/PGDATABASE/PGUSER/PGPASSWORD or DB_* equivalents")
                    }
                }
            } catch (e: Exception) {
                println("ERROR: Could not convert DATABASE_URL format: ${e.message}")
                e.printStackTrace()
            }
        } else {
            // Unknown format - try to use it as-is or check for separate env vars
            println("WARNING: DATABASE_URL in unknown format, checking for separate env vars...")
            val dbHost = System.getenv("PGHOST") ?: System.getenv("DB_HOST")
            val dbPort = System.getenv("PGPORT") ?: System.getenv("DB_PORT") ?: "5432"
            val dbName = System.getenv("PGDATABASE") ?: System.getenv("DB_NAME")
            val dbUser = System.getenv("PGUSER") ?: System.getenv("DB_USERNAME")
            val dbPass = System.getenv("PGPASSWORD") ?: System.getenv("DB_PASSWORD")
            
            if (dbHost != null && dbName != null && dbUser != null && dbPass != null) {
                val jdbcUrl = "jdbc:postgresql://$dbHost:$dbPort/$dbName"
                System.setProperty("spring.datasource.url", jdbcUrl)
                System.setProperty("spring.datasource.username", dbUser)
                System.setProperty("spring.datasource.password", dbPass)
                println("Using separate environment variables for database connection")
                databaseConfigured = true
            } else {
                // Last resort: try to use DATABASE_URL as-is (maybe it's already JDBC without prefix)
                if (databaseUrl.contains("://") && databaseUrl.contains("/")) {
                    // Might be a valid connection string, try adding jdbc: prefix
                    val jdbcUrl = when {
                        databaseUrl.startsWith("postgresql://") -> "jdbc:$databaseUrl"
                        databaseUrl.startsWith("postgres://") -> "jdbc:$databaseUrl"
                        databaseUrl.startsWith("jdbc:") -> databaseUrl
                        else -> {
                            // Try to detect if it's a postgres URL without protocol
                            if (databaseUrl.contains("@") && databaseUrl.contains("/")) {
                                "jdbc:postgresql://${databaseUrl.removePrefix("postgresql://").removePrefix("postgres://")}"
                            } else {
                                databaseUrl
                            }
                        }
                    }
                    System.setProperty("spring.datasource.url", jdbcUrl)
                    println("WARNING: Using DATABASE_URL as-is (may fail if format is incorrect)")
                    println("DEBUG: Attempting to use: ${jdbcUrl.take(50)}...")
                    databaseConfigured = true
                } else {
                    println("ERROR: Cannot determine DATABASE_URL format")
                    println("DEBUG: DATABASE_URL length: ${databaseUrl.length}")
                    println("DEBUG: Contains '://': ${databaseUrl.contains("://")}")
                    println("DEBUG: Contains '/': ${databaseUrl.contains("/")}")
                    println("DEBUG: First 100 chars (sanitized): ${databaseUrl.take(100).replace(Regex("[^a-zA-Z0-9:/@.-]"), "?")}")
                }
            }
        }
    } else {
        println("WARNING: DATABASE_URL not set")
    }
    
    // In prod or qa (Railway), fail fast if database is not configured
    if (requiresDatabase && !databaseConfigured) {
        println("ERROR: Profile $activeProfile requires a database connection but none is configured!")
        println("ERROR: Set CLOUD_SQL_INSTANCE (GCP) or DATABASE_URL (Railway) or DB_* env vars")
        System.exit(1)
    } else if (!databaseConfigured) {
        println("WARNING: Using default localhost connection from application.yml")
    }

    // Firebase (Railway): if no GOOGLE_APPLICATION_CREDENTIALS path, use FIREBASE_SERVICE_ACCOUNT_JSON (raw JSON)
    if (System.getenv("GOOGLE_APPLICATION_CREDENTIALS").isNullOrBlank()) {
        val json = System.getenv("FIREBASE_SERVICE_ACCOUNT_JSON")
        if (!json.isNullOrBlank()) {
            try {
                val file = java.io.File.createTempFile("firebase-sa-", ".json")
                file.deleteOnExit()
                file.writeText(json)
                System.setProperty("GOOGLE_APPLICATION_CREDENTIALS", file.absolutePath)
                println("Firebase: Using FIREBASE_SERVICE_ACCOUNT_JSON from environment (Railway)")
            } catch (e: Exception) {
                println("WARNING: Could not write Firebase credentials from FIREBASE_SERVICE_ACCOUNT_JSON: ${e.message}")
            }
        }
    }
    
    runApplication<Application>(*args)
}
