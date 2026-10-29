# Shifa Doctor Backend

Medical platform backend connecting doctors and patients. Built with Kotlin + Spring Boot, deployed on Railway.

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Kotlin 2.0.21 / Java 21 |
| Framework | Spring Boot 3.3.4 |
| Database | PostgreSQL 16 |
| ORM | Spring Data JPA (Hibernate) + Flyway migrations |
| Auth | JWT (JJWT 0.12.6) with session binding, Firebase phone OTP, BCrypt |
| AI | OpenAI GPT-4o-mini, Whisper transcription, PDFBox |
| Video | Daily.co |
| Cache | Caffeine |
| Rate Limiting | Bucket4j |
| Email | Brevo SMTP |
| API Docs | SpringDoc OpenAPI (Swagger UI) |
| Build | Gradle 8.5 (Kotlin DSL) |

## Prerequisites

- **Java 21** (or use the Gradle toolchain — it downloads automatically)
- **Docker** (for local PostgreSQL)
- **Git**

## Getting Started

### 1. Clone and set up environment

```bash
git clone <repo-url>
cd backend-kotlin-shifa
```

Copy `src/main/resources/application-local.example.yml` to `application-local.yml` (git-ignored) in the same folder
and fill in the keys you need. Everything left empty stays off or logs instead (OTP codes, SMS, AI). Firebase
(phone OTP, push) reads the `FIREBASE_SERVICE_ACCOUNT_JSON` environment variable directly.

### 2. Start the database

```bash
docker-compose up -d
```

This starts PostgreSQL 16 on port **5433** (not 5432, to avoid conflicts with a local install).

- Database: `shifa`
- Username: `shifa`
- Password: `shifa`

### 3. Run the application

```bash
./gradlew bootRun
```

Flyway runs all migrations automatically on startup. The app is available at `http://localhost:8080`.

### 4. Verify

- **Health check:** `http://localhost:8080/actuator/health`
- **Swagger UI:** `http://localhost:8080/swagger-ui/index.html`
- **API docs:** `http://localhost:8080/v3/api-docs`

## Common Commands

```bash
# Start local PostgreSQL
docker-compose up -d

# Stop local PostgreSQL
docker-compose down

# Run the app (dev profile)
./gradlew bootRun

# Run tests
./gradlew test

# Build production JAR
./gradlew build

# Run the built JAR
java -jar build/libs/app.jar
```

## Project Structure

```
src/main/kotlin/com/shifa/
├── Application.kt          # Entry point, DB config, Firebase init
├── ai/                     # Medical AI: symptom matching, red flags, severity scoring
├── config/                 # Spring configs: async, cache, static resources, properties
├── domain/                 # JPA entities (34)
├── repo/                   # Spring Data JPA repositories (35)
├── security/               # JWT filter, rate limiting, security headers, principals
├── service/                # Business logic (39 services)
├── util/                   # Utilities (phone normalization)
└── web/                    # REST controllers (37) + DTOs
    └── dto/                # Shared DTOs

src/main/resources/
├── application.yml         # Base config, identical everywhere (local defaults)
├── application-prod.yml    # Railway (production; development adds application-dev.yml)
├── application-dev.yml     # Development overlay (profile prod,dev)
└── db/migration/           # Flyway SQL migrations (V1 through V56)

src/test/kotlin/com/shifa/  # Tests (JUnit 5 + MockMvc)
```

## Architecture

### Layers

**Controllers** (`web/`) handle HTTP requests, validate input with `@RequestBody @Valid`, and delegate to services. DTOs are Kotlin `data class` definitions, typically inline in the controller file.

**Services** (`service/`) contain business logic and manage transactions via `@Transactional`. They throw `IllegalArgumentException` (400) or `ResponseStatusException` for errors.

**Repositories** (`repo/`) extend `JpaRepository` with custom JPQL/native queries. Use `LEFT JOIN FETCH` to prevent N+1 problems.

**Entities** (`domain/`) are JPA-mapped classes. IDs are auto-increment `Long`. Enums stored as `STRING`. Timestamps use `OffsetDateTime`, `Instant`, or `LocalDate` depending on semantics.

### Authentication

Three roles: `DOCTOR`, `PATIENT`, `ADMIN`. A user can hold multiple roles.

