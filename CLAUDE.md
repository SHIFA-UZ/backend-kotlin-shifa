# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Shifa Doctor Backend MVP — a medical platform backend connecting doctors and patients. Built with Kotlin + Spring Boot, deployed on Railway.

## Tech Stack

- **Language:** Kotlin 2.0, Java 21
- **Framework:** Spring Boot 3.3.4
- **Database:** PostgreSQL 16 with Spring Data JPA (Hibernate)
- **Migrations:** Flyway (55+ migrations in `src/main/resources/db/migration/`)
- **Auth:** JWT (JJWT 0.12.6) with session binding, Firebase phone OTP for patients
- **AI:** OpenAI GPT-4o-mini via OkHttp, Apache PDFBox for PDF extraction
- **Video:** Daily.co integration
- **Build:** Gradle (Kotlin DSL)

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
java -Xmx350m -Xms256m -jar build/libs/*.jar
```

## Architecture

**Layered structure under `src/main/kotlin/com/shifa/`:**

- **`web/`** — REST controllers (42) + DTOs. All routes under `/api/`. Role-based access via `@PreAuthorize`.
- **`service/`** — Business logic (40+ services). Transaction management via `@Transactional`.
- **`repo/`** — Spring Data JPA repositories (36). Custom queries via `@Query`.
- **`domain/`** — JPA entities (35). Core tables: `users`, `doctor_profiles`, `patient_profiles`, `appointments`, `consultation_notes`, `messages`.
- **`security/`** — JWT auth filter chain, rate limiting (Bucket4j), security headers. Filter order: SecurityHeaders → RateLimit → UserRateLimit → JwtAuth.
- **`ai/`** — Medical AI module: `SymptomMatcher`, `RedFlagEngine`, `SeverityScorer`, `MedicalPromptBuilder`, `StructuredNoteParser`.
- **`config/`** — Spring configs: async executor, Caffeine cache, static resource serving, app properties.

**Auth model:** Three roles — `DOCTOR`, `PATIENT`, `ADMIN`. Users can hold multiple roles. JWT tokens include `jti` for session revocation. Doctor tokens expire in 12h, patient tokens in 365 days.

**Route authorization:**
- `/api/patients/me/**` → PATIENT only
- `/api/doctors/me/**`, `/api/schedule/**`, `/api/calendar/**` → DOCTOR only
- `/api/admin/**` → ADMIN only
- `/api/public/**`, `/api/webhooks/**` → no auth

## Entry Point

`Application.kt` parses Railway's `DATABASE_URL` env var into JDBC format and handles `FIREBASE_SERVICE_ACCOUNT_JSON` for Firebase init. Falls back to individual `PG*` env vars.

## Database

Local dev uses Docker PostgreSQL on port **5433** (not default 5432). Credentials: `shifa/shifa/shifa`. Flyway runs migrations automatically on startup. Dev uses `hibernate.ddl-auto: update`; prod uses `validate`.

## Static File Serving

Uploads served from `storageRoot` (default `./public-storage/images`) at paths: `/doctors/**`, `/patients/**`, `/patientdocuments/**`, `/certificates/**`, `/chat-attachments/**`.

## Key Environment Variables

`DATABASE_URL`, `JWT_SECRET`, `OPENAI_API_KEY`, `DAILY_API_KEY`, `DAILY_WEBHOOK_SECRET`, `FIREBASE_SERVICE_ACCOUNT_JSON`, `STORAGE_ROOT`, `APP_PUBLICBASEURL`, `APP_FRONTENDURL`. See `README_ENVIRONMENT.md` for full details.

## Background Processing

- `@Async` scribe pipeline (voice-to-notes): ThreadPool core=2, max=4, queue=50
- Scheduled tasks: appointment reminders, AI draft cleanup, calendar resets

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
