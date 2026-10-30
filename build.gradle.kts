plugins {
    // Spring Boot + dependency management plugins
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"

    // Kotlin plugins
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.spring") version "2.2.21"
    kotlin("plugin.jpa") version "2.2.21"

    // Quality gates (roadmap 1.11): static analysis with a baseline so existing code does not block, coverage floor.
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
    jacoco
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

    // Flyway (the Postgres support lives in its own module since Flyway 10)
    implementation("org.flywaydb:flyway-core:11.18.0")
    implementation("org.flywaydb:flyway-database-postgresql:11.18.0")

    // Security + JWT
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

    // Swagger UI + OpenAPI
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.17")

    // Testing
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Feature-border rules with frozen baselines (src/test/kotlin/com/shifa/ArchitectureTest.kt)
    testImplementation("com.tngtech.archunit:archunit-junit5:1.5.1")
    // Fake OpenAI-compatible endpoints for LLM client tests (same vendor as the runtime OkHttp client).
    testImplementation("com.squareup.okhttp3:mockwebserver:5.5.1")

    // HTTP client for OpenAI, Daily, Click and FCM calls; coroutines for the AI streaming paths
    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // Caffeine caches (CacheConfig) and ShedLock so one instance runs each data-changing scheduled job
    implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")
    implementation("net.javacrumbs.shedlock:shedlock-spring:6.6.0")
    implementation("net.javacrumbs.shedlock:shedlock-provider-jdbc-template:6.6.0")
    // Rate limiting (IP, per-user and AI buckets)
    implementation("com.bucket4j:bucket4j_jdk17-core:8.16.1")

    // Firebase Admin SDK - verify ID tokens (phone OTP doctor login)
    implementation("com.google.firebase:firebase-admin:9.4.0")

    // Email (Brevo SMTP) - OTP verification emails
    implementation("org.springframework.boot:spring-boot-starter-mail")

    // PDF text extraction for AI patient briefing
    implementation("org.apache.pdfbox:pdfbox:3.0.3")

    // Payments
    implementation("com.stripe:stripe-java:30.1.0")

    // Uploads in an S3-compatible bucket (Railway Buckets), roadmap 2.7
    implementation(platform("software.amazon.awssdk:bom:2.57.6"))
    implementation("software.amazon.awssdk:s3")
}

tasks.withType<Test> {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    // Findings in code that existed before the gate are listed here; new code must be clean. Regenerate with
    // ./gradlew detektBaseline only when a rule is intentionally relaxed.
    baseline = file("config/detekt/baseline.xml")
    config.setFrom(file("config/detekt/detekt.yml"))
}

// detekt 1.23 is compiled against Kotlin 2.0; it must run with that compiler even though the project uses 2.2.
configurations.matching { it.name == "detekt" }.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin") useVersion("2.0.21")
    }
}

jacoco {
    toolVersion = "0.8.16"
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

// Coverage ratchet: the floor is the measured line coverage at the time the gate was added; raise it, never lower it.
tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.jacocoTestReport)
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.27".toBigDecimal() // measured 0.283 on 2026-10-29
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
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