JWT tokens contain a `jti` (JWT ID) bound to a `UserSession` row in the database, enabling real-time session revocation (force logout). Doctor tokens expire in 12 hours, patient tokens in 365 days.

Security filter chain order:
1. `SecurityHeadersFilter` — HTTPS enforcement, CSP, X-Frame-Options
2. `RateLimitFilter` — IP-based rate limiting (10/min auth, 200/min general)
3. `JwtAuthFilter` — token validation, session binding check, principal resolution
4. `UserRateLimitFilter` — per-user rate limiting

### AI Module

Stateless Kotlin `object` singletons for medical analysis: `SymptomMatcher`, `RedFlagEngine`, `SeverityScorer`, `MedicalPromptBuilder`, `StructuredNoteParser`, `NegationDetector`, `AmplifierDetector`.

OpenAI integration uses SSE streaming via Kotlin `Flow<String>`. The scribe pipeline (voice-to-notes) runs async on a dedicated thread pool.

### Background Processing

| Task | Schedule | Service |
|------|----------|---------|
| Appointment reminders | Every 60 seconds | `ReminderNotificationScheduler` |
| AI draft cleanup | Daily at 2 AM | `AiDraftNoteCleanupScheduler` |
| Expired OTP cleanup | Every hour | `EmailOtpService` |
| Voice-to-notes scribe | Async (thread pool: core=2, max=4) | `ScribePipelineService` |

## API Overview

All routes are under `/api/`. See Swagger UI for full documentation.

| Prefix | Role | Description |
|--------|------|-------------|
| `/api/auth/**` | Public | Login, register, OTP, password reset |
| `/api/public/**` | Public | Public doctor listings |
| `/api/patients/me/**` | PATIENT | Patient self-service |
| `/api/doctors/me/**` | DOCTOR | Doctor profile management |
| `/api/patients/**` | DOCTOR | Patient management |
| `/api/appointments/**` | DOCTOR | Appointment CRUD |
| `/api/schedule/**` | DOCTOR | Schedule management |
| `/api/messages/**` | DOCTOR | Chat/messaging |
| `/api/consultations/**` | DOCTOR | Consultation notes |
| `/api/ai/**` | Authenticated | AI diagnosis suggestions |
| `/api/video/**` | Authenticated | Daily.co video calls |
| `/api/admin/**` | ADMIN | User management, system config |
| `/api/webhooks/daily` | Public | Daily.co recording webhooks |

## Database Migrations

Migrations are managed by Flyway and run automatically on startup.

```bash
# Create a new migration (next number after V56)
touch src/main/resources/db/migration/V57__description_here.sql
```

Naming convention: `V{number}__{snake_case_description}.sql` (double underscore).

Never modify an existing migration. In dev, Hibernate `ddl-auto: update` fills gaps, but prod uses `validate` — migrations are the only way to change the schema.

## Configuration

Only secrets are environment variables (roadmap 3.6). Everything else (URLs, model names, limits, merchant ids, mail
sender) is a literal in `application.yml`, `application-prod.yml` or `application-dev.yml`; changing it is a pull
request. `ConfigPolicyTest` fails on any other placeholder and pins the production and development values.

### Railway variables (both environments)

| Variable | Description |
|----------|-------------|
| `SPRING_PROFILES_ACTIVE` | `prod` (production) or `prod,dev` (development) |
| `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` | Reference variables to the environment's Postgres (`${{Postgres.PGHOST}}` etc.) |
| `JWT_SECRET` | Min 64 characters (`openssl rand -base64 64`); rotating it logs every user out |
| `OPENAI_API_KEY`, `GEMINI_API_KEY` | AI providers (`XAI_API_KEY` optional) |
| `DAILY_API_KEY`, `DAILY_WEBHOOK_SECRET` | Daily.co video |
| `FIREBASE_SERVICE_ACCOUNT_JSON` | Firebase service account JSON string (read directly by the Firebase services) |
| `MAIL_PASSWORD` | Brevo SMTP password |
| `STRIPE_SECRET_KEY`, `STRIPE_WEBHOOK_SECRET` | Stripe (development sets them to empty in yml) |
| `CLICK_SECRET_KEY` | Click callback signing |
| `DEVSMS_API_TOKEN` | SMS provider (development sets it to empty in yml) |
| `GOOGLE_MAPS_API_KEY` | Served to clients by `/api/public/config` |
| `S3_BUCKET`, `S3_ACCESS_KEY`, `S3_SECRET_KEY` | Reference variables to the environment's Railway bucket (`${{shifa-uploads.BUCKET}}` etc.), used where `app.storage.s3.enabled` is true |

