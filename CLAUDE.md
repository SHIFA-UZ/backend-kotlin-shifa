# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Shifa Doctor Backend MVP — a medical platform backend connecting doctors and patients. Built with Kotlin + Spring Boot, deployed on Railway.

## Tech Stack

- **Language:** Kotlin 2.2.21, Java 21
- **Framework:** Spring Boot 3.5.16 with Spring Security
- **Database:** PostgreSQL (16 locally, 17 on Railway) with `pgvector` and `pg_trgm`, Spring Data JPA (Hibernate 6.6), HikariCP
- **Migrations:** Flyway 11.18.0 (121 migrations in `src/main/resources/db/migration/`, latest V121)
- **Auth:** JWT (JJWT 0.12.6) with session binding (JTI), Firebase phone OTP for patients, BCrypt passwords
- **AI:** OpenAI, Gemini and Grok behind `LlmProviderRegistry` (OkHttp), OpenAI embeddings for RAG, Whisper and Gemini speech, Apache PDFBox 3.0.3
- **Video:** Daily.co integration with webhook recording
- **Caching:** Caffeine 3.1.8 (`icd10Search` 5 min; `professions`, `publicClinics`, `publicDoctor` 60 s; session validity 30 s; rate-limit buckets)
- **Rate Limiting:** Bucket4j 8.16.1 (10 req/min auth, 200 req/min general) — IP-based + per-user, plus per-user AI limits
- **Job locking:** ShedLock on every `@Scheduled` job that changes data
- **Quality:** detekt (baseline), JaCoCo, ArchUnit (`ArchitectureTest`), OpenAPI snapshot test
- **Email:** Brevo SMTP (OTP delivery)
- **API Docs:** springdoc-openapi 2.8.17 (Swagger UI at `/swagger-ui/`, off in production)
- **Build:** Gradle 8.14.3 (Kotlin DSL), output: `build/libs/app.jar`; Dockerfile for Railway

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

