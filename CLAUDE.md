# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Shifa Doctor Backend MVP — a medical platform backend connecting doctors and patients. Built with Kotlin + Spring Boot, deployed on Railway.

## Tech Stack

- **Language:** Kotlin 2.0.21, Java 21
- **Framework:** Spring Boot 3.3.4 with Spring Security
- **Database:** PostgreSQL 16 with Spring Data JPA (Hibernate), HikariCP connection pooling
- **Migrations:** Flyway 11.18.0 (57 migrations in `src/main/resources/db/migration/`)
- **Auth:** JWT (JJWT 0.12.6) with session binding (JTI), Firebase phone OTP for patients, BCrypt passwords
- **AI:** OpenAI GPT-4o-mini via OkHttp, Apache PDFBox 3.0.3 for PDF extraction, Whisper transcription
- **Video:** Daily.co integration with webhook recording
- **Caching:** Caffeine 3.1.8 (ICD-10 search cache, 5min TTL, 10k max entries)
- **Rate Limiting:** Bucket4j (10 req/min auth, 200 req/min general) — IP-based + per-user
- **Email:** Brevo SMTP (OTP delivery)
- **API Docs:** SpringDoc OpenAPI 2.6.0 (Swagger UI at `/swagger-ui/`)
- **Build:** Gradle 8.5 (Kotlin DSL), output: `build/libs/app.jar`

## Common Commands

```bash
# Start local PostgreSQL (port 5433)
docker-compose up -d

# Run the application (dev profile)
./gradlew bootRun

# Run tests
./gradlew test

# Build production JAR
./gradlew build

# Run production JAR (Railway memory-constrained)
java -Xmx350m -Xms256m -jar build/libs/app.jar
```

## Architecture

**Layered structure under `src/main/kotlin/com/shifa/`:**

| Package | Count | Purpose |
|---------|-------|---------|
| `web/` | 37 controllers + 10 DTO files | REST controllers and request/response DTOs. All routes under `/api/`. |
| `service/` | 39 services | Business logic, transactions, external API calls. |
| `repo/` | 35 repositories | Spring Data JPA repositories with custom `@Query` methods. |
| `domain/` | 34 entities | JPA entities. Core: `User`, `DoctorProfile`, `PatientProfile`, `Appointment`, `ConsultationNote`, `Message`. |
| `security/` | 13 files | JWT filter chain, rate limiting, security headers, role principals. |
| `ai/` | 16 files | Medical AI: `SymptomMatcher`, `RedFlagEngine`, `SeverityScorer`, `MedicalPromptBuilder`, `StructuredNoteParser`. |
| `config/` | 6 configs | Async executor, Caffeine cache, static resources, app/OpenAI/scribe properties. |
| `util/` | 1 file | `PhoneNormalizer` — E.164 phone normalization. |

**Total: 194 Kotlin source files, 2 test files.**

### Auth Model

Three roles — `DOCTOR`, `PATIENT`, `ADMIN`. Users can hold multiple roles. JWT tokens include `jti` for session revocation via `UserSession` table. Doctor tokens expire in 12h, patient tokens in 365 days.

### Security Filter Chain Order

1. `SecurityHeadersFilter` (order: -200) — HTTPS enforcement, CSP, X-Frame-Options
2. `RateLimitFilter` (order: -100) — IP-based rate limiting via Bucket4j
3. `JwtAuthFilter` — JWT validation, session binding check, principal resolution
4. `UserRateLimitFilter` (order: 100) — Per-user rate limiting

### Route Authorization

- `/api/patients/me/**` → PATIENT only
- `/api/doctors/me/**`, `/api/schedule/**`, `/api/calendar/**`, `/api/appointments/**` → DOCTOR only
- `/api/admin/**` → ADMIN only
- `/api/public/**`, `/api/webhooks/daily`, `/api/auth/**`, `/api/test/**` → no auth
- `/actuator/health/**`, `/swagger-ui/**`, `/v3/api-docs/**` → no auth
- Static files: `/doctors/**`, `/patients/**`, `/patientdocuments/**`, `/certificates/**`, `/chat-attachments/**`, `/photos/**` → no auth

