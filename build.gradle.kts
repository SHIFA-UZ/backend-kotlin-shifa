plugins {
    // Spring Boot + dependency management plugins
    id("org.springframework.boot") version "3.3.4"
    id("io.spring.dependency-management") version "1.1.6"

    // Kotlin plugins
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.spring") version "2.0.21"
    kotlin("plugin.jpa") version "2.0.21"
}

group = "com.shifa"
version = "0.0.1"
java.sourceCompatibility = JavaVersion.VERSION_21

repositories {
    mavenCentral()
}

dependencies {
    // Core web stack
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

    // Data JPA + Postgres driver
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-cache")
    implementation("org.postgresql:postgresql:42.7.4")

    // We’ll enable Flyway migrations in Step 2	
	// ⬇️ Use the DB-specific module (Flyway split support out of core)
    implementation("org.flywaydb:flyway-core:11.18.0")
    implementation("org.flywaydb:flyway-database-postgresql:11.18.0")
	
	
    // --- NEW: Security + JWT ---
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

    // Optional later: WebSocket
    // implementation("org.springframework.boot:spring-boot-starter-websocket")

    // Swagger UI + OpenAPI
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.6.0")


    // Testing
    testImplementation("org.springframework.boot:spring-boot-starter-test")
	
	//OPEN AI 
	implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.+")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0") // optional

    // If you plan to use caching/rate limiting libraries later:
    implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")  // Cache
    // SECURITY (NEW): Rate limiting - Bucket4j for DDoS/abuse protection
    implementation("com.bucket4j:bucket4j_jdk17-core:8.16.1")

    // Firebase Admin SDK - verify ID tokens (phone OTP doctor login)
    implementation("com.google.firebase:firebase-admin:9.4.0")

    // Email (Brevo SMTP) - OTP verification emails
    implementation("org.springframework.boot:spring-boot-starter-mail")

    // PDF text extraction for AI patient briefing
    implementation("org.apache.pdfbox:pdfbox:3.0.3")

    // Cloud SQL socket factory for GCP Cloud Run
    implementation("com.google.cloud.sql:postgres-socket-factory:1.15.2")

    // Payments
    implementation("com.stripe:stripe-java:30.1.0")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

kotlin {
    jvmToolchain(21)
}

// Produce one deterministic boot jar name for deployments.
tasks.bootJar {
    archiveFileName.set("app.jar")
}

// Disable plain jar to avoid ambiguous artifacts in build/libs.
tasks.jar {
    enabled = false
}
