# Backend re-architecture and delivery roadmap

Written 2026-10-25 against `main` @ `3201e80`. This is the plan for turning `backend-kotlin-shifa` into a faster, safer, cleaner service **without interrupting the production users on Railway**. It is organised in chunks (phases). Every chunk is its own branch and pull request, is deployable on its own, and leaves production working if we stop after it.

Companion documents:

- `../CLAUDE.md` in the workspace root: the target architecture rules (modular monolith, dependency rules, speed and safety rules). This roadmap does not repeat them; it says in which order to get there.
- `../../apps-flutter-patient/docs/backend-improvements.md`: the patient app's asks. Section 8 maps every item to a phase.

---

## 0. Ground rules for the whole transition

1. **Production keeps its database.** The existing Railway Postgres stays the production database. Nothing in this plan copies, moves or recreates it. The development environment gets its **own** Postgres (section 3). Dev and prod never share a database.
2. **Schema changes are expand-then-contract.** Railway starts the new build next to the old one and switches traffic when the health check passes. For a short time old code runs against the new schema. So every migration in a release must be additive (new table, new nullable column, new index `CONCURRENTLY`). Dropping or renaming happens at least one release later, after the old code is gone.
3. **API changes are additive.** New fields are optional, old fields stay until the user confirms no store build still reads them (workspace `CLAUDE.md`, "Changing the API contract").
4. **One chunk, one branch, one PR.** Refactoring and feature work never share a branch. Pure moves (packages, files) are separate PRs from behaviour changes so the diff is reviewable.
5. **Nothing reaches production without passing through development first.** Since 2026-10-28 `main` deploys only development; production moves only by an explicit release (fast-forward of the `production` branch) after the user has tested on `https://api-dev.shifa.uz`.
6. **Every chunk has a rollback.** For code: Railway "Redeploy" of the previous deployment (one click). For schema: additive migrations never need a rollback; the old code ignores the new columns.

### 0.1 Compatibility contract

Store builds of the patient app and installed builds of the doctor app keep working unchanged through every phase. Concretely, for every endpoint that exists today:

| Never changes | Allowed |
|---|---|
| Path, HTTP method, auth requirement (except the security fixes in Phase 0, each listed with its verified impact) | New endpoints, new optional query parameters |
| Field names, types and nullability of existing response fields; enum values the apps already receive | New fields; new enum values only where the app side is confirmed to tolerate unknown values (the KMP app does for `type`; appointment `status` and `paymentStatus` do **not** get new values without an app release) |
| Response **shape**: a `List` stays a `List`, a Spring `Page` stays a `Page` with the same top-level keys (`content`, `totalElements`, `number`, `size`, `last`, …) | A paginated variant of a list endpoint is a new endpoint or opt-in by a new query parameter; the default response is unchanged |
| Error JSON keys `error` (int), `message`, `status`; the **wording** of `message` strings, because the KMP app matches known messages in `data/api/ErrorMessages.kt` | New keys `code`, `requestId`, `fields`; message wording changes only after the app matches on `code` |
| URL **hosts** the apps hardcode (`shifa-doc-backend-mvp-production.up.railway.app`) and URL **paths** for files (`/patientdocuments/…`, `/patients/…`, `/doctors/…`, `/chat-attachments/…`) | Query parameters on file URLs (signatures); the backend may proxy or redirect those paths to object storage |
| Default page sizes and the sizes the apps send (`for-assignment` sends 500 and loops; the patient app sends `pageSize=50` for doctors) | Caps above what any app sends |
| `JWT_SECRET`. Rotating it logs out every user; patients hold 365-day tokens. Sessions are revoked through `user_sessions`, never by changing the secret | Keys for new signatures (file URLs) are derived from it, never added as new secrets (3.6) |
| JSON serialisation defaults (date format, null handling, enum names, `Page` JSON) | Pinned explicitly before any framework upgrade |

**How it is enforced, not just promised:**

- **OpenAPI snapshot test** (Phase 1.4): `docs/api/openapi.json` is committed; CI fails when the live schema differs. A field removal, type change or path change cannot slip into a PR unnoticed.
- **Golden response tests** (Phase 1.4): for the endpoints the apps use most (login, doctor list and profile, appointments list and detail, documents list, notifications, conversations, payments methods, `for-assignment`), the context test calls them against the dev seed and compares the JSON to committed files, ignoring volatile fields. This is what catches a serialisation change from a framework upgrade, which the OpenAPI snapshot cannot.
- **Soak on development** before every production release; the apps' debug builds point at development.
- **Per-PR checklist** in the PR template: endpoints touched; fields added/removed; error messages touched; migration additive (yes/no); which app builds were tried against development.

**Supported app builds (Q9, confirmed 2026-10-27):** patient app KMP **1.2.3** from the stores (older Flutter builds may still be installed; nothing in this plan breaks them either); doctor app is **web only**, serving `1.0.0+3` at `doctor.shifa.uz`, which is the head of its `main`. Mobile store builds of the doctor app are not distributed.

### 0.2 Removals audit: the complete list of what this plan removes

Everything not on this list stays. CI enforces it through the OpenAPI snapshot and the schema baseline (13.6).

| Kind | Removed | Evidence it is safe |
|---|---|---|
| API endpoints | `POST /api/test/create-test-patient`, `POST /api/test/unlock-test-patient` (PR 0.3) | No app build calls them (grep of KMP, retired Flutter and doctor app repos). They create a known-password account. |
| Public URL path | the static alias `/photos/**` (PR 0.2) | Zero stored URLs contain `/photos/` in any table (query Q-B, prod, 2026-10-27); no app builds such URLs; it exposed the whole storage root. |
| API endpoints, fields, enum values, status codes, error keys | **none** | Deprecated-but-kept: `PUT /patients/me/fcm-token`, the `error` integer key, `creatorLabel`, `reason`. |
| Database tables | **none** in any phase | |
| Database columns | **none** in Phases 0–6. One future candidate: `patient_profiles.fcm_token` after the token table has run for two releases, only through an explicit schema-baseline change you approve. | |
| Database rows deleted | **none** | Retention (Q11) is a separate decision; default is never. |
| Database rows changed by migration | 13 `payments` rows: status `PENDING` → `CANCELLED`, `failure_reason` set (PR 0.10) | They are duplicate open checkouts on 5 appointments; the newest per appointment is kept; counts logged before and after. |
| Database rows merged | duplicate patient profiles, Phase 5 only, after a count query and your approval | |
| Uploaded files | **none deleted**. Phase 2.7 copies to object storage with checksum verification and keeps the volume mounted until a week of zero local reads. | |
| Repository files (not user data) | log archives, one-off SQL scripts, `uat_dump.sql`, graphify cache, `images/`, GCP files, `railway.toml` and friends after their settings moved (1.13), `application-qa.yml` | None is read at runtime by production. |
| Railway | the empty projects `shifa-backend-qa` and `shifa-backend-sheroziy` (0 services; the QA project holds two detached volumes from February 2026 with no instances). The production environment is **renamed** `staging` → `production`, nothing else changes. | |

---

## 1. Where the backend stands today

Facts from the audit of `main` @ `3201e80`. Line references point at that commit.

### 1.1 Size and shape

| Metric | Value |
|---|---|
| Kotlin files in `src/main` | 440 (about 60 000 lines) |
| Controllers (`@RestController`) | 64 |
| Services (`@Service`) | 99 |
| Repositories | 71 files |
| JPA entities | 66 |
| Flyway migrations | 117 (latest V117) |
| Tests | 64 files, 335 tests. 1 fails today, 4 are skipped (they need a local Postgres) |
| CI | **None.** There is no `.github/workflows/` in this repo. |
| Dependency updates | None (no Dependabot or Renovate) |

The code is layered by technical type (`web/` → `service/` → `repo/` → `domain/`), with three feature-shaped islands (`payment/`, `clinicalengine/`, `intelligence/appointment/`).

### 1.2 Delivery today

- Railway builds from the GitHub repo with Nixpacks (`railway.toml`), runs `./gradlew clean build -x test` (tests are skipped on every deploy), and starts the jar with the flags in `railway.toml`. The same JVM flags are repeated in `railway.json`, `nixpacks.toml`, `Procfile` (different values) and `Dockerfile` (different values again).
- **Railway layout as read through the CLI on 2026-10-27** (workspace "bek94's Projects"): project `shifa-backend` has the environment `production` (named `staging` until 2026-10-27) and, since 2026-10-28, `development` (own Postgres, own volumes, own variables, deploys from `main`, served at `shifa-doc-backend-mvp-development.up.railway.app` and `api-dev.shifa.uz`, both verified 2026-10-28) (the service `shifa-doc-backend-mvp` behind `shifa-doc-backend-mvp-production.up.railway.app`, plus `Postgres`). Deploy trigger: branch `main`, Wait for CI off. Builder is Railpack with `railway.toml` applied (build command, start command, health check `/actuator/health/liveness` at 180 s, restart policy). A 2 GB volume is mounted at `/app/storage` (268 MB used); the Postgres volume is 1 GB (202 MB used). Both volumes have **daily backups with 6-day retention**. 48 variables on the service; `JWT_SECRET` is 40 characters; `GEMINI_API_KEY` is set, `XAI_API_KEY` is not; stray unused variables `PUBLIC_BASE_URL`, `SPRING_VARIABLES_ACTIVE`, `DB_USERNAME`, `DB_PASSWORD`. Two other projects, `shifa-backend-qa` and `shifa-backend-sheroziy`, contain no services.
- **Production gate live since 2026-10-28:** production's deploy trigger is the `production` branch (created at the live commit `3201e80`), development's is `main`. Merging to `main` deploys development only. A production release is a fast-forward push of `production` to a tested commit, done on the user's "release"; until the GitHub Actions workflow (1.8) exists this push is the release button. Rollback is Railway "Redeploy" of the previous deployment. Wait for CI stays off until CI exists (1.2).
- **Hard date:** Railway has deprecated config-as-code (`railway.toml` / `railway.json`) in favour of infrastructure-as-code (`.railway/railway.ts`). Existing files stop being read on **2026-12-01**. After that date the health check, start command and restart policy in `railway.toml` silently stop applying unless they have been moved to the dashboard or to IaC first. This moves into Phase 1 (PR 1.13).
- Configuration is split between the repo and the Railway dashboard: the yml files expose about 90 `${VAR}` placeholders (only 13 are secrets) and `Application.kt` reads 17 more variables by hand. The effective production configuration cannot be read from the repo alone. Section 3.6 narrows this to the secrets.
- Both apps point at `https://shifa-doc-backend-mvp-production.up.railway.app` (patient app `ApiConfig.BASE_URL`; doctor app `--dart-define=API_BASE_URL` in its workflows).
- Flyway runs at startup; the health check is `/actuator/health/liveness` with a 180 s timeout.
- Logs go to a file inside the container (`logging.file.name`) with 10 rolled archives. Railway only shows stdout, so the file is wasted memory and disk.
- Uploaded files (patient documents, photos, chat attachments, scribe audio) live on the local disk under `STORAGE_ROOT`. Whether a Railway volume is mounted there is not visible in the repo (Q2). Without a volume, every deploy loses all uploads.

### 1.3 Repository hygiene problems

- `.gitignore` ignores every `*.md` except `CLAUDE.md` and `README.md`, so `docs/` files must be force-added. It also ignores `application-dev.yml`, which blocks the `dev` profile this plan introduces. (Fixed on this branch.)
- Tracked in git and must be removed: 8 rolled log archives (`shifa-backend.log.*`, likely containing PII), 11 one-off SQL scripts in the root, an empty `uat_dump.sql`, 440 generated JSON files under `src/main/kotlin/graphify-out/cache/`, and two sample photos under `images/`.
- `MIGRATION_GCP*.md` and `cloudbuild.yaml` described a Cloud Run alternative, with the Cloud SQL socket factory in `build.gradle.kts`. Removed 2026-10-29 (Q3): the owner deleted the Cloud Build trigger in Google Cloud (the Cloud Run service and any Cloud SQL or Artifact Registry resources there still need deleting to stop their cost); the runbooks, the dependency, the `CLOUD_SQL_INSTANCE` branch and the `*.run.app` CORS origin are gone from the repo.

### 1.4 Security findings (ordered by severity)

These are confirmed in code. They go first, in Phase 0.