## Entry Point

`Application.kt` — handles multiple database connection modes:
1. GCP Cloud SQL socket factory (via `CLOUD_SQL_INSTANCE`)
2. Railway `DATABASE_URL` (converts `postgresql://` to JDBC)
3. Direct JDBC URL
4. Fallback to individual `PG*` env vars

Also loads `.env` for local dev and writes `FIREBASE_SERVICE_ACCOUNT_JSON` to temp file for Firebase SDK init.

Annotations: `@EnableConfigurationProperties`, `@EnableScheduling`, `@EnableCaching`.

## Database

Local dev uses Docker PostgreSQL on port **5433** (not default 5432). Credentials: `shifa/shifa/shifa`. Flyway runs migrations automatically on startup. Dev uses `hibernate.ddl-auto: update`; prod uses `validate`.

Production HikariCP: max 20 connections, min 5 idle, 30s connection timeout, 30min max lifetime.

## Deployment

- **Primary:** Railway (Nixpacks builder, `railway.toml`)
- **Alternative:** GCP Cloud Run (`cloudbuild.yaml`, Dockerfile)
- **Profiles:** `default` (dev), `prod`, `qa`
- **Health check:** `/actuator/health/liveness` (180s timeout for cold start + migrations)
- **JVM:** `-Xmx1536m -Xms512m -XX:+UseG1GC -XX:+UseStringDeduplication`
- **Restart:** on_failure, max 10 retries

## Static File Serving

Uploads served from `storageRoot` (default `./public-storage/images`) with 1-hour cache. Paths: `/doctors/**`, `/patients/**`, `/patientdocuments/**`, `/certificates/**`, `/chat-attachments/**`, `/photos/**`.

## Key Environment Variables

`DATABASE_URL`, `JWT_SECRET` (64+ chars), `OPENAI_API_KEY`, `OPENAI_PROJECT_ID`, `DAILY_API_KEY`, `DAILY_WEBHOOK_SECRET`, `FIREBASE_SERVICE_ACCOUNT_JSON`, `STORAGE_ROOT`, `APP_PUBLICBASEURL`, `APP_FRONTENDURL`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `GOOGLE_MAPS_API_KEY`. See `README_ENVIRONMENT.md` for full details.

## Background Processing

- `@Async` scribe pipeline (voice-to-notes): ThreadPool `scribeTaskExecutor` core=2, max=4, queue=50
- Scheduled tasks: appointment reminders (`ReminderNotificationScheduler`), AI draft cleanup (`AiDraftNoteCleanupScheduler`), calendar resets (`AdminCalendarResetService`)

## Git Workflow

**NEVER commit and push directly to `main`.** When the user wants to commit and push changes while on `main`:

1. Create a new feature branch from `main` with a descriptive name based on the changes (e.g., `feature/add-patient-search`, `fix/appointment-timezone-bug`, `refactor/auth-middleware`).
2. Stage and commit the changes on the new branch.
3. Push the new branch to the remote with `-u` flag.
4. Offer to create a pull request targeting `main`.

If the user is already on a non-main branch, commit and push normally to that branch.

## Commit Message Convention

Follow **Conventional Commits** format:

```
<type>(<scope>): <short summary>
```

**Types:**
- `feat` — new feature
- `fix` — bug fix
- `refactor` — code restructuring without behavior change
- `docs` — documentation only
- `style` — formatting, missing semicolons, etc. (no logic change)
- `test` — adding or updating tests
- `chore` — build config, dependencies, CI, tooling
- `perf` — performance improvement

**Rules:**
- Subject line: imperative mood, lowercase, no period, max 72 characters (e.g., `feat(appointments): add recurring appointment support`)
- Scope is optional but encouraged — use the module or domain area (e.g., `auth`, `appointments`, `ai`, `patients`, `doctors`, `admin`, `video`, `chat`)
- Add a blank line then a body paragraph only when the **why** is not obvious from the subject
- Never reference ticket numbers or conversation context in commit messages — those belong in the PR description

## Coding Best Practices