# Run the built JAR (Railway start command uses -Xmx1536m; the Dockerfile default is -Xmx1024m)
java -jar build/libs/app.jar
```

## Architecture

**Modular monolith under `src/main/kotlin/com/shifa/`:** one package per feature, each with the layers `api/` (controllers and DTOs), `application/` (use-case services, transactions), `domain/` (entities, enums, pure rules) and `infrastructure/` (Spring Data repositories, external gateways). Dependencies point inward (`api` → `application` → `domain`; `infrastructure` implements what `application`/`domain` need). A feature uses another feature only through its `application` services. `src/test/kotlin/com/shifa/ArchitectureTest.kt` enforces these rules with ArchUnit; today's violations are frozen in `src/test/resources/archunit-store` and any new one fails the build. Two helpers exist so nobody writes a copy: `patients.application.CurrentPatientResolver` (the signed-in patient's profile) and `shared.PublicUrls` (absolute URLs under `app.publicBaseUrl`). The move into this layout was done 2026-10-29 (roadmap Phase 5 step 1, class-for-class; only package, import and fully-qualified references changed); the per-feature border seal (DTOs in `api/dto`, controllers and other features injecting the feature's `application` "doors" instead of its repositories) is done for every feature (roadmap "Status 2026-10-29"). A door is a `@Service` in `<feature>.application` whose methods have the repository's names; when you need a repository of another feature, use its door, and when you need a new query, add it to the door (or to a use-case service) rather than injecting the repository. The frozen ArchUnit lists only shrink: a refreeze (`freeze.refreeze=true` in `src/test/resources/archunit.properties`, run `ArchitectureTest`, set it back) is for moved violations only, and the store diff must show no class gaining entries. Public methods of an `application` service that reach repositories or gateways are `@Transactional` (`readOnly = true` for reads: Postgres then rejects any write reached from them, so a read that may write on first use stays read-write); pure helpers are `internal` instead of transactional. ArchUnit enforces this too (roadmap P3); the doors and the gateway-calling methods are the frozen exceptions. A list endpoint that can grow with a user's history gets a `/page` sibling built with `shared.api.Pages.of(list, pageable)` (same filters and order, Spring `Page` shape); the list endpoint itself never changes shape (roadmap P6).

| Package | Files | Purpose |
|---------|-------|---------|
| `admin/` | 20 | Admin panel API, admin profiles, audit log, system config. |
| `ai/` | 114 | All AI: doctor AI, patient co-pilot, briefings, scribe, transcription, RAG, ICD-10, LLM routing and clients, intelligence memory, speech, `clinicalengine/` (its own four layers). |
| `appointments/` | 74 | Booking, calendar, schedule rules and blocks, visits, consultation notes, video (Daily), the morning-assistant appointment intelligence. |
| `auth/` | 39 | Login, OTP (SMS and e-mail), sessions, users and roles, account deletion. |
| `chat/` | 12 | Conversations, messages, attachments. |
| `clinics/` | 65 | Clinics, memberships, invitations, finance ledger, treatment plans, installments. |
| `devseed/` | 1 | Deterministic development data (`app.dev-seed`). |
| `doctors/` | 59 | Doctor profiles, services and prices, locations, reviews, analytics, professions, public listing. |
| `documents/` | 15 | Patient documents, access grants and requests, document storage service. |
| `notifications/` | 20 | Notifications, FCM push, SMS provider, reminders. |
| `patients/` | 34 | Patient profiles, forms, prophylaxis, care partnerships, doctor-patient access. |
| `payments/` | 40 | Checkout, gateways (Click, Stripe, manual), webhooks, subscriptions, doctor billing. |
| `shared/` | 54 | Cross-cutting: `security/` (JWT chain, rate limits, principals), `config/`, `api/` (error handler, request id, file serving), `storage/`, `util/`, error codes, upload policy. |
| `tasks/` | 8 | Remote-care tasks and check-ins. |

**Total: 556 Kotlin source files, 88 test files (2026-10-30).** Regenerate with `find src/main/kotlin -name '*.kt' | wc -l`.

### Auth Model

Four roles — `DOCTOR`, `PATIENT`, `ADMIN`, `CLINIC_STAFF`. Users can hold multiple roles. `CLINIC_STAFF` shares the doctor-app routes for the roster, booking, calendar, messages, clinics and treatment plans. JWT tokens include `jti` for session revocation via the `UserSession` table (validity cached 30 s). Doctor, staff and admin tokens expire after 30 days (`app.jwt.accessTokenMinutesDoctor`), patient tokens after 365 days. Admin login adds an e-mailed second factor.

### Security Filter Chain Order

1. `SecurityHeadersFilter` — HTTPS enforcement, CSP, X-Frame-Options
2. `RateLimitFilter` — IP-based rate limiting via Bucket4j
3. `JwtAuthFilter` — JWT validation, session binding check, principal resolution
4. `UserRateLimitFilter` — per-user rate limiting
5. `AiRateLimitFilter` — per-user daily and burst limits for `/api/ai/**`

`SignedUrlFilter` checks the signature of file URLs (`?exp=&sig=`) on the static-file paths; it only logs until `app.file-url-enforce` is true.

### Route Authorization

- `/api/patients/me/**`, `/api/tasks/my-tasks/**`, `/api/payments/**` → PATIENT
- `/api/doctors/me/**`, `/api/consultations/**`, `/api/doctor/**`, `/api/clinical-engine/**`, `/api/tasks/**` (except `my-tasks`), `/api/ai/memories/**` → DOCTOR only
- `/api/patients/**`, `/api/appointments/**`, `/api/calendar/**`, `/api/messages/**`, `/api/clinics/**`, `/api/treatment-plans/**` → DOCTOR or CLINIC_STAFF; `/api/schedule/**` reads, booking and blocks for both, other schedule mutations DOCTOR only
- Fallback: `anyRequest().authenticated()` (any signed-in user), so every new route gets an explicit rule
- `/api/notifications/**`, `/api/ai/**`, `/api/video/**` → any authenticated user
- `/api/admin/**` → ADMIN only
- `/api/public/**` (except `POST .../reviews`, authenticated), `/api/webhooks/**`, the public `/api/auth/*` endpoints → no auth
- `/actuator/health/**`, `GET /actuator/info`, `/swagger-ui/**`, `/v3/api-docs/**` → no auth
- Static files: `/doctors/**`, `/patients/**`, `/patientdocuments/**`, `/certificates/**`, `/chat-attachments/**` → no auth, signed URLs (`/photos/**` was removed)

## Entry Point

`Application.kt` is `runApplication` plus the annotations. All configuration is in `application.yml` (same everywhere),
`application-prod.yml` (Railway, both environments) and `application-dev.yml` (the development overlay, profile `prod,dev`).
Only secrets and Railway-injected values (`PORT`, `SPRING_DATASOURCE_*` reference variables, `RAILWAY_GIT_COMMIT_SHA`)
are environment variables; `src/test/kotlin/com/shifa/shared/config/ConfigPolicyTest.kt` fails on any other placeholder and pins
the production and development values. Local secrets go in the git-ignored `application-local.yml`
(see `application-local.example.yml`). `FIREBASE_SERVICE_ACCOUNT_JSON` is read by the Firebase services directly.

Annotations: `@EnableConfigurationProperties`, `@EnableScheduling`, `@EnableCaching`.

## Database

Local dev uses Docker PostgreSQL on port **5433** (not default 5432). Credentials: `shifa/shifa/shifa`. Flyway runs migrations automatically on startup. Dev uses `hibernate.ddl-auto: update`; prod uses `validate`.

Production HikariCP: `maximum-pool-size: 15`, `minimum-idle: 2`; Tomcat `threads.max: 100` (`application-prod.yml`).

## Deployment

- **Primary:** Railway, built from the `Dockerfile`; start command, health check and restart policy are Railway service settings (`railway.toml` and friends were removed)
- **Profiles:** none = `local` (reads `application-local.yml`), `prod` (production), `prod,dev` (development)
- **Health check:** `/actuator/health/liveness` (180s timeout for cold start + migrations)
- **JVM:** `-Xmx1536m -Xms512m -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:+UseStringDeduplication -XX:MaxMetaspaceSize=384m` (Railway start command); the image default is `-Xmx1024m -Xms384m`
- **Restart:** on_failure, max 10 retries

## Static File Serving

Uploads are written and read through `shared/storage/FileStorage` (local directory `app.storageRoot`, or the S3-compatible Railway bucket when `app.storage.s3.enabled`; the bucket falls back to the directory for reads while a volume is being copied). `shared/api/StoredFileController` serves them with a 1-hour cache at `/doctors/**`, `/patients/**`, `/patientdocuments/**`, `/certificates/**`, `/chat-attachments/**`. Admins copy a volume into the bucket with `POST /api/admin/storage/migrate`.

## Key Environment Variables

Secrets only: `SPRING_DATASOURCE_URL`/`USERNAME`/`PASSWORD` (Railway references), `JWT_SECRET` (64+ chars), `OPENAI_API_KEY`, `GEMINI_API_KEY`, `DAILY_API_KEY`, `DAILY_WEBHOOK_SECRET`, `FIREBASE_SERVICE_ACCOUNT_JSON`, `MAIL_PASSWORD`, `STRIPE_SECRET_KEY`, `STRIPE_WEBHOOK_SECRET`, `CLICK_SECRET_KEY`, `DEVSMS_API_TOKEN`, `GOOGLE_MAPS_API_KEY`, plus `SPRING_PROFILES_ACTIVE`. URLs, model names, limits, merchant ids and the mail sender are literals in the yml files.

## Background Processing

- `@Async` scribe pipeline (voice-to-notes): ThreadPool `scribeTaskExecutor` core=2, max=4, queue=50
- Scheduled tasks, under ShedLock except the log-only metrics line: reminders (`ReminderNotificationScheduler`, 60 s), payment hold expiry (`PaymentHoldExpiryScheduler`, 60 s), AI draft cleanup (`AiDraftNoteCleanupScheduler`, 02:00), OTP cleanup (`SmsOtpService`, `EmailOtpService`, hourly), session cleanup (`UserSessionCleanupScheduler`, hourly), runtime metrics line (`RuntimeMetricsLogger`, hourly)
- `@Async` executors for briefings, visit summaries and intelligence memory; SSE streams on a bounded executor

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
- CORS: allowed origins include localhost, `*.netlify.app`, `*.vercel.app`, `*.railway.app`, `*.web.app`, `*.firebaseapp.com`, plus `frontendUrl` and `publicBaseUrl` from config. Allowed headers: `Authorization`, `Content-Type`, `Accept`, `Origin`, `X-Requested-With`. Credentials allowed. Preflight cached 1 hour.
- Rate limit sensitive endpoints (auth, OTP) at 10 req/min per IP. General API at 200 req/min per IP + per user.

### AI Module (`ai/`)

- All AI analysis logic uses Kotlin `object` singletons — they are stateless and thread-safe.
- SSE streaming for AI responses uses Kotlin `Flow<String>` (coroutines) in `OpenAiResponseService` and `runBlocking` in `DoctorAiController` to bridge to servlet responses.
- `AiStreamException` for structured error handling during streaming (codes: `SAFETY_BLOCK`, `RATE_LIMIT`, `VALIDATION`, `PROVIDER_ERROR`).
- AI service uses `SimpleRateLimiter` to cap OpenAI requests.
- `PatientAiContextBuilder` (`@Component`) builds context from patient data for AI prompts.

### Database & Migrations

- **Always** create a new Flyway migration for schema changes — never modify existing migrations.
- Migration naming: `V{next_number}__{description}.sql` (double underscore, snake_case description). Check `ls src/main/resources/db/migration | sort -V | tail -1` (V121 at the time of writing).
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