| # | Finding | Where |
|---|---|---|
| S1 | **Account takeover.** `POST /api/auth/create-patient-for-doctor` is public and skipped by the JWT filter. With a body of only `{"phone": "<any doctor's phone>"}` no code is checked, and it returns a PATIENT JWT for that doctor's user account. The patient app always sends `email + emailOtp`, so requiring a verified OTP on every path breaks nothing. | `web/AuthController.kt:419-485`, `security/SecurityConfig.kt`, `security/JwtAuthFilter.kt:27-50` |
| S2 | **Patient documents downloadable without login.** `/patientdocuments/**` is `permitAll` and the controller does no auth. File names are `<original-name>-<epoch-millis>.<ext>` under a sequential patient id, so they are guessable. `/photos/**` additionally maps the **whole** storage root, which exposes `patientdocuments/`, `scribe-temp/` (consultation audio) and `transcription-feedback/`. | `security/SecurityConfig.kt:95`, `web/PatientDocumentFileController.kt:27-85`, `config/StaticResourceConfig.kt:50-53`, `service/PatientDocumentStorageService.kt:26` |
| S3 | **Global patient directory for any doctor or staff.** `GET /api/messages/search?q=` searches every patient profile by name, email or phone with no roster scope and no limit. `GET /api/patients/for-assignment` returns a global patient list with a default page size of 500. The doctor app uses both on purpose (chat contact search, calendar "assign patient" which loops all pages), so narrowing them is a product decision (Q10), not a hotfix. | `service/MessageService.kt:288-327`, `repo/PatientProfileRepository.kt:81-87`, `web/PatientsController.kt:254`, doctor app `lib/state/chat/chat_actions.dart:97`, `lib/state/patients/patient_actions.dart:541-550` |
| S4 | **Test endpoint open in production.** `POST /api/test/create-test-patient` creates or unlocks `patient@test.com` / `+998901234567` with password `patient123` and returns the password. No `@Profile` gate exists anywhere in the codebase. | `web/TestDataController.kt`, `security/SecurityConfig.kt:78` |
| S5 | **Committed Daily.co API key** as a config fallback. | `application.yml:203` |
| S6 | **Click callback accepts a blank secret.** `click.secret-key` defaults to `""` while `click.enabled` defaults to `true`. The callback service never rejects a blank secret, and the signature comparison is not constant-time. (Stripe and Daily webhooks do reject a blank secret.) | `application.yml:232-237`, `payment/service/ClickShopCallbackService.kt:52-59,158-166` |
| S7 | **Rate limits keyed on the first `X-Forwarded-For` value**, which the client controls. OTP and login limits can be bypassed. | `security/RateLimitFilter.kt:35-40` |
| S8 | **Chat attachments** keep the raw extension from the upload name with no allow-list, and are served publicly from the API origin (stored `.html`/`.svg` = XSS). Profile photo uploads do the same. | `web/ChatAttachmentController.kt:32-41`, `web/ProfilePhotoController.kt:50-58`, `web/PatientPhotoController.kt:58` |
| S9 | **PII in logs:** phone and email at INFO on every video token lookup, DB username and a password prefix printed at startup, OTP codes logged when SMTP is unset, LLM response bodies logged. | `web/VideoController.kt:30-63`, `Application.kt:117-195`, `service/EmailSenderService.kt:30,105-112`, `ai/llm/OpenAiCompatibleLlmClient.kt:175` |
| S10 | Swagger UI and `/v3/api-docs` are public in production. `POST /api/public/doctors/{id}/reviews` is `permitAll` but needs a principal, so anonymous calls 500 instead of 401. 70 of 119 `@RequestBody` parameters have no `@Valid`. | `security/SecurityConfig.kt`, `web/DoctorReviewController.kt:73` |

### 1.5 Correctness and performance findings

| # | Finding | Where |
|---|---|---|
| P1 | **Checkout is not idempotent.** Every call to create a consultation checkout inserts a new `payments` row unless the appointment is already PAID. `findByAppointment_Id` returns a single row, so after a double click the cancel, hold-expiry and already-paid paths throw and return 500. | `payment/service/PaymentService.kt:112-184`, `payment/repo/PaymentRepository.kt:13`, `V58__payment_foundation.sql:39` |
| P2 | **Scheduled jobs are not locked.** Six `@Scheduled` jobs, no ShedLock or advisory lock. Two instances overlap during every Railway deploy; the reminder job uses check-then-insert dedupe, so SMS (billed) and pushes can be sent twice. The reminder steps also make SMS and FCM HTTP calls **inside** the database transaction. | `service/ReminderNotificationScheduler.kt:18`, `service/ReminderNotificationService.kt:112-419` |
| P3 | **Booking without a transaction.** `PatientCopilotBookingService.bookNearestToPreferred` has no `@Transactional`, so its `findByIdForUpdate` lock is a no-op. No DB constraint prevents a double booking. 96 write paths lack `@Transactional`, 27 of them with two or more writes (e.g. `AuthController.register` with 9 writes). | `service/PatientCopilotBookingService.kt:51`, `web/AuthController.kt:548,735,1153` |
| P4 | **Implicit EAGER loading.** 16 bare `@ManyToOne`/`@OneToOne` relations default to EAGER, including `Appointment.doctor` and `Appointment.patient` (the hottest table), `Notification`, `RemoteCareTask`, `DoctorReview`. No `hibernate.default_batch_fetch_size` is set. | `domain/Appointment.kt:11,14`, `domain/Notification.kt:13,17`, … |
| P5 | **N+1 and in-memory pagination on hot paths.** Public doctor list loads every enabled doctor, runs 2 queries per doctor, then pages in memory. Conversation list loads **all messages** of every conversation to compute the last message and unread count. Notification list runs a query per row. Mark-read saves one message at a time. Reminders query once per appointment every 60 s. | `web/PublicDoctorController.kt:195-323`, `service/MessageService.kt:31-49,280-283,338-343`, `web/NotificationController.kt:62-100`, `service/ReminderNotificationService.kt:118-127` |
| P6 | **Unbounded lists.** `findAll()` on payments and subscriptions in the admin API (sorted in memory), notifications without paging, all conversations, `findAllByUserEnabled()` for doctors. | `payment/web/AdminPaymentController.kt:28,61`, `web/NotificationController.kt:68-75`, `repo/DoctorProfileRepository.kt:47` |
| P7 | **One raw `Thread` per SSE request** with `runBlocking` and `SseEmitter(0L)` (no timeout). Unbounded threads under load. | `web/PatientCopilotController.kt:150-161`, `web/DoctorAiController.kt:59-74,203-215` |
| P8 | **Per-instance in-memory state** that breaks with 2 instances: session revocation cache (2 min window), rate-limit maps (no eviction, so they also leak), clinical catalog cache invalidation. | `security/UserSessionValidationCache.kt`, `security/RateLimitFilter.kt:32-33`, `clinicalengine/service/ClinicalEngineCatalogCache.kt` |
| P9 | **Lazy loads outside transactions** wrapped in `try/catch` so names silently come back `null`. Default profile has `open-in-view: true`, prod has `false`, so dev hides bugs that only appear in prod. | `web/NotificationController.kt:79-81`, `service/MessageService.kt:391`, `application.yml:22` |
| P10 | **Flaky test.** `DoctorMorningToolDispatchTest` asserts on today's date and fails on any day but the one it was written on. It will block CI on day one. | `src/test/.../DoctorMorningToolDispatchTest.kt` |

### 1.6 Structure findings

- 193 inline `data class` DTOs in controller files (AuthController 26, TreatmentPlanController 23, ClinicFinanceController 19).
- 41 of 64 controllers inject repositories directly (156 repository constructor parameters). `TreatmentPlanController` has 17 repositories and 17 `@Transactional` methods: it is a service wearing a controller annotation.
- 18 files over 600 lines; `AuthController` (1547 lines) holds all registration and token logic.
- Duplicates: 9 copies of `currentPatientProfile` with **inconsistent** logic (6 resolve the patient by phone/email, 3 by user id), 3 haversine functions, 3 `parseJsonList`, 4 slugify helpers, 3 doctor DTO mappers, 24 places that build absolute URLs by hand, 3 rate limiters.
- Zero tests for security filters, authorization rules, repositories, schedulers, `MessageService`, and uploads. No test runs against a real Postgres in CI (no Testcontainers; the 4 Postgres tests skip themselves).
- Documentation drift: `CLAUDE.md` says doctor tokens last 12 h; `application-prod.yml:122` defaults to 30 days. `README.md` lists 37 controllers; there are 64.

### 1.7 Dependencies

| Dependency | Today | Status (Oct 2026) |
|---|---|---|
| Spring Boot | 3.3.4 | Every 3.x line is out of OSS support (3.5 ended 2026-06-30; last 3.5 patch is 3.5.16). Supported: 4.0.x (OSS until 2026-12-31) and 4.1.x (until 2027-07-31). |
| Kotlin | 2.0.21 | 2.4.x is current. Spring Boot 4 needs Kotlin 2.2+. |
| Gradle wrapper | 8.10.2 | 9.x is current (Kotlin 2.4 targets Gradle 9.5). |
| Flyway | 11.18.0 pinned | 12.x/13.x exist; Spring Boot manages a version, so the pin should go once on Boot 4. |
| `jackson-module-kotlin` | declared twice, once as `2.17.+` | A dynamic version makes builds non-reproducible. Remove both lines; Boot manages it. |
| `postgres-socket-factory` (Cloud SQL) | 1.15.2 | Removed 2026-10-29 with the Cloud Run path (Q3). |
| JJWT 0.12.6, Bucket4j 8.16.1, Caffeine 3.1.8, stripe-java 30.1.0, firebase-admin 9.4.0, pdfbox 3.0.3, OkHttp 4.12.0, springdoc 2.6.0 | fine | springdoc 2.x works with Boot 3; Boot 4 needs springdoc 3.x. |

---

## 2. Target architecture (summary)

The target is fixed in the workspace `CLAUDE.md`: a **modular monolith by feature**, each feature with `api/`, `application/`, `domain/`, `infrastructure/`, plus `shared/`. Features talk to each other only through `application` services. No circular dependencies.

Target feature list for this codebase, derived from the current packages and tables:

```
com.shifa/
├── shared/          security (JWT, principals, rate limits), config, errors (codes), i18n, storage, util
├── auth/            login, registration, OTP, sessions, account deletion          ← today: AuthController, *OtpService, UserSession*
├── doctors/         doctor profile, services and prices, settings, schedule rules ← DoctorController, ScheduleController, PublicDoctorController
├── patients/        patient profile, forms (025-2), photos, deleted patients      ← PatientController, PatientsController, PatientMeFormController
├── appointments/    booking, slots, holds, reschedule, cancel, visit lifecycle, reminders, video ← AppointmentController, PatientAppointmentController, VideoController, intelligence/appointment
├── documents/       patient documents, access grants, file storage               ← PatientDocument*, PatientDocumentFileController
├── chat/            conversations, messages, attachments                         ← MessageService, ChatAttachmentController
├── notifications/   notification rows, FCM tokens, push i18n, schedulers         ← NotificationController, FcmService, ReminderNotification*
├── payments/        already feature-shaped; rename web→api, service→application, gateway+repo→infrastructure
├── clinics/         clinics, memberships, staff, finance ledger, treatment plans  ← Clinic*, TreatmentPlan*, ClinicFinance*
├── tasks/           remote care tasks and check-ins                              ← RemoteCareTaskController
├── ai/              LLM routing, scribe, briefing, copilot, RAG, clinical engine ← ai/, scribe/, clinicalengine/, OpenAiResponseService
└── admin/           admin console endpoints                                      ← AdminController, Admin*Service
```

Two mechanical rules make the move safe:

- **A pure move never changes behaviour.** Package moves and file splits are their own PRs with no logic edits. CI (Phase 1) plus the OpenAPI snapshot test (Phase 1) prove the HTTP contract did not change.
- **The rules are enforced by a test, not by review.** Phase 5 adds ArchUnit tests: `api` may not import `infrastructure`, `domain` may not import Spring web or DTOs, feature X may only import feature Y's `application` package. New code that breaks a rule fails CI.

---

## 3. Environments and release pipeline

This is the part the user called the most important: `main` → development Railway automatically; a manual action → production Railway.

### 3.1 Design

```
PR ──CI──► merge to main ──► Railway "development" environment (branch: main, waits for CI)
                                  │  dev DB, dev secrets, dev Firebase/Stripe-test/Click-test
                                  ▼
            GitHub Action "Release to production" (manual, workflow_dispatch)
              checks: SHA is on main · CI green · dev is running that SHA and is healthy
              then: fast-forwards branch `production` to that SHA
                                  │
                                  ▼
                            Railway "production" environment (branch: production, waits for CI)
                                  existing Postgres, existing secrets, real users
```

Why this shape and not the alternatives:

- **One Railway project, two environments** (not two projects). Railway environments are isolated copies of the services with their own variables, domains and databases, and each environment's service can track a different branch. One project keeps a single place for members, billing and the Postgres backups.
- **A `production` branch as the deploy pointer**, moved only by the release workflow, instead of `railway up` from GitHub Actions. It needs no Railway token in GitHub, it keeps Railway building the exact same way for both environments, every production deploy is visible in git history (`git log production`), and the user's rule "changes reach `main` only through a PR" still holds because nobody commits to `production`; the workflow only fast-forwards it to a commit that is already on `main`.
- **Rollback is a Railway "Redeploy"** of the previous production deployment (dashboard, one click). We never force-push `production`. For a code fix, ship a new commit through the same path.
- **"Wait for CI" on both environments.** Railway holds the deployment in `WAITING` until every GitHub Actions workflow on that commit has finished, and skips it if one failed. This is what stops a broken `main` from reaching development.

### 3.2 Actions for you: Railway

Do these in the Railway dashboard, in this order. Nothing here touches the production database.

1. **Current state (2026-10-27):** project `shifa-backend`, environment `production` (renamed from `staging` on 2026-10-27; nothing else changed), services `shifa-doc-backend-mvp` and `Postgres`. Custom domain `api.shifa.uz` added 2026-10-27/28 (CNAME `api` → `7vnksdyz.up.railway.app` plus the TXT ownership record `_railway-verify.api`, both at ahost.cloud); the generated `shifa-doc-backend-mvp-production.up.railway.app` stays.
2. **Volume (Q2): answered.** A 2 GB volume is mounted at `/app/storage` with daily backups. Nothing to do.
3. **Rotate the secrets listed in S5 and section 1.3** (Daily API key; anything that appears in the committed log archives). Set the new values on the production service. Do this before the Phase 0 PR that removes the fallback merges, so the deploy does not fail on a missing key.
4. **Create the `development` environment.** Environment dropdown → `+ New Environment` → *Duplicate* `production`. Railway stages a copy of every service and its variables. **Before approving the staged changes:**
   - Remove the duplicated Postgres from the staged set if it is a copy, and add a **new** Postgres service to the development environment (same template as production, so pgvector is present; verify with `CREATE EXTENSION IF NOT EXISTS vector` on the new DB).
   - Open the backend service's variables **in the development environment** and make sure `DATABASE_URL` is a reference to the **new** Postgres (`${{Postgres.DATABASE_URL}}` of the dev instance), not a literal copy of the production URL. This is the single most important check in the whole setup.
   - Replace every secret with a development value, using the list in 3.6: `JWT_SECRET` (new random), Stripe **test** keys and webhook secret, Click test merchant secret (or leave Click disabled in `application-dev.yml` until you have one), a Daily domain or API key for dev, Firebase (Q4), `MAIL_PASSWORD` (Brevo can stay shared), `OPENAI_API_KEY` (a separate project keeps the bill readable).
   - Set `SPRING_PROFILES_ACTIVE=dev` (profile added in Phase 1). Until PR 1.10 lands, also set the non-secret variables the code still reads (`APP_PUBLICBASEURL` = the dev domain from step 6, `APP_FRONTENDURL`, `STORAGE_ROOT`); after 1.10 they live in `application-dev.yml` and are deleted here.
   - Do **not** copy production's non-secret variables into dev as literals; they become yml in 1.10.
5. **Branches: done 2026-10-28.** `development` deploys from `main`, `production` from `production` (branch created at the live commit). Verified through the API: these are the only two triggers in the project and PR deploys are off.
6. **Domains.** In `development`, Settings → Networking → Generate Domain. If you want custom domains (`api.shifa.uz`, `api-dev.shifa.uz`, Q5), add them now; apps only need to learn a URL once.
7. **Wait for CI.** After the first CI workflow run on `main` (Phase 1), the toggle appears under Settings → Deploy in each environment. Turn it on in both.
8. **Health check.** Keep `/actuator/health/liveness` (already in `railway.toml`). Phase 3 switches to `readiness`, which also checks the database.
9. **Backups: answered.** Both volumes have a daily Railway backup (Postgres at 02:19 UTC, uploads at 06:56 UTC) with 6-day retention. A weekly 90-day schedule was proposed and declined for now (2026-10-27); it stays on the table because a corruption noticed on day 7 is unrecoverable with 6-day retention. The first restore drill (into the development Postgres) is scheduled after Phase 2 and repeated quarterly (13.5, 13.6).
10. **Seed the development database.** It starts empty; Flyway creates the schema on the first deploy and the existing seed migrations load ICD-10 and catalogs. Test doctors with slots, test patients with appointments, documents, tasks and notifications come from the dev-only seed in Phase 1 (`db/seed-dev/`). Never copy production data into dev; if you ever need realistic volume, use an anonymised dump, which is a separate task.

### 3.3 Actions for you: GitHub

1. **Branch protection / rulesets** on `SHIFA-UZ/backend-kotlin-shifa`:
   - `main`: require a pull request, require the `CI` status check, block force pushes and deletion.
   - `production`: block direct pushes and force pushes for everyone, **allow the GitHub Actions app to bypass** (the release workflow pushes with `GITHUB_TOKEN`). Rulesets → "Bypass list" → add "GitHub Actions" (or a dedicated deploy app if you prefer).
2. **GitHub Environment `production`** (repo Settings → Environments): add yourself as a *required reviewer*. The release workflow targets this environment, so every production release shows an approval button and an audit trail. Add `DEV_BASE_URL` as an environment variable (the dev Railway domain).
3. **Dependabot**: the Phase 1 PR adds `.github/dependabot.yml` for Gradle and GitHub Actions. Dependabot PRs run CI like any other PR.
4. **Secrets:** none are needed for the pipeline as designed. (The `railway up` alternative would need `RAILWAY_TOKEN`.)

### 3.4 Actions for the apps

| App | Change | When |
|---|---|---|
| Patient (KMP) | `ApiConfig.BASE_URL` becomes build-type dependent: debug builds and Firebase App Distribution builds use the dev URL, release builds use prod. One constant generated from a Gradle property (`-PapiBaseUrl=`) or a `BuildConfig` field in `androidMain`/`iosMain`. Resolves item 3 of the patient app's list. | After the dev environment exists (end of Phase 1) |
| Doctor (Flutter) | Add a `dev` job or workflow input that passes `--dart-define=API_BASE_URL=<dev url>`; the admin panel's hostname routing keeps working because it is client-side. | Same |
| Both | If you adopt custom domains (Q5), plan one release of each app that switches the URL, and keep the Railway domain alive until that release is the oldest supported version. | Phase 2 |

### 3.5 The workflows

Both files are added by Phase 1. Shown here so you can review them before I open the PR.

`.github/workflows/ci.yml`:

```yaml
name: CI
on:
  push:
    branches: [main, production]
  pull_request:

jobs:
  build:
    runs-on: ubuntu-latest
    services:
      postgres:
        image: pgvector/pgvector:pg16
        env:
          POSTGRES_DB: shifa
          POSTGRES_USER: shifa
          POSTGRES_PASSWORD: shifa
        ports: ["5433:5432"]
        options: >-
          --health-cmd "pg_isready -U shifa"
          --health-interval 5s --health-timeout 5s --health-retries 10
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: "21" }
      - uses: gradle/actions/setup-gradle@v4
      - run: ./gradlew build --no-daemon
      - uses: actions/upload-artifact@v4
        if: failure()
        with: { name: test-reports, path: build/reports/tests/test }
```

The Postgres service makes the 4 skipped tests run, and lets the new context-load test (Phase 1) start the real application against a fresh database with `ddl-auto: validate`. That single test catches the two outages this codebase is most exposed to: a migration that does not apply, and an entity that no longer matches the schema.

`.github/workflows/release-production.yml`:

```yaml
name: Release to production
on:
  workflow_dispatch:
    inputs:
      ref:
        description: Commit SHA or branch to release (must be on main and live on development)
        default: main

permissions:
  contents: write

jobs:
  release:
    runs-on: ubuntu-latest
    environment: production          # required reviewer = your approval click
    steps:
      - uses: actions/checkout@v4
        with: { fetch-depth: 0, ref: "${{ inputs.ref }}" }
      - name: Resolve SHA
        run: echo "SHA=$(git rev-parse HEAD)" >> "$GITHUB_ENV"
      - name: SHA must be on main
        run: git merge-base --is-ancestor "$SHA" origin/main
      - name: Development must be healthy and running this SHA
        env: { DEV: "${{ vars.DEV_BASE_URL }}" }
        run: |
          curl -fsS "$DEV/actuator/health/readiness"
          test "$(curl -fsS "$DEV/actuator/info" | jq -r .commit)" = "$SHA"
      - name: Fast-forward production branch
        run: git push origin "$SHA:refs/heads/production"
```

`git push <sha>:refs/heads/production` is fast-forward only; it refuses anything else, which is exactly the rule. The `.commit` field comes from Railway's `RAILWAY_GIT_COMMIT_SHA` runtime variable exposed through `/actuator/info` (Phase 1 adds two lines of config and a `permitAll` for `/actuator/info`). No git plugin, no extra dependency.

Alternative, if you prefer not to have a `production` branch: the same workflow can run `railway up --service <name> --environment production --detach` with a `RAILWAY_TOKEN` project token. It works, but Railway then builds from the runner's upload instead of the GitHub commit, and rollback is still the dashboard. I recommend the branch pointer.

### 3.6 Configuration and secrets: narrow the variables

Today the yml files read **about 90 environment variables**, and `Application.kt` reads 17 more by hand. Only 13 of them are secret. Everything else (URLs, model names, timeouts, rate limits, feature flags, merchant ids, mail sender) is plain configuration that happens to be overridable from Railway, which means the real configuration of production is spread between the repo and the Railway dashboard and nobody can read it in one place.

**Policy from Phase 1 on:**

1. **A value is a Railway variable only if it is a secret**, or if Railway itself injects it (`PORT`, the Postgres reference variables, `RAILWAY_GIT_COMMIT_SHA`), plus the single selector `SPRING_PROFILES_ACTIVE`.
2. **Everything else is hardcoded in the repo**, in `application.yml` (same in every environment) or `application-dev.yml` / `application-prod.yml` (differs per environment). Changing it is a PR that goes through CI → development → production like any code change, and `git log` shows who changed what and when.
3. **No new secret is introduced when an existing one can be derived.** The file-URL signature in 0.2 uses a key derived from `JWT_SECRET` (`HMAC-SHA256("shifa-file-url", JWT_SECRET)`), so there is no `FILE_URL_SECRET`. The same pattern applies to any future signing need.
4. **No `${VAR:default}` placeholders for non-secrets.** A placeholder for a non-secret invites someone to set it in the dashboard and fork the configuration again.
5. **Secrets have no defaults.** A missing secret fails startup with its name in the log (Railway then keeps the previous deployment running).
6. **Nothing secret lives in GitHub**, neither in files nor in GitHub Actions secrets. The pipeline in 3.5 needs none.

**The secrets (the complete Railway variable list per environment after Phase 1):**

| Variable | Why it is secret | Dev value |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | not secret, the profile selector (`dev` / `prod`) | `dev` |
| `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` | database credentials, as Railway reference variables to the environment's own Postgres (2.2; until then `DATABASE_URL`) | references to the dev Postgres |
| `JWT_SECRET` | signs every token; also derives the file-URL key | separate random value |
| `FIREBASE_SERVICE_ACCOUNT_JSON` | service account private key | dev project or shared (Q4) |
| `OPENAI_API_KEY`, `GEMINI_API_KEY`, `XAI_API_KEY` | provider keys (the two optional ones only if you actually use Gemini/Grok; otherwise they are removed with their providers) | separate keys or projects for billing visibility |
| `DAILY_API_KEY`, `DAILY_WEBHOOK_SECRET` | video provider key and webhook signing secret | dev domain's values |
| `STRIPE_SECRET_KEY`, `STRIPE_WEBHOOK_SECRET` | payment provider | test-mode values |
| `CLICK_SECRET_KEY` | payment provider callback signing | test merchant, or Click disabled in `application-dev.yml` |
| `DEVSMS_API_TOKEN` | SMS provider | same account or SMS disabled in dev |
| `MAIL_PASSWORD` | SMTP password | same Brevo account |
| `S3_ACCESS_KEY`, `S3_SECRET_KEY` | object storage (from 2.7; Railway reference variables if its own storage is used) | dev bucket |

That is 13 secrets plus the profile selector and the database references. Everything below leaves Railway.