These rules apply to ALL code written in this project. Follow them strictly.

### Kotlin Style

- **Constructor injection only** — never use `@Autowired` field injection. All dependencies as `private val` constructor params.
- **`val` over `var`** — use immutable references by default. Only use `var` for JPA entity fields that Hibernate mutates.
- **Nullable types for optional fields** — use `Type?` with `= null` default. Never use `Optional<T>` in Kotlin code (only in repository return types for Java interop).
- **Use Kotlin idioms** — `?.let {}`, `?:` (elvis), `when` expressions, string templates `"$var"`, `also`/`apply` for initialization, `runCatching` for exception handling in filters.
- **Avoid `!!` (non-null assertion)** — prefer `?:`, `?.let`, or `requireNotNull()` with a descriptive message. Some legacy `!!` exists in the codebase but new code should not introduce more.
- **Data classes for DTOs** — all request/response DTOs must be `data class` with `val` fields.
- **Regular classes for JPA entities** — never use `data class` for `@Entity`. Entities that participate in lazy-loading proxies (e.g., `PatientProfile`, `Message`) use `open class` with `open var` fields and a `protected constructor()`. Simpler entities (e.g., `User`, `Appointment`) use regular `class` without `open`.
- **Extension function chaining** for collection operations: `.filter { }.map { }.distinct()` over imperative loops.
- **Kotlin `object` for stateless logic** — use `object` singletons for pure utility/algorithm classes (e.g., `PhoneNormalizer`, `PasswordPolicy`, all AI modules like `RedFlagEngine`, `SymptomMatcher`, `SeverityScorer`).
- **`lateinit var`** — only for `@ConfigurationProperties` beans (e.g., `AppProperties`, `OpenAiProperties`). Never for service or controller fields.
- **`companion object`** — use for constants (e.g., `CacheConfig.ICD10_SEARCH_CACHE`, `EmailVerificationCode.PURPOSE_*`) and factory methods. Never for mutable state.
- **Logging** — use SLF4J via `LoggerFactory.getLogger(ClassName::class.java)`. Name the field `log` (preferred) consistently in new code.

### Controllers (`web/`)

- Annotate with `@RestController` and `@RequestMapping("/api/{resource}")`.
- Inject the authenticated principal via `@AuthenticationPrincipal principal: DoctorPrincipal` (or `PatientPrincipal`, `AdminPrincipal`). For role-agnostic endpoints, use `UserDetails`.
- DTOs are typically inline `data class` definitions within the controller file. Move to `web/dto/` only when reused across multiple controllers.
- Use `@RequestBody @Valid` for request validation. Add `@field:NotBlank`, `@field:Size`, `@field:Email` on DTO fields.
- Throw `ResponseStatusException(HttpStatus.XXX, "message")` for HTTP errors.
- Return plain objects/lists from controller methods (Spring auto-wraps as 200 JSON). Use `ResponseEntity<T>` only when you need explicit status control (e.g., `ResponseEntity.noContent().build()` for 204).
- Use `@PageableDefault(size = 50)` for paginated endpoints (adjust size per use case — e.g., assignment lists may use 500).
- Keep controllers thin — delegate all business logic to services.

### Services (`service/`)

- Annotate with `@Service` for business logic services. Use `@Component` for mappers, schedulers, filters, and infrastructure beans.
- Annotate write methods with `@Transactional`. Annotate read-only methods with `@Transactional(readOnly = true)` — this enables Hibernate flush-mode optimizations.
- **Exception throwing conventions:**
  - `IllegalArgumentException("message")` — for business rule violations (mapped to 400 by `ErrorHandler`).
  - `ResponseStatusException(HttpStatus.XXX, "message")` — when the service knows the correct HTTP status (e.g., 404 NOT_FOUND, 403 FORBIDDEN, 409 CONFLICT).
  - `NoSuchElementException("message")` — acceptable from `.orElseThrow {}` on Optional lookups.
- Use `repository.findById(id).orElseThrow { IllegalArgumentException("...") }` for entity lookups.
- Services may call other services via constructor injection — keep the dependency graph acyclic.
- Name mapper classes explicitly: `DoctorProfileMapper`, `PatientProfileMapper` (annotated with `@Component`).

