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

Create a `.env` file in the project root (git-ignored):

```env
JWT_SECRET=your_64_plus_character_secret_here_generate_with_openssl_rand_base64_64
OPENAI_API_KEY=sk-...
DAILY_API_KEY=...
DAILY_WEBHOOK_SECRET=...
FIREBASE_SERVICE_ACCOUNT_JSON={"type":"service_account",...}
GOOGLE_MAPS_API_KEY=...
MAIL_USERNAME=...
MAIL_PASSWORD=...
```

The app loads `.env` automatically in development. All values have sensible defaults except the ones above.

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
├── application.yml         # Default (dev) config
├── application-prod.yml    # Production config
├── application-qa.yml      # QA config
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

## Environment Variables

### Required in Production

| Variable | Description |
|----------|-------------|
| `DATABASE_URL` | PostgreSQL connection URL (Railway provides automatically) |
| `SPRING_PROFILES_ACTIVE` | `prod` or `qa` |
| `JWT_SECRET` | Min 64 characters (`openssl rand -base64 64`) |
| `APP_PUBLICBASEURL` | Backend public URL |
| `APP_FRONTENDURL` | Frontend URL (CORS) |
| `STORAGE_ROOT` | File storage path (default: `/app/storage/images`) |
| `OPENAI_API_KEY` | OpenAI API key |
| `DAILY_API_KEY` | Daily.co API key |
| `DAILY_WEBHOOK_SECRET` | Daily.co webhook verification |
| `FIREBASE_SERVICE_ACCOUNT_JSON` | Firebase service account JSON string |
| `MAIL_USERNAME` | Brevo SMTP username |
| `MAIL_PASSWORD` | Brevo SMTP password |

### Optional

| Variable | Default | Description |
|----------|---------|-------------|
| `PORT` | `8080` | Server port |
| `DB_USERNAME` / `PGUSER` | `shifa` | Database username (fallback) |
| `DB_PASSWORD` / `PGPASSWORD` | `shifa` | Database password (fallback) |
| `OPENAI_PROJECT_ID` | — | OpenAI project ID |
| `OPENAI_MODEL` | `gpt-4o-mini` | OpenAI model |
| `OPENAI_TRANSCRIPTION_SEND_LANG` | `true` | Send `language=` to gpt-4o-transcribe; set `false` if API rejects a code |
| `OPENAI_TRANSCRIPTION_MED_CLEANUP` | `true` | GPT pass to fix medical-term typos on long scribe transcripts |
| `OPENAI_TRANSCRIPTION_MED_CLEANUP_VOICE` | `false` | Also run cleanup on short mic-upload transcriptions |
| `OPENAI_TRANSCRIPTION_MED_CLEANUP_MODEL` | `gpt-4o-mini` | Model for cleanup pass |
| `TRANSCRIPTION_FEEDBACK_ENABLED` | `false` | Enables patient/doctor “report bad transcript” + `transcriptionFeedbackEnabled` in public config |
| `GOOGLE_MAPS_API_KEY` | — | Google Maps geocoding |
| `RATE_LIMIT_AUTH_PER_MIN` | `10` | Auth endpoint rate limit |
| `RATE_LIMIT_GENERAL_PER_MIN` | `200` | General API rate limit |
| `JWT_ACCESS_TOKEN_MINUTES_DOCTOR` | `720` | Doctor token expiry (12h) |
| `JWT_ACCESS_TOKEN_MINUTES_PATIENT` | `525600` | Patient token expiry (365d) |

## Deployment

### Railway (Primary)

The project includes `railway.toml` and `nixpacks.toml` for Railway deployment. Railway auto-detects the build and runs Nixpacks with JDK 21.

```toml
# railway.toml key settings
[deploy]
startCommand = "java -Xmx1536m -Xms512m -XX:+UseG1GC ... -jar build/libs/app.jar"
healthcheckPath = "/actuator/health/liveness"
healthcheckTimeout = 180
restartPolicyType = "on_failure"
restartPolicyMaxRetries = 10
```

Health check timeout is 180 seconds to allow for cold start + Flyway migrations.

### Docker

```bash
docker build -t shifa-api .
docker run -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e DATABASE_URL=postgresql://user:pass@host:5432/shifa \
  -e JWT_SECRET=... \
  shifa-api
```

The Dockerfile uses a multi-stage build: Gradle 8.5 + JDK 21 for build, Eclipse Temurin 21 JRE Alpine for runtime.

### GCP Cloud Run (Alternative)

```bash
gcloud builds submit --config cloudbuild.yaml
```

Supports Cloud SQL via socket factory (`CLOUD_SQL_INSTANCE` env var).

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