**Moves to `application-prod.yml` / `application-dev.yml` (environment-specific, not secret):** `app.publicBaseUrl`, `app.frontendUrl`, `app.storageRoot` (gone after 2.7, replaced by `S3_ENDPOINT`/bucket name in yml), Stripe Connect refresh/return URLs, Click `merchant-id`, `service-id`, `merchant-user-id` and `enabled` (identifiers that already appear in the public checkout URL), `payment.enabled-providers`, mail `from`, `from-name`, `username` (the SMTP login is an account identifier, not a credential), DevSMS `sender-from`, Google Maps API key (it is served to every client by `/api/public/config`, so it is public by design; restrict it by app/referrer in Google Cloud instead), AI rate limits (dev gets generous values for testers), springdoc on/off, log level.

**Moves to `application.yml` (same everywhere, no override):** `jwt.issuer`, token lifetimes, OpenAI/Gemini/Grok base URLs, model names, temperature, max tokens, timeouts, token budgets, provider `enabled` flags and routes, transcription and TTS options, `maxRequestsPerMinute`, general and auth rate limits, `payment.hold-minutes`, DevSMS base URL, price and currency, Daily API URL, Click pay base URL, mail host and port, `transcription-feedback-enabled`, multipart limits, Hikari and Tomcat sizes.

**Removed entirely:** `LOG_FILE_PATH` (logs go to stdout, 2.3), `SERVER_PORT` (Railway sets `PORT`), `CLOUD_SQL_INSTANCE`, `DB_NAME`, `DB_USER`, `DB_PASS` (GCP, Q3), the `DB_HOST`/`DB_USERNAME`/`DB_PASSWORD`/`PG*` fallback chain in `Application.kt` (2.2), `GOOGLE_APPLICATION_CREDENTIALS` temp-file dance (2.2), `DB_PORT` (local only; lives in `application-local.yml`).

**Local development:** `application-local.yml` (git-ignored) holds the developer's own secrets; a committed `application-local.example.yml` lists the keys with empty values. The hand-rolled `.env` loader in `Application.kt` goes.

**Operational toggles.** Flags like `click.enabled` or `ai.rate-limit.enabled` stop being Railway variables too. Flipping one is a one-line yml PR that reaches production through the normal path in minutes, and the history shows when it was flipped and by whom. If you want a true emergency switch that works without a deploy, say which flags, and only those keep an env override. My recommendation is none.

**Actions for you:**

1. Before the configuration PR (1.10), export the **current values** of the non-secret variables from the production service (Raw Editor) and paste them to me without the secrets. The PR hardcodes exactly those values, so production behaviour does not change; the golden tests (1.4) confirm it on development first.
2. After 1.10 is live in production, delete the non-secret variables from both Railway environments. Deleting them is safe at that point because the code no longer reads them; keeping them is harmful because a stale value in the dashboard would silently win again if a placeholder ever came back.
3. Rotate the Daily key (S5) and anything that appears in the committed log archives, except `JWT_SECRET` (see the 0.1 contract).

---

## Status 2026-10-29

Phase 0 is on development except the two enforcement flips; Phase 1 is done except 1.10; Phase 2 items 2.3
(partly), 2.5, 2.6, 2.8 and the Firebase half of 2.2 are done; Phase 3 steps 3.1–3.3 are done (Boot 3.5.16,
Kotlin 2.2.21, Gradle 8.14.3); Phase 4 items 4, 5, 7, 8, 9, 12, 13, 14, 15 are implemented additively; P4
(batch size), P7 (SSE executor), the cache item and the first indexes from Phase 6 are in. The per-step
record, the open items and why they are open are in `docs/OVERNIGHT_2026-10-29.md`.

1.10 and 2.2 are implemented together (branch `refactor/config-consolidation`): every non-secret is a literal in
`application.yml` / `application-prod.yml` / `application-dev.yml` with the values production ran with on
2026-10-29, `Application.kt` is `runApplication` only, the datasource comes from the `SPRING_DATASOURCE_*` reference
variables (set in both Railway environments on 2026-10-29), `ConfigPolicyTest` enforces the policy. Before the
production release that carries it: apply the production deploy settings (1.13), so a boot failure keeps the old
deployment serving. After it is live in production, delete from **both** Railway environments: `DATABASE_URL`,
`DB_USERNAME`, `DB_PASSWORD`, `APP_PUBLICBASEURL`, `APP_FRONTENDURL`, `STORAGE_ROOT`, `JWT_ISSUER`, `MAIL_USERNAME`,
`MAIL_FROM`, `MAIL_FROM_NAME`, `OPENAI_PROJECT_ID`, `DAILY_API_URL`, `DEVSMS_SENDER_FROM`, `CLICK_ENABLED`,
`CLICK_MERCHANT_ID`, `CLICK_SERVICE_ID`, `CLICK_MERCHANT_USER_ID`, `STRIPE_CONNECT_REFRESH_URL`,
`STRIPE_CONNECT_RETURN_URL`, `PAYMENT_ENABLED_PROVIDERS` (dev), and the stale `PUBLIC_BASE_URL` and
`SPRING_VARIABLES_ACTIVE` (prod). `GOOGLE_MAPS_API_KEY` stays a variable until its value is confirmed public.

**Phase 5 step 1 is done for every feature at once** (branch `refactor/phase5-move`, 2026-10-29): 436 main and 60 test
files moved class-for-class into `com.shifa.<feature>.{api,application,domain,infrastructure}` (`shared/` keeps
`security/`, `config/`, `api/`, `storage/`, `util/`; `payment/` became `payments/`; `clinicalengine/` and
`intelligence/appointment/`, `ai/llm`, `ai/memory` and `ai/speech` are layered into `appointments/` and `ai/`). Only
`package`, `import` and fully-qualified references changed (verified by a script that compares every moved file with its
original ignoring those lines), the OpenAPI snapshot is byte-identical and all 412 tests pass. Placement decisions worth knowing: `SubscriptionTier` stays with `User` in `auth.domain`,
`SubscriptionTierService` is `payments.application`; `BookingPaymentPolicy` is `appointments.application`,
`AppointmentPaymentSettlement` is `payments.application`; the AI heuristics (`SymptomMatcher` etc.) are `ai.domain`;
`AuditService`/`AuditLog` are `admin`; `DoctorBilling` is `payments`; `VisitChargeService` is `clinics`. Steps 2–4 (DTO
extraction, controllers without repositories, ArchUnit border rules) now follow one feature per PR in the 9.2 order.

**Phase 5, `shared` step (branch `refactor/shared-module`):** `patients.application.CurrentPatientResolver` replaces the
nine private copies (order: `user_id`, then newest exact phone match, then newest exact e-mail match; it lives in
`patients` rather than `shared` so that `shared` stays feature-free); `shared.PublicUrls` replaces a dozen
`normalize*Url` copies and every `publicBaseUrl` concatenation; `ArchitectureTest` (ArchUnit, frozen baselines in
`src/test/resources/archunit-store`) enforces from now on: domain depends on nothing above it, api does not use
infrastructure, shared does not depend on features, a feature's infrastructure is used only by that feature. Each
later feature step shrinks the frozen lists; a new violation fails the build. The production check for patients whose
profile resolves differently by `user_id` than by phone/e-mail is `docs/sql/patient-resolution-check.sql` (run with
`railway connect Postgres --environment production --ssh < docs/sql/patient-resolution-check.sql`); its result is
run on production 2026-10-29: 70 patient users, `changed_vs_*` all 0, `lost_vs_any` 0, `multiple_linked` 0,
`gained_vs_c` 5 (five patients who got 404 on tasks, chat, appointments and notifications resolve from now on). The
resolver is therefore a pure refactor for 65 users and a fix for 5; no data merge needed.

2.7 is implemented on development (branch `refactor/object-storage`): Railway bucket `shifa-uploads` (region iad, next
to the service), `shared/storage/FileStorage` with local and S3 backends, `StoredFileController` serving the five
prefixes unchanged, `POST /api/admin/storage/migrate` copying the volume into the bucket (idempotent, size-verified),
reads falling back to the volume meanwhile. Production steps, in order: (1) `railway bucket create shifa-uploads
--region iad --environment production`; (2) the three reference variables on production (`S3_BUCKET=${{shifa-uploads.BUCKET}}`,
`S3_ACCESS_KEY=${{shifa-uploads.ACCESS_KEY_ID}}`, `S3_SECRET_KEY=${{shifa-uploads.SECRET_ACCESS_KEY}}`, with
`--skip-deploys`); (3) a PR moving the `app.storage.s3` block from application-dev.yml to application-prod.yml;
(4) after the release, an admin runs the migration until it reports zero copied and zero failed; (5) a week of zero
fallback reads later, unmount the volume. Object storage costs $0.015 per GB-month; bucket egress is free.

## 4. Phase 0: stop the bleeding (security hotfixes)

**Goal:** close the findings in 1.4 and P1 with the smallest possible diffs, through the current deploy path, before anything else. Each row is one PR. None changes an API shape the apps rely on.

| PR | Change | Action for you |
|---|---|---|
| 0.1 | `create-patient-for-doctor`: require a verified OTP on every path (email OTP as today; for phone-only requests require `smsOtp` via the existing `SmsOtpService`, else 400). Remove the path from `JwtAuthFilter.shouldNotFilter` so a presented token is still honoured. **Verified compatible:** both the KMP app (`AuthRepository`) and the retired Flutter app (`auth_repository.dart`) always send `email + emailOtp`; the doctor app never calls it. Phone-only requests can only come from an attacker. | None. |
| 0.2 | **Signed file URLs, rolled out in two steps.** Step 1 (this PR): every URL the API returns for `patientdocuments/`, `patients/` photos and `chat-attachments/` carries `?exp=<unix>&sig=<hmac>` (HMAC-SHA256 with a key **derived** from `JWT_SECRET`, `HMAC("shifa-file-url", JWT_SECRET)`, so no new secret; 1 h expiry). A `SignedUrlFilter` verifies the signature but, for 7 days, only **logs** unsigned hits (path prefix and user agent, no PII). Step 2 (one-line PR after the week): unsigned → 403. **Verified compatible:** the KMP app loads whatever `url` the API returns (Kamel, no header); the doctor app never uses these paths, it downloads through the authenticated `GET /api/patients/{id}/documents/{docId}/download`; the Flutter patient build only ever worked with the absolute `url`. The log week proves no client builds file URLs itself. PDFs stay `inline` (the apps open them in a viewer). Doctor photos and certificates stay public (they are on the public doctor list). `/photos/**` is removed entirely: production holds **zero** stored URLs under `/photos/` (query Q-B, 2026-10-27), and no app build constructs them. `scribe-temp` and `transcription-feedback` move out of `STORAGE_ROOT`. | None. No new Railway variable. |
| 0.3 | Delete `TestDataController` and its `permitAll` line. The `patient@test.com` account does **not** exist in production (query Q-E, 2026-10-27), so no data change is needed. Dev test data moves to the dev seed in Phase 1. **Verified:** no app build (KMP, Flutter, doctor) calls `/api/test/**`. | None. |
| 0.4 | Remove the `DAILY_API_KEY` fallback. Remove the `shifa` DB password fallbacks from prod/qa yml. Startup check in `prod`/`dev`: **refuse** to start if `click.enabled` with a blank `click.secret-key` (no live user impact: Click callbacks would be unsafe anyway); **warn only** if `JWT_SECRET` is shorter than 64 chars. We do not rotate `JWT_SECRET` (see 0.1 contract); if the committed logs contain tokens we revoke those sessions in `user_sessions`. If a new deploy fails this check, Railway keeps the old deployment serving. | Rotate the Daily key first (3.2 step 3). Tell me the length of the current `JWT_SECRET` (not the value). |
| 0.5 | **Reduced to a limit only.** `GET /api/messages/search` gets `LIMIT 50` (same shape, same ordering; the doctor app shows a short list). `for-assignment` is **left as is**: the doctor app deliberately loops pages of 500 to build the full directory for "assign patient". Scoping either to the doctor's roster changes which patients a doctor can find, which is a product decision for the doctor app, so it moves to Phase 4 behind a doctor-app release. Until then S3 stays open and is listed as accepted risk. | Decide (Q10): should a doctor be able to search or assign patients who have no relationship with them or their clinic? |
| 0.6 | Click callback: reject when secret blank; `MessageDigest.isEqual` for the signature. No response change for valid callbacks. | None. |
| 0.7 | **Done 2026-10-28, outcome differs from the plan.** The property `app.rate-limit.trusted-proxy-hops` exists (default `0`), but the forged-header test on dev showed that Railway's CDN layer (CDN77 nodes, e.g. `79.127.224.65`) **replaces** `X-Forwarded-For` with `<client>, <cdn node>` and drops whatever the client sent; confirmed with a plain forged value, two header lines, lowercase over HTTP/1.1, a forged chain with a port, and on both production hostnames (GET only). So hops `0` already keys on the real client, and `1` would key on the CDN node (one bucket per node: wrong). `0` stays in prod and dev. **Residual risk:** this holds only for traffic that enters through Railway's public HTTPS ingress, the paths tested; private networking, TCP proxies or a CDN switch to appending would make the first entry spoofable again, so no other ingress may be opened to this service without repeating the test. The dev overlay logs the header tail (256 chars) at DEBUG on this filter's 429s so a format change shows up (it cannot detect evasion that never reaches 429). Re-check with `curl ... -H 'X-Forwarded-For: 203.0.113.7'` x13 over one connection against `/api/auth/login` on dev after any Railway networking change. | None. |
| 0.8 | Uploads: one `UploadPolicy` in `shared/` used by all 4 upload paths. Allow-list = what production actually stores (query Q-D, 2026-10-27: `pdf` 825, `png`, `jpg`, `jpeg`, and `m4a` voice messages in chat) plus what the apps' pickers can offer (`doc docx` in doctor chat, `heic/heif` images from iOS, scribe audio `mp3 wav webm ogg`). First PR logs rejected-would-be uploads for a week; second PR enforces. UUID file names for **new** uploads only; existing paths untouched. Images and PDFs keep `inline`; everything else `attachment` + `nosniff` (this is what closes the stored-XSS, and `.doc/.docx` were never opened inline by any app). | None. Run the extension query I give you. |
| 0.9 | Logging: remove phone/email from `VideoController`, remove the `println`s in `Application.kt`, log OTP codes only in the `local` profile, log LLM response bodies at DEBUG without the body. | None. |
| 0.10 | Payment idempotency: `createConsultationCheckout` returns the **same response shape** (`paymentId`, `checkoutUrl`, …) for the open `PENDING`/`AUTHORIZED` payment if one exists and its checkout is younger than the hold window; an older open one is cancelled and a new one created (gateway links expire). Repository returns a list and the service picks the latest. **Production has the bug's footprint today (query Q-A, 2026-10-27): 5 appointments with duplicate open checkouts, 13 surplus rows, one appointment with 6.** So the PR ships in two parts: (a) a data-fix migration that sets every open payment except the newest per appointment to `CANCELLED` with `failure_reason = 'duplicate checkout cleanup 2026-10'` (idempotent, row counts logged); (b) the partial unique index `payments(appointment_id) WHERE status IN ('PENDING','AUTHORIZED')`, created `CONCURRENTLY` (`-- flyway:executeInTransaction=false`) after (a) in the same migration file order. | Approve the data-fix SQL in the PR; nothing to run by hand. |
| 0.11 | Swagger UI and api-docs off in `prod` (`springdoc.api-docs.enabled=false`), on in `dev`. No app calls them at runtime; the patient app team uses `/v3/api-docs` for contract checks and switches to the dev URL (and the committed `docs/api/openapi.json` from Phase 1.4). `POST /api/public/doctors/{id}/reviews` → `authenticated()`: calls with a token behave as today, anonymous calls get 401 instead of 500. | Tell the patient app team the api-docs move to dev. |
| 0.12 | Repo hygiene: `git rm` the log archives, root SQL scripts, `uat_dump.sql`, `src/main/kotlin/graphify-out/`, `images/`; extend `.gitignore` (`*.log.*`, `graphify-out/`, `*.sql` outside `src/`); un-ignore `*.md` (done on this branch). No runtime effect. | Review the list before I delete anything. |