### Repositories (`repo/`)

- Extend `JpaRepository<Entity, Long>`.
- Prefer JPQL `@Query` for complex queries, derived method names for simple ones, native SQL (`nativeQuery = true`) only when JPQL cannot express the query (e.g., `LIMIT 1`, window functions, `NOT EXISTS` subqueries).
- Use `LEFT JOIN FETCH` in JPQL queries to prevent N+1 problems — never rely on lazy loading in controllers.
- Return `Optional<Entity>` for single-result queries, `Entity?` (nullable) for derived `findFirst*` queries, `List<Entity>` for multiple results, `Page<Entity>` for paginated results.
- `@Modifying` annotation required on `UPDATE`/`DELETE` queries.
- Name methods: `findBy{Field}`, `findFirst{Field}`, `findAll{Criterion}`, `countBy{Field}`, `existsBy{Field}`.
- For complex queries requiring `EntityManager` directly (e.g., dynamic search with optional filters), use the custom repository pattern: `{Entity}RepositoryCustom` interface + `{Entity}RepositoryImpl` class (see `UserRepositoryCustom`/`UserRepositoryImpl`).

### Entities (`domain/`)

- `@Id @GeneratedValue(strategy = GenerationType.IDENTITY)` — auto-increment `Long` IDs (default `0` or `null` depending on entity style).
- `@Enumerated(EnumType.STRING)` — store enums as strings, never ordinals. Nested enums in entities are fine (e.g., `Appointment.Status`, `Message.MessageType`).
- **Timestamp types** — use the correct type for the field's semantics:
  - `OffsetDateTime` — for timezone-aware business events (user activity: `createdAt`, `deletedAt`, `lastLoginAt`, session `expiresAt`).
  - `Instant` — for absolute point-in-time fields (appointment `startAt`/`endAt`, JWT expiry, `patientSignedAt`).
  - `LocalDate` — for date-only fields (birth date, schedule validity dates).
- Timestamps are managed in services, not via `@CreatedDate`/`@LastModifiedDate` — this project does not use JPA auditing.
- Relationships: use explicit `fetch = FetchType.LAZY` on `@ManyToOne` and `@OneToOne`. Use `cascade = [CascadeType.ALL], orphanRemoval = true` only on owning-side `@OneToMany` collections.
- Large text: `@Column(columnDefinition = "TEXT")`.
- Unique constraints: `@Column(unique = true)` on phone, email, username.

### Error Handling

- Global `ErrorHandler` (`@ControllerAdvice`) handles all exceptions. Response format:
  ```json
  {"error": <status_code>, "message": "<text>", "status": <status_code>}
  ```
  Validation errors also include `"code": "VALIDATION"` and `"errors": {"field": "reason"}`.
- Exception mapping:
  - `MethodArgumentNotValidException` → 400 with field errors map
  - `IllegalArgumentException` → 400
  - `AccessDeniedException` → 403
  - `ResponseStatusException` → pass-through status code
  - `Exception` (catch-all) → 500 (generic "Internal server error" in prod, actual message in dev)
- `AiStreamException(code, message)` — custom exception for AI/SSE streaming errors (rate limits, safety blocks, provider failures).
- **Never** expose stack traces or internal details in production responses.

### Security

- Never hardcode secrets — all sensitive values from environment variables.
- Passwords hashed with BCrypt via `PasswordEncoder` bean. Validate with `PasswordPolicy.validate()` before hashing. Policy: min 8 chars, max 128, at least 1 uppercase + 1 lowercase + 1 digit + 1 special character.
- New endpoints must be added to `SecurityConfig.kt` authorization rules. Default is **deny** (`anyRequest().authenticated()`). Role checks use `hasRole("DOCTOR")`, `hasRole("PATIENT")`, `hasRole("ADMIN")`.
- Public endpoints require explicit whitelisting in both `SecurityConfig` (via `.permitAll()`) and `JwtAuthFilter.shouldNotFilter()`.
- `@EnableMethodSecurity(prePostEnabled = true)` is enabled but `@PreAuthorize` is not actively used — role enforcement is route-based in `SecurityConfig`.
- CORS: allowed origins include localhost, `*.netlify.app`, `*.vercel.app`, `*.railway.app`, `*.web.app`, `*.firebaseapp.com`, `*.run.app`, plus `frontendUrl` and `publicBaseUrl` from config. Allowed headers: `Authorization`, `Content-Type`, `Accept`, `Origin`, `X-Requested-With`. Credentials allowed. Preflight cached 1 hour.
- Rate limit sensitive endpoints (auth, OTP) at 10 req/min per IP. General API at 200 req/min per IP + per user.