Railway also injects `PORT` and `RAILWAY_GIT_COMMIT_SHA` (shown by `/actuator/info`). A missing secret fails startup
with the variable's name in the log.

### Local

Copy `application-local.example.yml` to `application-local.yml` (git-ignored) and fill in what you need.

## API contract

`docs/api/openapi.json` is the OpenAPI document the apps build against, generated by the backend itself.
`ApplicationContextTest` (CI, against PostgreSQL) fails when the running code produces a different document, so
every change to a path, parameter or schema must update the snapshot in the same pull request. To update it: run
`./gradlew test --tests com.shifa.ApplicationContextTest` with a local database (or download the `openapi` artifact of
the failed CI run) and copy `build/openapi.json` over `docs/api/openapi.json`, or take
`https://api-dev.shifa.uz/v3/api-docs` once the change is on development (drop the `servers` entry).

## Deployment

### Railway (Primary)

Railway builds the repository with the `Dockerfile` (the Dockerfile is auto-detected). The deploy settings live in
the Railway **service settings** of each environment, not in the repository (Railway stops reading
`railway.toml` / `railway.json` on 2026-12-01; the files were removed in October 2026 once the settings were applied):

| Setting | Value |
|---|---|
| Start command | `java -Xmx1536m -Xms512m -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:+UseStringDeduplication -XX:MaxMetaspaceSize=384m -jar build/libs/app.jar` |
| Health check path | `/actuator/health/liveness` |
| Health check timeout | 180 s (cold start + Flyway) |
| Restart policy | on failure, max 10 retries |

Environments: `development` deploys `main` (`https://api-dev.shifa.uz`, `SPRING_PROFILES_ACTIVE=prod,dev`);
`production` deploys the `production` branch (`https://api.shifa.uz`, `SPRING_PROFILES_ACTIVE=prod`), moved only by the
"Release to production" GitHub workflow. See `docs/ARCHITECTURE_ROADMAP.md` (1.13) for how the settings were applied.

### Docker

The Dockerfile's own ENTRYPOINT (the image default, used when no start command is configured) runs the JRE stage with **`Xmx≈1024m`** heap (512m proved too tight for Hibernate + Flyway startup and led to unhealthy deploys—Tomcat never bound before health timeouts). Provision **≥ ~2 GiB RAM** on the Railway service or, to change the heap, change the `-Xmx` in the Railway start command (explicit flags there win over `JAVA_TOOL_OPTIONS`).

```bash
docker build -t shifa-api .
docker run -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e DATABASE_URL=postgresql://user:pass@host:5432/shifa \
  -e JWT_SECRET=... \
  shifa-api
```

The Dockerfile uses a multi-stage build: Gradle 8.5 + JDK 21 for build, Eclipse Temurin 21 JRE Alpine for runtime.

## Static File Serving

Uploaded files (profile photos, documents, certificates, chat attachments) are served from `STORAGE_ROOT` with 1-hour browser cache:

| URL Path | Storage Subdirectory |
|----------|---------------------|
| `/doctors/**` | `{STORAGE_ROOT}/doctors/` |
| `/patients/**` | `{STORAGE_ROOT}/patients/` |
| `/patientdocuments/**` | `{STORAGE_ROOT}/patientdocuments/` |
| `/certificates/**` | `{STORAGE_ROOT}/certificates/` |
| `/chat-attachments/**` | `{STORAGE_ROOT}/chat-attachments/` |

## Testing

```bash
./gradlew test
```

Tests use JUnit 5, MockMvc, Mockito, and Hamcrest. Controller tests use `@WebMvcTest` with `@MockBean` for dependencies and `@AutoConfigureMockMvc(addFilters = false)` to bypass security filters.

## License

Private and confidential. All rights reserved