**Done when:** every item in 1.4 reads "closed" or "accepted (Q10)" in the PR description, production deploys are green, and the apps' smoke flows (login, book, upload a document, open a document, chat with an attachment, assign a patient on the calendar, search a chat contact) still work on the current store builds.

---

## 5. Phase 1: safety net (CI, tests, dev profile, release workflow)

**Goal:** make "merge to main" safe to automate.

| PR | Change |
|---|---|
| 1.1 | Fix the flaky `DoctorMorningToolDispatchTest` by injecting a `Clock` (the service already has one call site; the test uses a fixed clock). |
| 1.2 | `ci.yml` as in 3.5. Gradle build cache via `gradle/actions/setup-gradle`. |
| 1.3 | `ApplicationContextTest`: `@SpringBootTest` with `spring.jpa.hibernate.ddl-auto=validate` against the CI Postgres. Fails if a migration is broken or an entity drifts from the schema. Makes the 4 `assumeTrue` Postgres tests run in CI by giving them the same connection. |
| 1.4 | **OpenAPI snapshot test and golden response tests** (see 0.1 contract). The context test writes `/v3/api-docs` to `build/` and compares it with `docs/api/openapi.json` committed in the repo; it also calls the app-critical endpoints against the dev seed and compares the JSON with committed golden files (volatile fields masked). Any contract or serialisation change must come with the snapshot update in the same PR, which makes accidental breaking changes visible in review. This also gives the patient app team a contract file in git. Also pins `spring.data.web.pageable.serialization-mode=direct` explicitly, so `Page` JSON cannot change under us (see 3.4). |
| 1.5 | Profiles: add `application-dev.yml` (copy of prod with DEBUG for `com.shifa`, springdoc on, `open-in-view: false`). `application-prod.yml` keeps every runtime value it has today. Rename the local default to `local` semantics by moving the dev secrets out of `application.yml` into an untracked `application-local.yml` and setting `spring.profiles.default=local`. `application-qa.yml` is deleted once Q1 is answered (if a QA Railway project still runs with `SPRING_PROFILES_ACTIVE=qa`, deleting the profile would break it). `ErrorHandler` reads `app.expose-error-details` (true only in `local`) instead of string-matching the profile name; the prod 500 body stays byte-identical (`"Internal server error: <ExceptionClass>"`). `open-in-view: false` in `local` too, so local shows the same lazy-load failures as prod. |
| 1.6 | `/actuator/info` exposes `commit` from `RAILWAY_GIT_COMMIT_SHA` (`management.info.env.enabled=true`, `info.commit=${RAILWAY_GIT_COMMIT_SHA:local}`) and is `permitAll`. |
| 1.7 | Dev seed: `src/main/resources/db/seed-dev/R__dev_seed.sql` (repeatable migration) with 3 doctors (slots, services with free / pay-later / pay-now prices), 5 patients with appointments in every status, documents, tasks with check-ins, notifications of every type, a clinic with staff. Enabled only by `spring.flyway.locations` in `application-dev.yml`. Resolves the test-data half of patient ask 3. |
| 1.8 | `release-production.yml` as in 3.5, `.github/dependabot.yml`, `CODEOWNERS` (you). |
| 1.9 | Cut over: **done early on 2026-10-28** for the branch and the triggers (see 1.2). Remaining: enable Wait for CI in both environments once the first CI run exists, then the first release through the workflow. |
| 1.11 | **Measurement and quality gates** (scorecard 13): `RequestTimingFilter` logging one JSON line per request (route template, status, ms; no PII) plus `scripts/latency-report.sh` over Railway logs; a daily metrics summary line (heap, Hikari active, thread count); ktlint and detekt with a baseline file so existing code does not block; JaCoCo with a coverage ratchet; the **route authorization coverage test** (every OpenAPI path must match an explicit `SecurityConfig` rule; the public allow-list is a committed file); the **schema baseline test** (`docs/db/schema-baseline.json` from `information_schema` on the CI Postgres); `/actuator/info` also exposes the latest Flyway version for the release workflow's parity check. |
| 1.12 | **Supply-chain and secret scanning:** `gitleaks` on every PR and once over the full history; `actions/dependency-review-action` on PRs; Dependabot security updates on (1.8). No new secrets: both run with `GITHUB_TOKEN`. |
| 1.13 | **Railway settings off `railway.toml` before 2026-12-01.** The three settings the file carries today (health check path and timeout, restart policy, start command) are moved to service settings through the Railway API, verified on development, then applied to production on your "go"; then `railway.toml`, `railway.json`, `nixpacks.toml` and `Procfile` are deleted from the repo in the same PR. With the Dockerfile builder (2.1) the start command comes from the image, so only health check and restart policy remain as settings. A `.railway/railway.ts` IaC file is **not** adopted now: it would add an npm dependency and a second deploy path for two settings. Revisit when Railway makes IaC the only option. |
| 1.10 | **Configuration consolidation** (policy in 3.6): every non-secret `${VAR:default}` placeholder becomes a literal in `application.yml` or the profile files, with the values production uses today (you export them, see 3.6 actions). Secrets lose their defaults and fail startup with a clear message when missing. `Application.kt` keeps only `runApplication`. `application-local.example.yml` committed. After it is live in production, the non-secret Railway variables are deleted. Golden tests prove behaviour is unchanged. |

**Done when:** a PR cannot merge with red CI, `main` auto-deploys to development, a release to production requires your click and shows the SHA it shipped, and `git log production` lists every production release.

---

## 6. Phase 2: platform hygiene (Railway, JVM, config, storage, locking)

**Goal:** one source of truth for how the service runs, and no behaviour that breaks with two instances.

| PR | Change | Action for you |
|---|---|---|
| 2.1 | **Dockerfile as the Railway builder.** Multi-stage build that runs the tests (Gradle layer cache keeps it fast), produces a layered Spring Boot jar, runs as non-root. The builder is switched to Dockerfile in the service settings (1.13 already removed the config files); `cloudbuild.yaml` goes with Q3. JVM flags move to one `JAVA_TOOL_OPTIONS` default in the Dockerfile; Railway can override per environment. | None: no per-service memory cap is set today (the plan limit of 32 GB applies), so the current flags are safe. |
| 2.2 | Delete the 180-line `DATABASE_URL` parser in `Application.kt`. Railway reference variables give Spring what it needs directly: `SPRING_DATASOURCE_URL=jdbc:postgresql://${{Postgres.PGHOST}}:${{Postgres.PGPORT}}/${{Postgres.PGDATABASE}}`, `SPRING_DATASOURCE_USERNAME=${{Postgres.PGUSER}}`, `SPRING_DATASOURCE_PASSWORD=${{Postgres.PGPASSWORD}}`. The `.env` loader goes; local secrets live in the git-ignored `application-local.yml` (3.6). Firebase credentials: `FIREBASE_SERVICE_ACCOUNT_JSON` is read by a `@Bean` from the env var instead of writing a temp file. Safety: if the variables were missing, the new instance would fail its health check and Railway would keep the current deployment serving; there is no window where production is down. | Add the three variables in both Railway environments **before** this PR deploys (they can coexist with `DATABASE_URL`; nothing breaks if both are present). |
| 2.3 | Logs to stdout only (JSON in `dev`/`prod` so Railway's log search can filter by field; plain in `local`). Remove `logging.file.*`. Request id (`X-Request-Id`) in MDC and in error responses, so a user's error can be matched to a log line without PII. | None. |
| 2.4 | Health: switch Railway to `/actuator/health/readiness` (includes the DB check) once we know cold start fits: measure on dev, then set `healthcheckTimeout` accordingly. Graceful shutdown (`server.shutdown=graceful`, 30 s) so in-flight SSE streams finish during a deploy. | Change the health path in Railway when I say the dev measurement is done. |
| 2.5 | **ShedLock** (`shedlock-spring` + `shedlock-provider-jdbc-template`, one `shedlock` table migration) on all six scheduled jobs. Reminder job: move the SMS/FCM calls out of the transaction (mark-then-send with a `sent_at` column, or an outbox table processed by the same job). | None. |
| 2.6 | Per-instance state: session validation cache keyed by a `revoked_at` check with a 30 s TTL (shorter window) or replaced by a DB read with the Caffeine cache as a short-lived shield; rate limiter buckets get Caffeine eviction (1 h idle). Full multi-instance correctness (shared store) is not needed at today's scale; the goal is "two instances for 2 minutes during a deploy do no harm". | None. |
| 2.7 | **Object storage for uploads.** `FileStorage` interface in `shared/storage` with a local implementation (dev, tests) and an S3-compatible implementation (AWS SDK v2 `s3` module). **The URLs the apps see do not change host or path:** the API keeps serving `/patientdocuments/**`, `/patients/**`, `/doctors/**`, `/chat-attachments/**` and, after checking the signature from 0.2, streams the object or 302-redirects to a short-lived presigned bucket URL. Absolute URLs already stored in the database and cached in the apps therefore keep working. One-time copy job for existing files (read from the volume, write to the bucket, verify checksum), run on dev first, then on prod while the volume is still mounted; the volume is unmounted only after a week of zero local reads. Provider: Railway's object storage if it is available on your plan, otherwise Cloudflare R2 (S3 API, no egress fees). | Create the bucket(s). Endpoint and bucket name go into `application-dev.yml` / `application-prod.yml`; only `S3_ACCESS_KEY` and `S3_SECRET_KEY` are Railway variables (or reference variables if Railway's own storage is used). |
| 2.8 | Tomcat: `threads.max` down from 300 to 100 (1 MB stack each; the service is I/O-bound on Postgres with a 20-connection pool), Hikari `maximum-pool-size` 15 and `minimum-idle` 2 (Railway Postgres has a connection ceiling; two instances during a deploy must fit). | None. |