### AI Module (`ai/`)

- All AI analysis logic uses Kotlin `object` singletons — they are stateless and thread-safe.
- SSE streaming for AI responses uses Kotlin `Flow<String>` (coroutines) in `OpenAiResponseService` and `runBlocking` in `DoctorAiController` to bridge to servlet responses.
- `AiStreamException` for structured error handling during streaming (codes: `SAFETY_BLOCK`, `RATE_LIMIT`, `VALIDATION`, `PROVIDER_ERROR`).
- AI service uses `SimpleRateLimiter` to cap OpenAI requests.
- `PatientAiContextBuilder` (`@Component`) builds context from patient data for AI prompts.

### Database & Migrations

- **Always** create a new Flyway migration for schema changes — never modify existing migrations.
- Migration naming: `V{next_number}__{description}.sql` (double underscore, snake_case description). Current latest: V56.
- **Never** use `hibernate.ddl-auto: create` or `create-drop` — Flyway is the single source of truth.
- Include `IF NOT EXISTS` / `IF EXISTS` guards in migrations for idempotency where appropriate.
- Seed data goes in dedicated seed migrations (e.g., `V3__seed.sql`, `V19__seed_doctor_reviews.sql`, `V53__seed_icd10_codes.sql`).
- Test migrations locally with `docker-compose up -d && ./gradlew bootRun` before pushing.

### Testing

- Use `@WebMvcTest(controllers = [XController::class])` for controller unit tests with `MockMvc`.
- Use `@MockBean` for service/repository dependencies in controller tests.
- Use `@AutoConfigureMockMvc(addFilters = false)` to bypass security filters in unit tests.
- Test framework: JUnit 5 + Mockito + Hamcrest matchers.
- Test files go in `src/test/kotlin/com/shifa/` mirroring the main source structure.

### Performance

- Use `@Transactional(readOnly = true)` on read-only service methods — enables Hibernate flush-mode optimizations.
- Use `@Cacheable("icd10Search")` for expensive, cacheable queries (see `CacheConfig.kt`).
- Avoid `open-in-view` in production (`spring.jpa.open-in-view: false` in prod profile).
- Use `LEFT JOIN FETCH` to batch-load related entities instead of triggering N+1 lazy loads.
- Prefer `Page<Entity>` with limits over unbounded `findAll()` on large tables.
- Async heavy work via `@Async("scribeTaskExecutor")` — never block request threads with AI/transcription calls.
- Use `@Scheduled` for background tasks: `cron` expressions for daily jobs (e.g., AI draft cleanup at 2 AM), `fixedRate` for polling (e.g., reminders every 60s, OTP cleanup every hour).

### API Design

- All API routes under `/api/` prefix.
- RESTful URL patterns: `/api/{resource}`, `/api/{resource}/{id}`, `/api/{resource}/{id}/{action}`.
- Use HTTP methods correctly: `GET` (read), `POST` (create), `PUT`/`PATCH` (update), `DELETE` (remove).
- Return appropriate status codes: 200 (OK), 201 (created), 204 (no content), 400 (validation), 401 (unauthenticated), 403 (forbidden), 404 (not found), 409 (conflict), 429 (rate limited).
- File upload limit: 25MB (`spring.servlet.multipart.max-file-size`).
- Paginated responses use Spring's `Pageable` — let the client pass `?page=0&size=20&sort=createdAt,desc`.