**Done when:** deploys are reproducible from the Dockerfile, a deploy with overlapping instances sends no duplicate SMS (verified on dev with a forced redeploy during the reminder window), and uploads survive a redeploy without a volume.

---

## 7. Phase 3: dependency upgrades

Do these after Phase 2, on `chore/` branches, each verified on development for at least a day before release. Order matters.

| Step | From → To | Notes |
|---|---|---|
| 3.1 | Spring Boot 3.3.4 → **3.5.16** (last OSS 3.5) | Same Jakarta and Hibernate 6.6 generation; mostly a drop-in. Fixes the known CVEs between 3.3.4 and 3.5.16. Remove the duplicate/dynamic `jackson-module-kotlin` lines and let Boot manage it. |
| 3.2 | Kotlin 2.0.21 → **2.2.x** | Required by Boot 4. Check `kotlin("plugin.jpa")` and `all-open` still apply to entities. |
| 3.3 | Gradle 8.10.2 → **8.14**, then **9.x** | 9.x drops deprecated APIs; the build script here is small, so expect minor edits. Configuration cache on. |
| 3.4 | Spring Boot 3.5.16 → **4.1.x** | The big one: Spring Framework 7, Spring Security 7, Hibernate 7, Jackson 3, Jakarta EE 11. Known touch points in this codebase: springdoc → 3.x; `jjwt-jackson` → check Jackson 3 support or switch to `jjwt-gson`; `HttpSecurity` lambda DSL already used (good); `@MockBean` → `@MockitoBean` in tests; Hibernate 7 is stricter about implicit joins and `OffsetDateTime` mapping. Drop the Flyway pin (Boot 4 manages 11.x+) and the `flyway-database-postgresql` artifact stays. **Contract risks specific to this step, all caught by the golden response tests from 1.4:** Jackson 3 changes some defaults (null handling, date/time rendering), so Jackson is configured explicitly to today's output before the upgrade; Spring Data's `Page` JSON must stay in `direct` mode (`spring.data.web.pageable.serialization-mode`), otherwise `for-assignment` and every other paged endpoint would switch to the `PagedModel` shape (`page{…}` wrapper) and break the doctor app's paging loop; `@Enumerated(STRING)` names and `ErrorHandler` bodies are compared byte for byte. Soak on development for at least a week with both apps' debug builds. |
| 3.5 | Kotlin → 2.4.x, remaining libs to current (stripe-java, firebase-admin, Bucket4j, Caffeine, pdfbox, OkHttp) | Dependabot does the rest from here. |
| 3.6 | Remove `postgres-socket-factory` and Cloud Run files | Done 2026-10-29 (Q3 = Railway only). |

**Done when:** the service runs on a supported Spring Boot line and Dependabot PRs are green by default.

---

## 8. Phase 4: API contract hardening (the patient app's list)

Every item from `apps-flutter-patient/docs/backend-improvements.md`, what the backend does today, and where it lands. Items are additive; the patient app's `15-api-reference.md` and `DemoBackend` are updated in the same-named branch of that repo.

| # | Ask | Today | Plan | Phase |
|---|---|---|---|---|
| 1 | Push text in the patient's language | Only payment pushes are localised (`i18n/PatientPaymentPushI18n`). All other patient pushes carry hard-coded English `title/body`. Doctor pushes are fully localised via `DoctorPushI18n`. 35 `Notification.Type` values exist. | Option A: `PatientPushI18n` for all 35 types in en/ru/uz/de (copy the texts from the app's `strings.xml`), selected by `PatientProfile.language`, fallback en. Keep `notification{}`. Add `notificationId` to `data` (today the key is `id`); keep `id` for old builds. Publish the type list in the OpenAPI snapshot. | 4 |
| 2 | Push tokens per device | One `patient_profiles.fcm_token` column; `PUT /fcm-token` overwrites; cleanup only on `UNREGISTERED`. About 40 call sites read the column. | New `fcm_tokens(user_id, token UNIQUE, platform, source, created_at, last_seen_at)` table; `POST/DELETE /api/patients/me/fcm-tokens`; `FcmService` sends to all tokens and drops on `UNREGISTERED`/`INVALID_ARGUMENT`. **Legacy semantics preserved:** `PUT /fcm-token {"fcmToken": "x"}` upserts a row with `source=legacy`; `PUT /fcm-token {"fcmToken": ""}` (old builds send this on logout) deletes only the `legacy` rows of that user, so an old build logging out still stops its own pushes without killing the new build's other devices. The column is kept in sync for two releases, then dropped. Same for doctors. | 4 (first item of the `notifications` feature move, Phase 5) |
| 3 | Staging backend | `qa` profile exists; URL unknown; `/api/test/**` open. | Development environment (section 3) + dev seed (1.7). Firebase per Q4. | 1 |
| 4 | Error codes | `{error:<int>, message, status}`; `code` only on validation errors; security filters write different shapes. 737 `ResponseStatusException` sites with 288 distinct messages. The KMP app **matches on the English `message` text** for the errors it knows. | `shared/errors`: `ApiException(code, status, message, fields?)` and an `ErrorCode` enum; `ErrorHandler` and the filters all emit `{error, status, code, message, requestId, fields?}`: `error`, `status` and `message` exactly as today, `code` added. **Message strings are frozen** until the KMP app matches on `code` and the builds that matched on text are out of support; a unit test pins the messages the KMP app's `ErrorMessages.kt` lists. The filters' bodies (`{"error":"Session invalid"}` style) gain the new keys but keep `error` as it is. Migrate call sites feature by feature (Phase 5); until then unknown errors get `code: "UNKNOWN"`. The enum is published in the OpenAPI snapshot. | 4 (framework) + 5 (migration) |
| 5 | Mark the patient's own documents | `creatorLabel` is the uploader's name; entity has `uploadedByPatientProfile`. | Add `uploadedByPatient: Boolean` and `creatorType: "PATIENT"\|"DOCTOR"` to `PatientDocumentDto`. One-line mapping. | 4 |
| 6 | Absolute URLs | Documents and most photos are absolute via `publicBaseUrl`. Still relative: `AuthController.check-existing-patient`, `MessageService` user search, `DoctorController.avatarUrl`, `CarePartnershipService`. 24 hand-rolled URL builders. | One `PublicUrls` helper in `shared/`; all 24 sites use it; the 4 relative sites fixed. With 0.2 the helper also signs. | 0.2 + 4 |
| 7 | Cancel as a status | **Already done**: `DELETE /patients/me/appointments/{id}` sets `CANCELLED`; the list returns cancelled rows. | Tell the app team; they can drop the workaround. Add a patient push on cancel (today only the doctor is notified). | 4 |
| 8 | Delete account for phone-only patients | Email OTP only; `request` sets `PENDING_DELETION` immediately without a code; the code is not tied to `challengeId`. | Add `ACCOUNT_DELETION` purpose to `SmsOtpService`; `/confirm` accepts `smsOtp`; `request` sends the code (SMS or email by what the account has) and stops flipping status before confirmation; tie the code to the challenge. | 4 |
| 9 | Doctor search cleanups | Radius filter keeps doctors with `null` distance; `parseJsonList` returns `null` for empty. | Filter `dist != null && dist <= radiusKm` **only when the client sends `radiusKm` explicitly** (the KMP app does; a request without it keeps today's result set, so no build loses doctors it shows now); `parseJsonList` returns `emptyList()` (every JSON client parses `[]` where it accepted `null`). Plus the query rewrite in Phase 6 (P5) with identical output, verified by the golden test. | 4 |
| 10 | Google sign-in | Nothing. | `POST /api/auth/google` verifying the Google ID token with Firebase Admin (already a dependency, if the Firebase project enables Google as a provider) → same `TokenResponse`. Product decision; scheduled when wanted. | later |
| 11 | Shifa AI codes, limits, attachments, history | SSE error codes exist (`VALIDATION`, `SAFETY_BLOCK`, `RATE_LIMIT`, `AI_UNAVAILABLE`) but are undocumented; uncaught errors send no coded event; `X-RateLimit-*` headers and the 429 body with `resetAt` already exist. No storage, no attachments. | Document codes and headers in the API reference; map every failure to a coded SSE event. Chat history (`copilot_threads`, `copilot_messages`) and an `attachmentDocumentId` field are product work, scheduled after Phase 5's `ai` move. | 4 (codes) / later |
| 12 | Payments | Hold, `payments/methods`, pay later: done. Statuses exist (`PENDING AUTHORIZED PAID FAILED CANCELLED REFUNDED`; appointment `NOT_REQUIRED PENDING PAID FAILED REFUNDED PAY_IN_CLINIC`) but `failureReason` is not exposed. Checkout not idempotent (P1). Patient gets no push on PAID/FAILED. Return pages are HTML on the API; no `shifa://` deep link. No fees/tax, receipts, history. | 0.10 idempotency. Phase 4: expose `failureReason`, patient pushes on PAID/FAILED (localised via item 1), `returnUrl` on the success/cancel pages that redirects to `shifa://payment/return?paymentId=` when a `platform=app` query is present, document all statuses. Fees/tax, receipts, history, saved cards: product scope, after Phase 5. | 0 + 4 |
| 13 | Live visit fields | B2 has `serviceId/serviceTitle/reason/paymentStatus`; B4 adds `signatureRequested/alreadySigned/documentsCount`. No `documentIds`, no attach-after-booking, no patient end-visit. Reschedule leaves the old PENDING checkout open. | Add `signatureRequested`, `alreadySigned`, `documentIds`, `notes` (alias of `reason`) to both DTOs via one shared mapper; `POST /patients/me/appointments/{id}/documents`; define end-visit as "leave the call" (no endpoint) and say so in the reference; reschedule cancels the old open checkout. | 4 |
| 14 | Task entries | `TaskDto` has no `appointmentId` and no doctor; no check-in delete; check-in window not enforced server-side. | Add `doctorId`, `doctorName` (entity has `doctor`), nullable `appointmentId` column; `DELETE /tasks/my-tasks/{taskId}/check-ins/{id}` allowed for 24 h; enforce the window on submit. | 4 |
| 15 | Document's visit | Entity has `appointment`; DTO does not expose it. | Add `appointmentId` to `PatientDocumentDto`. | 4 |
| Q | Questions to confirm | Answers from the audit: error shape differs (item 4); `/profile/photo` is doctor-only, patients use `POST /patients/me/photo`; B1 `day` is in the **doctor's** time zone; T3 `scheduledDate/Time` are wall-clock in `patientTimeZone` (sent in the DTO); appointment statuses are `REQUESTED CONFIRMED IN_PROGRESS CANCELLED COMPLETED NO_SHOW PENDING_PAYMENT EXPIRED PAYMENT_FAILED`; bookings auto-confirm for free, pay-later and unpriced services, `REQUESTED` only comes from the legacy pay-now path and is never auto-cancelled; `prices[].locationId=null` is the global price and a clinic-only price is not usable for video; the video join window is enforced (5 min before to 15 min after), the check-in window is not; D5 effectively needs a token (and 500s without one, fixed in 0.11). | Copy these into the patient app's `15-api-reference.md`. | 4 |

---

## 9. Phase 5: modular monolith migration, one feature at a time

**Goal:** the target layout in section 2, reached by moves that each pass CI and the OpenAPI snapshot unchanged.

### 9.1 Recipe for one feature (one `refactor/<feature>-module` branch each)

1. **Move** the feature's controllers, services, repositories and entities into `com.shifa.<feature>.{api,application,domain,infrastructure}`. IDE move, no edits. CI green, snapshot unchanged.
2. **Extract DTOs** from the controller into `api/dto` files and a `<Feature>Mapper`. No field changes.
3. **Push logic down:** every repository call and every `@Transactional` in a controller moves into an `application` service (`XxxCommands` / `XxxQueries`, or one `XxxService` if small). The controller keeps parsing, principal resolution and the call. Controllers end with zero repository parameters.
4. **Seal the border:** other features that touched this feature's repositories or entities now call its `application` service. Add the ArchUnit rule for this feature. CI fails on the next violation.
5. **Then and only then** make the behavioural changes assigned to that feature from Phases 4 and 7, in separate PRs.

### 9.2 Order

| Order | Feature | Why here |
|---|---|---|
| 1 | `shared/` | Security, config, errors (item 4 framework), storage, `PublicUrls`, `UploadPolicy`, one `CurrentPatientResolver` replacing the 9 `currentPatientProfile` copies. **Careful:** 6 copies resolve by phone/email and 3 by `user_id`; unifying on `user_id` can change which profile a patient sees where duplicates exist. So first a one-off prod query counts users for whom the two resolutions differ; those profiles are merged (data fix, reviewed with you) **before** the resolver switches, and the resolver keeps the phone/email fallback for profiles without a `user_id`. Everything else here is pure extraction. |
| 2 | `notifications/` | Small, cross-cutting, and items 1 and 2 land here. Includes ShedLock'd reminder jobs. |
| 3 | `documents/` | S2 follow-up (object storage, 2.7), items 5, 6, 15. |
| 4 | `payments/` | Already feature-shaped; a rename plus the item 12 work. Good rehearsal of the ArchUnit rules. |
| 5 | `appointments/` | Biggest user value: booking, holds, reschedule, video, reminders, `intelligence/appointment`. Items 13 and the P3/P5 fixes. Adds an `EXCLUDE USING gist (doctor_id WITH =, tstzrange(start_at, end_at) WITH &&) WHERE (status IN ('CONFIRMED','PENDING_PAYMENT','IN_PROGRESS'))` constraint after a one-off overlap check on prod (existing overlaps are resolved by hand first, or the migration would fail and the deploy would be held back). A violation surfaces as the existing 409 `DataIntegrityViolation` response, which both apps already handle as "slot taken". |
| 6 | `auth/` | Split `AuthController` (1547 lines) into `RegistrationService`, `OtpService` (email + SMS), `SessionService`, `PasswordService`. Item 8 and S1 follow-ups. |
| 7 | `patients/`, `doctors/` | Profile and public listing; P5 doctor search rewrite (one query with window functions, DB-side paging). |
| 8 | `chat/` | P5 conversation list rewrite (one query with `DISTINCT ON` for last message and a grouped unread count), bulk mark-read. |
| 9 | `tasks/` | Item 14. |
| 10 | `clinics/` | `TreatmentPlanController` and `ClinicFinanceController` become services (17 and 12 repositories move down). Largest mechanical change, lowest contract risk. |
| 11 | `ai/` | `OpenAiResponseService` (1363 lines) splits into transport (`LlmClient`, already exists in `ai/llm`) and prompt builders; SSE streaming moves to a bounded executor (P7); `clinicalengine`, `scribe` fold in. Item 11 history/attachments after. |
| 12 | `admin/` | Last; it reads from every feature through their `application` services, which is exactly the rule. Adds the additive `admin_audit_log` table and writes one row per admin mutation (13.5). |

Each feature is roughly one to three PRs. The whole phase is a few months of steady work in the gaps between feature requests, which is the point: it never blocks a feature, because features are built in the target shape from Phase 0 on.

---

## 10. Phase 6: performance

Most of these ride along with the feature move they belong to (9.2). Listed here so they are not forgotten and so each has a measurement.

| Item | Change | Measure |
|---|---|---|
| P4 | `fetch = FetchType.LAZY` on the 16 implicit-EAGER relations; `spring.jpa.properties.hibernate.default_batch_fetch_size: 50`; `JOIN FETCH` or projections where the DTO needs the relation. **This is the one performance change that can break production without touching an endpoint:** any code path that reads the relation outside a transaction would start throwing `LazyInitializationException` (500) where it silently worked before. So it is done one entity at a time, only after that entity's feature has moved (Phase 5) and all its read paths are `@Transactional(readOnly = true)`, with the golden tests and a dev soak in between. `default_batch_fetch_size` alone is safe and goes first. | Hibernate statistics in dev (`generate_statistics: true` for one day): query count per request on the appointments list before/after. |
| P5 | Rewrites listed in 9.2 (doctor search, conversations, notifications, reminders, mark-read). Same response JSON, proven by the golden tests. | p95 latency of `GET /api/public/doctors`, `GET /api/messages/conversations`, `GET /api/notifications` from Railway metrics. |
| P6 | Paging **without changing any existing response.** Endpoints that return a `List` today keep returning the full `List` by default; they gain optional `page`/`size` parameters (or a `/v2` sibling) that return a `Page`, and the apps adopt them in their own releases. Existing `Page` endpoints keep their default sizes; a cap is added only above what any app sends (the doctor app sends 500). Admin lists are paged the same way. | No endpoint returns more than 100 rows without a page parameter, but only once every app build that reads the unpaged form is out of support. |
| P3 | `@Transactional` on the 96 write paths (class-level on `application` services, `readOnly = true` on query services). | ArchUnit rule: every public method of an `application` class is transactional. |
| P7 | SSE on a bounded `ThreadPoolTaskExecutor` (or Spring's `ResponseBodyEmitter` with `spring.mvc.async.request-timeout`); `SseEmitter` timeout 5 min. | Thread count under 50 concurrent streams on dev. |
| Cache | Caffeine for public doctor profile (`60 s`), professions, clinics list, subscription tier per user (`30 s`, invalidated by admin writes). | Postgres connection and CPU on Railway metrics. |
| Indexes | Review from `pg_stat_statements` on dev after the seed and a load script; expected: `appointments(doctor_id, start_at)`, `notifications(patient_id, created_at DESC)`, `messages(conversation_id, created_at DESC)`, `payments(appointment_id) WHERE status…` (0.10). All `CREATE INDEX CONCURRENTLY`. | Index usage in `pg_stat_user_indexes`. |

---

### 10.1 A note on priority, after seeing production's size

The production database is 58 MB with about a thousand appointments and 120 users (2026-10-27). At this size none of the N+1 or unbounded-list findings is felt by users yet; they are correctness and future-proofing work, not fire-fighting. The order of the phases already reflects this (security, then safety net, then environments, then structure, performance last), and the latency baseline from 1.11 will say when a performance item is actually worth its risk. What the small size does change: the dev restore drill and an anonymised seed from a production dump are cheap, and the Postgres connection budget is not a concern.

## 11. First ten working days (concrete sequence)

| Day | What | Who |
|---|---|---|
| 1 | You: Railway steps 1–3 (record variables, check the volume, rotate the Daily key). Me: PRs 0.1, 0.3, 0.4, 0.12 ready for review. | both |
| 2 | Review and merge 0.1, 0.3, 0.4 one at a time, watching the Railway deploy log and the apps after each. Run the `patient@test.com` disable SQL. | you |
| 3 | PRs 0.2 step 1 (signed URLs, log-only), 0.8 step 1 (upload policy, log-only) and 0.10 (payment idempotency with the data fix) ready. The baseline queries are already done (2026-10-27). | me |
| 4 | Merge 0.2 step 1, 0.8 step 1 and 0.10. Verify documents open in both apps and a double-tap on "Pay" returns the same checkout; check the 5 cleaned appointments in the admin panel. PRs 0.5–0.7, 0.9, 0.11 ready. | both |
| 5 | Merge the rest of Phase 0 except the two enforcement steps. (0.7 is verified on dev once dev exists, day 8.) You export the non-secret production variables for PR 1.10 (3.6). | you |
| 12 | After a week of clean logs: merge 0.2 step 2 and 0.8 step 2 (enforce). Phase 0 done. | you |
| 6 | PRs 1.1–1.6 (CI, context test, snapshot, profiles, info endpoint) and 1.11–1.12 (timing log, lint, coverage ratchet, route coverage test, schema baseline, gitleaks, dependency review). First CI run on `main`; first latency report gives the speed baseline. | me |
| 7 | You: Railway step 4 (create `development`, new Postgres, variables with `SPRING_PROFILES_ACTIVE=dev`), step 6 (domain). Me: PR 1.7 dev seed. | both |
| 8 | Merge 1.7; development deploys from `main` and seeds itself. Patient and doctor apps: dev URL in debug builds (3.4). | both |
| 9 | PRs 1.8 (release workflow, Dependabot) and 1.10 (configuration consolidation, verified on dev against the golden tests). You: GitHub rulesets and the `production` environment with yourself as reviewer (3.3). | both |
| 10 | Cut-over 1.9: `production` branch created at the live commit, Railway branches switched, Wait for CI on, first manual release (which carries 1.10). After it is live, delete the non-secret variables from both Railway environments. From here every change goes main → dev → click → prod. | both |

Phases 2 to 6 follow at the pace features allow, in the order given.

---

## 12. Open questions for you

Answer these when you can; nothing in Phase 0 or the first half of Phase 1 depends on them.

- **Q1. Answered 2026-10-27:** `shifa-backend-qa` (environment `QA`) and `shifa-backend-sheroziy` exist but contain no services. Nothing uses the `qa` profile; it is deleted in 1.5. Proposal: delete both empty projects on your "go".
- **Q2. Answered 2026-10-27:** a 2 GB volume is mounted at `/app/storage` with daily backups. No action.
- **Q3. Answered 2026-10-27: Railway only.** Done 2026-10-29 on the repo side: runbooks, `cloudbuild.yaml`, the Cloud SQL dependency and code path, and the `*.run.app` CORS origin removed; the owner deleted the Cloud Build trigger. Still to delete in Google Cloud: the `shifa` Cloud Run service in europe-west1 and any Cloud SQL instance or Artifact Registry repository the exploration created.
- **Q4. Answered 2026-10-27: share the production Firebase project now; separate in Phase 2, once development is stable.** The backend uses Firebase Admin for two things only: verifying the phone-OTP ID tokens the doctor app sends at login and password reset, and sending FCM pushes. A development backend sharing the project can only push to tokens registered in the development database, which come from debug builds, so real users are not reachable from dev. Separation is worth it later for three reasons: test phone numbers for OTP without SMS cost, push testing that cannot touch a tester's production install on the same phone, and a dev service-account key that can be revoked without affecting production. The separation steps are in `PREPARATION_CHECKLIST.md` 3.3.
- **Q5. Answered 2026-10-27: add `api.shifa.uz` (production) and `api-dev.shifa.uz` (development), keep the Railway hostnames alive until every app build has moved.** Today's hostnames: backend `https://shifa-doc-backend-mvp-production.up.railway.app` (hardcoded in the patient app and in the doctor app's build), doctor web `https://doctor.shifa.uz`, admin panel `https://admin.shifa.uz`, doctor web's Firebase hosting `https://shifa-doctor-staging.web.app`. Sequence:
  1. **Done 2026-10-28:** `api.shifa.uz` added to the production service; Railway's CNAME target is `7vnksdyz.up.railway.app`. **Railway requires two DNS records per custom domain:** the CNAME and a TXT ownership record (`_railway-verify.<label>` = `railway-verify=<token>`); without the TXT the certificate stays in "validating ownership" forever. The generated `*.up.railway.app` domain stays; both serve the same service.
  2. **Done 2026-10-28:** both records in the `shifa.uz` zone at ahost.cloud (cPanel Zone Editor). Same later for `api-dev` → the development service. Note: the zone lists a dead nameserver `ns3.ahost.cloud`, locked in cPanel; a ticket to ahost is the only way to remove it.
  3. **Verified 2026-10-28:** certificate `CN=api.shifa.uz` valid to 2027-01-26; liveness 200, public doctor list 200, CORS preflight from `doctor.shifa.uz` 200, protected route without token behaves exactly as on the old host. Logins from the apps against the new host happen with their base-URL branches (step 4).
  4. Apps: patient `ApiConfig.BASE_URL` → `https://api.shifa.uz/api/` in release 1.2.4; doctor web `API_BASE_URL` → `https://api.shifa.uz` in its workflows. Each on a branch named `chore/api-domain` in its repo.
  5. Backend, only after step 3: `app.publicBaseUrl` → `https://api.shifa.uz` in `application-prod.yml` (PR 1.10 or a one-line follow-up). New file URLs then carry the new host; URLs already stored keep the old host and keep working because the old host stays. Webhook URLs registered at Stripe, Click and Daily keep pointing at the old host; they are moved one by one at leisure, never removed.
  6. The old hostname is never deleted while a store build references it. Per the 0.1 contract, hosts are only ever added.
- **Q6.** Spring Boot 4 timing: right after Phase 2 (my recommendation, since 3.x has no security patches) or after Phase 5 when the code is smaller per module?
- **Q7. Answered: you are the only required reviewer.**
- **Q8. Answered: Railway's own object storage** (buckets are a Railway resource; 2.7 uses reference variables, so no bucket credential is typed anywhere).
- **Q9. Answered:** see 0.1. Patient KMP 1.2.3; doctor web `1.0.0+3` = `main`.
- **Q10. Answered: keep it.** S3 is accepted risk; PR 0.5 only adds a result limit.
- **Q11.** Data retention: should anything ever be deleted or archived (read notifications older than a year, expired OTP rows, AI usage counters, rolled payment events)? The plan deletes nothing until you decide; the scalability scorecard only records growth.
- **Q12. Answered: 30 days for doctors, 365 for patients, for now.** `CLAUDE.md` is corrected in the next docs PR.
- **Backups:** you declined the weekly 90-day schedule for now; daily with 6-day retention stays.

---

## 13. Scorecard: how the work is rated

Six criteria, each with a measurable baseline from the audit, a target, and the mechanism that measures it. Every PR description states which criteria it moves and the measured delta; the release notes of each production release aggregate them. A criterion with no measurement cannot be rated, so where the baseline is "unknown" the first task is to measure (PR 1.11).

### 13.1 Efficiency (resources and cost per request)

| Metric | Baseline (2026-10-25) | Target | Measured by | Phases |
|---|---|---|---|---|
| JVM heap and container memory | `-Xmx1536m`, 300 Tomcat threads (~1 MB stack each), Hikari 20 | `-Xmx1024m`, 100 threads, Hikari 15, container p95 memory under 1.5 GB | Railway memory graph; `jvm.memory.used` in the daily metrics summary (1.11) | 2.8 |
| Queries per request on hot lists | Unknown; N+1 confirmed on 5 endpoints (P5) | Doctor list, conversations, notifications, appointments list: constant query count regardless of row count | Hibernate statistics on dev (`generate_statistics`) in the context test, asserted per golden endpoint | 6 (P4, P5) |
| Build and deploy time | Nixpacks build, tests skipped, ~180 s health timeout | CI under 8 min; Railway image build cached; cold start under 60 s | GitHub Actions duration; Railway deploy log | 1.2, 2.1, 2.4 |
| AI spend per request | Token budgets configured but 0 (off) | Budgets set per provider; tokens per request logged | `ai.providers.*.daily-token-budget` and the existing usage counter | 2 (config) |
| Log volume | File logging in container plus stdout, DEBUG for security in default profile | stdout only, JSON, INFO in prod | Railway log volume | 2.3 |

### 13.2 Speed (latency as the apps see it)

| Metric | Baseline | Target | Measured by | Phases |
|---|---|---|---|---|
| p95 latency per endpoint | **Unknown: nothing measures it today** | p95 under 300 ms for list endpoints, under 150 ms for single-entity reads, excluding AI streams | `RequestTimingFilter` (1.11) logs one JSON line per request (`route`, `status`, `ms`, no PII); `scripts/latency-report.sh` computes p50/p95 per route from Railway logs. Weekly on dev and prod. | 1.11 then 6 |
| Cold start to healthy | ~100 s+ (comments in `railway.toml`) | under 60 s | Railway deploy log timestamps | 2.1, 2.4, 2.8 |
| Public doctor search | Loads all doctors, in-memory paging | single DB query, DB-side paging; p95 under 200 ms | timing log; golden test proves identical output | 6 (P5) |
| Reminder scheduler cycle | one query per appointment per minute | one batched query per step | query count in dev statistics | 5 (`notifications`), 6 |

### 13.3 Scalability (what breaks when load or replicas grow)

| Metric | Baseline | Target | Measured by | Phases |
|---|---|---|---|---|
| Safe replica count | **1.** Two instances (which happens on every deploy) can double-send SMS and pushes, disagree on revoked sessions for 2 min, and lose uploads written to the other disk | 2 replicas run for a full day on dev with zero duplicate notifications, zero 404 files; `N` replicas blocked only by the Postgres connection budget | Dev soak with replicas = 2 after Phase 2; duplicate check on `notifications` and SMS billing | 2.5, 2.6, 2.7 |
| Postgres connection budget | 20 per instance, unbudgeted | `replicas × pool ≤ max_connections − 10`; documented in `application-prod.yml` | `pg_stat_activity` on dev during the soak | 2.8 |
| Unbounded responses | 7 `findAll`, 5 unbounded lists | 0 unbounded responses on authenticated endpoints (opt-in paging per the 0.1 contract) | grep count in CI (a unit test asserts the allow-list of remaining `findAll` is empty) | 6 (P6) |
| Threads per SSE stream | 1 raw thread each, no timeout | bounded executor, 5 min timeout | thread count under 50 concurrent streams on dev | 6 (P7) |
| Data growth | **Baseline 2026-10-27:** database 58 MB; largest tables `notifications` ~3 100 rows, `task_check_ins` ~1 100, `appointments` ~980, `patient_profiles` ~580, `patient_documents` 825, `users` 120. (`pg_stat` estimates lag; `patient_documents` showed 16.) No retention policy. | Indexes for the top 5 growth tables; retention is a product decision (Q11) | `pg_stat_user_tables` snapshot in the weekly report | 6 (indexes), Q11 |
| Postgres connections | `max_connections` 500, 11 in use (2026-10-27) | the budget rule still documented, but this is not a constraint at today's size | `pg_stat_activity` | 2.8 |

### 13.4 Quality (code and test health)

| Metric | Baseline | Target | Measured by | Phases |
|---|---|---|---|---|
| CI | none | every PR: build, lint, tests, context test, snapshot tests, ArchUnit | GitHub required check | 1 |
| Lint | none | ktlint + detekt, zero warnings on changed files | CI | 1.11 |
| Test coverage | unknown (no JaCoCo); 335 tests; zero tests for security, repositories, schedulers, uploads | JaCoCo report in CI with a **ratchet**: line coverage may not drop below the committed threshold, which is raised whenever it grows | `jacocoTestCoverageVerification` | 1.11 |
| Flaky tests | 1 date-dependent failure, 4 skipped | 0 flaky, 0 skipped | CI history | 1.1, 1.3 |
| Controllers injecting repositories | 41 of 64 (156 params) | 0 | ArchUnit rule | 5 |
| Inline DTOs in controllers | 193 | 0 (all in `api/dto`) | ArchUnit rule | 5 |
| Files over 600 lines | 18 | 0 in `api` and `application`; `domain` and generated code exempt | detekt `LargeClass`/`LongMethod` | 5 |
| Duplicate helpers | 9 patient resolvers, 3 haversine, 3 `parseJsonList`, 4 slugify, 24 URL builders, 3 rate limiters | 1 of each in `shared/` | grep audit repeated per phase, recorded in the PR | 5 |
| Request validation | 49 of 119 `@RequestBody` with `@Valid` | 119 of 119 | ArchUnit rule on controller parameters | 0, 5 |
| Transactions | 86 of 282 `readOnly`; 96 write paths without `@Transactional` | every `application` public method transactional; reads `readOnly` | ArchUnit rule | 5, 6 (P3) |
| Architecture rules | none enforced | `api → application → domain`, features only via `application`, no cycles | ArchUnit | 5 |
| Documentation drift | `CLAUDE.md` and `README.md` counts and token lifetimes wrong | regenerated per phase; API contract is `docs/api/openapi.json` | PR checklist | every phase |

### 13.5 Cyber safety

| Metric | Baseline | Target | Measured by | Phases |
|---|---|---|---|---|
| Open findings S1–S10 | 10 open | 0 open, or "accepted" with your sign-off (Q10) | this document's table 1.4 updated per PR | 0 |
| Route authorization coverage | deny-by-default exists, but public surfaces include `/api/test/**`, patient files, api-docs | every path in `openapi.json` matched by an explicit rule; public allow-list reviewed and committed | **route coverage test** (1.11): enumerates the OpenAPI paths and asserts each matches a rule in `SecurityConfig` other than `anyRequest()`; the public list is a committed file | 0, 1.11 |
| Secrets | 1 committed API key; ~90 env placeholders; logs with PII tracked in git | 13 secrets, only in Railway; zero in repo or GitHub | `gitleaks` in CI on every PR and on history once (1.12); 3.6 inventory | 0, 1.10, 1.12 |
| Dependency vulnerabilities | Spring Boot 3.3.4 (unsupported); no scanning | supported Boot line; Dependabot alerts at zero high/critical; `dependency-review-action` blocks PRs that add a known-vulnerable dependency | GitHub Dependabot alerts; CI | 1.8, 1.12, 3 |
| PII in logs | phones, emails, OTP codes, DB username, LLM bodies | none | a Logback filter masks phone/email patterns as a safety net; unit test on the filter; grep audit per phase | 0.9, 2.3 |
| Webhook and callback integrity | Click accepts blank secret | all webhooks reject blank secrets, constant-time compare, unit-tested | tests in `payments` | 0.6 |
| Rate limiting | keyed on the first `X-Forwarded-For` entry; per instance | unchanged key: Railway's CDN rewrites the header, so the first entry is the real client (verified 2026-10-28); hop count is a property for the day that changes; per instance is accepted at 1 replica (Q: shared store later) | forged-header test on dev, all variants stripped | 0.7 |
| Session security | 365-day patient tokens, 30-day doctor tokens (docs say 12 h) | lifetimes confirmed with you and documented; revocation window 30 s across instances | `application.yml` + 2.6 | 2.6, Q12 |
| Admin accountability | no audit log of admin actions | `admin_audit_log` (who, what, target, when) written by the `admin` feature's `application` layer | table exists and the admin golden tests show entries | 5 (`admin`) |
| Backups | unknown | Railway Postgres backups on and verified by a restore into the dev environment once per quarter; this restore is also the completeness drill (13.6) | Railway → Postgres → Backups; restore log | 3.2 action, quarterly |

### 13.6 Completeness: no endpoint and no table goes missing

| Guard | Mechanism |
|---|---|
| **Endpoint inventory** | `docs/api/openapi.json` is the committed list of every path, method, parameter and response field. CI fails on any difference. A removal is only possible by editing the file in the PR, where the PR template requires: the app builds checked, the date the field/endpoint was deprecated, and your explicit approval. Baseline: about 320 handlers across 60 controllers; the exact count is fixed when the snapshot is first committed (1.4). |
| **Schema inventory** | `docs/db/schema-baseline.json`: every table, column, type, nullability, index and constraint as read from `information_schema` on the CI Postgres after Flyway runs (1.11). CI fails on any difference. Removals follow the same explicit-approval rule. Baseline: 87 live tables (89 created, 2 dropped by past migrations), 70 of them mapped as JPA entities, the rest used through JDBC or seed data. |
| **Entity–schema parity** | the context test runs with `ddl-auto: validate` (1.3): an entity that no longer matches a table fails CI, which is the outage the old `qa` setup could not catch. |
| **Migration parity between environments** | `/actuator/info` exposes the latest applied Flyway version (1.6); the release workflow refuses to release unless development's version equals the repo's latest, and a post-release check on production reads the same value. |
| **Expand-then-contract** | ground rule 2: no `DROP` or `RENAME` in the same release as the code that stops using it; the schema-baseline diff makes every drop visible in review. |
| **Data, not just schema** | before any migration that rewrites or moves rows (0.10 dedupe, Phase 5 patient-profile merge, 2.7 file copy), a one-off query records row counts and checksums before and after, and the PR includes both numbers. |
| **Restore drill** | quarterly restore of the production backup into development (13.5, backups) proves the backup is complete and gives dev realistic data once (anonymised by the seed scripts). |
| **Production keeps its database** | ground rule 1: the production Postgres is never copied, moved or recreated. |

### 13.7 Rating a PR, a release, a phase

- **PR:** the description lists the criteria it touches, the before/after numbers for each (from the mechanisms above), and the completeness guards it changed (snapshot, baseline). A PR that moves no criterion is a pure move and says so.
- **Independent review before you see it:** every PR is reviewed by OpenAI Codex (`codex exec --sandbox read-only`, model from `~/.codex/config.toml`, medium reasoning effort, with the compatibility constraints in the prompt) and the findings are fixed or answered before the summary reaches you. Rounds and verdicts go in the PR description. PR 0.1's first round found an account-selection bypass the author had missed, which is the point of a second model.
- **Release (production):** the release notes aggregate the PRs' deltas; the workflow attaches the latency report and the Dependabot alert count.
- **Phase:** done when every row of its "Done when" line holds and the scorecard tables above are updated with the measured values. Each phase ends with a short review against the six criteria, written into this document.

---

## 14. What this plan deliberately does not do

- It does not rewrite the backend, change the framework, or split into services. The problems are in structure and hygiene, not in the stack.
- It does not add a message broker, Redis or a second database. ShedLock and Caffeine cover the two-instance window; a shared store becomes worth it only when the service runs more than one replica on purpose.
- It does not migrate production data. The production Postgres stays where it is, with the same `DATABASE_URL`, and only ever receives additive Flyway migrations that were first applied on development.
