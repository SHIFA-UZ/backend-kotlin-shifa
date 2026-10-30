# Plan audit, 2026-10-30

Every row of `ARCHITECTURE_ROADMAP.md` was checked against the code on branch `refactor/phase6` (main `6abbf3e` plus
the Phase 6 commits). "Claimed" is what the roadmap tables, the "Status 2026-10-29" paragraphs or
`OVERNIGHT_2026-10-29.md` say; "Verified" is what the repository shows. Settings that live outside the repository
(Railway service settings, GitHub rulesets, "Wait for CI") could not be checked from here and are marked so.

Legend: DONE, PARTIAL (works, but a clause of the row is not implemented), NOT DONE, UNVERIFIABLE.

## 1. What the audit changes in the plan

Three findings matter for the release and should be read first.

1. **Golden response tests were never built.** Roadmap 0.1, 1.4, 1.10, 3.4, P4, P5 and the scorecard say behaviour
   changes are "proven by the golden tests"; the only automated contract check is the OpenAPI snapshot in
   `ApplicationContextTest`. The Phase 6 branch proved its rewrites with a manual old-jar/new-jar comparison on the
   local database (46 responses). Either the golden tests get built (a `@SpringBootTest` that calls the seeded
   endpoints and compares committed JSON with volatile fields masked) or the plan should stop referring to them.
2. **P4 (lazy relations) cannot be done as written**, see the Phase 6 P4 finding in the status section: 64 of the 70
   entity classes are final, so Hibernate loads `LAZY` to-one relations eagerly, and controllers read detached entities
   through doors. P4 moves behind Phase 5 step 3 proper.
3. **Several scorecard mechanisms (section 13) do not exist**: route-authorization coverage test, schema baseline,
   PR template, PII log-masking filter, `findAll` allow-list test, ktlint, Flyway version in `/actuator/info`,
   `dependency-review-action` (declined, needs GitHub Advanced Security). They are listed below as open.

Two Phase 0 rows are not complete as written: 0.1 still lists `create-patient-for-doctor` in `JwtAuthFilter.shouldNotFilter`,
and 0.8's "UUID file names" exist only for chat attachments.

## 2. Phase 0

| Item | Claimed | Verified | Evidence | Discrepancy |
|---|---|---|---|---|
| 0.1 OTP on create-patient-for-doctor | done | PARTIAL | `AuthController.kt` requires e-mail OTP or `smsOtp`; `AuthCreatePatientForDoctorOtpTest` | path still in `JwtAuthFilter.shouldNotFilter` (`JwtAuthFilter.kt:38`) |
| 0.2 Signed file URLs, step 1 | done, flip pending | DONE | `FileUrlSigner`, `SignedUrlFilter` (403 only when `app.file-url-enforce`), `application.yml:151 false` | key is `SHA-256("shifa-file-url:" + secret)`, not HMAC as the row says |
| 0.3 TestDataController removed | done | DONE | no `/api/test` anywhere | `V16/V17` still seed `patient@test.com` on fresh databases |
| 0.4 secret fallbacks, startup checks | done | DONE | `application-prod.yml` no defaults; `StartupSecretChecks`; `ConfigPolicyTest` | none |
| 0.5 chat search limit | done | DONE | `MessageService.SEARCH_RESULT_LIMIT = 50` per side | up to 100 rows (50 doctors + 50 patients), not 50 |
| 0.6 Click blank secret, constant-time compare | done | DONE | `ClickShopCallbackService.kt:296,301` | none |
| 0.7 trusted proxy hops | done, kept 0 | DONE | `RateLimitFilter.kt:27`, both yml `0` | none |
| 0.8 UploadPolicy, step 1 | done, flip pending | PARTIAL | `shared/UploadPolicy.kt`, 10 upload sites, `upload-policy-enforce: false` | UUID names only for chat attachments; documents and photos keep guessable names until 0.2 enforces |
| 0.9 PII out of logs | done | DONE | no `println`; OTP codes gated by `app.log-otp-codes`; LLM bodies at DEBUG | base `com.shifa: DEBUG` is overridden to INFO only by the prod profile |
| 0.10 checkout idempotency | done | DONE | `V118` partial unique index, `PaymentService.createConsultationCheckout`, `OpenCheckoutPolicyTest` | index not `CONCURRENTLY`; no row-count log |
| 0.11 springdoc off in prod, reviews authenticated | done | DONE | `application-prod.yml:50-54`, `SecurityConfig.kt:79` | `/swagger-ui/**` stays `permitAll` (endpoints disabled, harmless) |
| 0.12 repo hygiene | done | DONE | `git ls-files` clean, `.gitignore` | `.gitignore` covers root `*.sql` only |

## 3. Phase 1

| Item | Claimed | Verified | Evidence | Discrepancy |
|---|---|---|---|---|
| 1.1 fixed clock in flaky test | done | DONE | `DoctorMorningToolService` takes `Clock` | none |
| 1.2 `ci.yml` | done | DONE | pgvector pg17 service, Gradle action, `./gradlew build` | triggers `[main]` only, image pg17 (roadmap says pg16, `[main, production]`) |
| 1.3 context test with `ddl-auto=validate` | done | DONE | `ApplicationContextTest` | two `assumeTrue` tests exist, not four |
| 1.4 OpenAPI snapshot + golden tests | done | PARTIAL | snapshot yes | **no golden response tests** |
| 1.5 profiles, local defaults | done | DONE | `application-dev.yml`, `spring.profiles.default: local` | dev logs INFO, not DEBUG as the row says |
| 1.6 `/actuator/info` commit | done | DONE | `info.commit`, `permitAll` | none |
| 1.7 dev seed | done | DONE | `devseed/DevSeedRunner` (Kotlin runner, idempotent) | roadmap describes an `R__dev_seed.sql`; seed doctors had no schedule validity period until this branch |
| 1.8 release workflow, Dependabot, CODEOWNERS | done | DONE | `.github/` | none |
| 1.9 cut-over | done except Wait-for-CI, first release | UNVERIFIABLE | `origin/production` exists at `3201e80` | no release yet (by decision) |
| 1.10 config consolidation | done | DONE | `Application.kt` 31 lines, `ConfigPolicyTest` | "proved by golden tests" cannot hold |
| 1.11 measurement and gates | done | PARTIAL | `RequestTimingFilter`, `scripts/latency-report.sh`, `RuntimeMetricsLogger` (hourly), detekt + baseline, JaCoCo floor 0.27 | missing: ktlint, route-authorization coverage test, schema baseline, Flyway version in `/actuator/info` |
| 1.12 supply-chain scanning | done | PARTIAL | `security.yml` gitleaks (PR, push, manual history) | `dependency-review-action` declined (needs GHAS); result of the history scan not recorded |
| 1.13 Railway settings off `railway.toml` | dev done, prod pending | PARTIAL | config files deleted; Dockerfile | production settings before 2026-12-01 (your side); Dockerfile builder image `gradle:8.5` vs wrapper 8.14.3 |

## 4. Phase 2

| Item | Claimed | Verified | Evidence | Discrepancy |
|---|---|---|---|---|
| 2.1 Dockerfile builder | partly | PARTIAL | multi-stage, `bootJar` only, runs as root, fixed `-Xmx1024m` | tests run in CI, not in the image (deliberate); no `USER`; builder base 8.5 |
| 2.2 datasource via reference variables | done | DONE | `application-prod.yml:8-10`; Firebase from env | none |
| 2.3 logs: stdout, request id, JSON | partly | PARTIAL | stdout only, `RequestIdFilter`, request id in error bodies | no JSON log encoder |
| 2.4 readiness health, graceful shutdown | open | PARTIAL | `server.shutdown: graceful` 30 s | readiness group has no DB indicator (`management.endpoint.health.group.readiness.include: readinessState,db` missing); Railway health path unchanged |
| 2.5 ShedLock, reminders outside tx | done | PARTIAL | `V119`, 6 of 7 jobs locked (`RuntimeMetricsLogger` unlocked, log-only) | SMS and FCM reminder sends still run inside `@Transactional` methods; no `sent_at`/outbox |
| 2.6 session cache 30 s, bucket eviction | done | DONE | `UserSessionValidationCache`, Caffeine `expireAfterAccess(1h)` | none |
| 2.7 object storage | dev done | PARTIAL (by design) | `FileStorage` local/S3/fallback, migration endpoint, dev bucket | production steps open; size-verified, not checksum |
| 2.8 Tomcat 100, Hikari 15/2 | done | DONE | `application-prod.yml:13-14,44` | none |

## 5. Phase 3

| Step | Claimed | Verified | Evidence | Discrepancy |
|---|---|---|---|---|
| 3.1 Boot 3.5.16 | done | DONE | `build.gradle.kts:3` | none |
| 3.2 Kotlin 2.2.21 | done | DONE | `build.gradle.kts:7` | no `allOpen` for `@Entity` (see P4 finding) |
| 3.3 Gradle 8.14, then 9.x, config cache | done | PARTIAL | wrapper 8.14.3 | Gradle 9 and configuration cache not done |
| 3.4 Boot 4.1 | open | NOT DONE | | planned |
| 3.5 Kotlin 2.4, libraries current | open | NOT DONE | stripe-java 30.1.0, firebase-admin 9.4.0, OkHttp 4.12.0 unchanged | planned |
| 3.6 remove Cloud SQL / Cloud Run | done | DONE | no socket factory, no `cloudbuild.yaml` | none |

## 6. Phase 4 (patient app list)

| # | Claimed | Verified | Evidence | Discrepancy |
|---|---|---|---|---|
| 1 push i18n | open | NOT DONE | only `DoctorPushI18n`, `PatientPaymentPushI18n` | planned |
| 2 tokens per device | open | NOT DONE | | planned |
| 3 staging backend | done | DONE | development environment, seed | none |
| 4 error codes | done | PARTIAL | `shared/ErrorCode.kt` (16 codes), `ErrorBodies`, filters emit the same shape | no `ApiException`/`fields`, codes not in the OpenAPI snapshot; invalid-signature tokens get Spring's 403 body (see §9) |
| 5 `uploadedByPatient`, `creatorType` | done | DONE | `PatientDocumentDto.kt:29-31` | `creatorType` can be `UNKNOWN` |
| 6 absolute URLs | helper done | PARTIAL | `shared/PublicUrls` in 16 files | still raw: `MessageService.kt:320,337`, `AuthController` check-existing-patient, `CarePartnershipService.kt:96` |
| 7 cancel as status, patient push | done | DONE | `DELETE /patients/me/appointments/{id}`, push after commit | push text English only |
| 8 SMS account deletion | done | PARTIAL | `/confirm` accepts `smsOtp`; status flips only on confirm | `request` does not send the code; OTP tied to phone + purpose, not `challengeId` |
| 9 doctor search cleanups | done | PARTIAL | strict radius when `radiusKm` sent | `parseJsonList` still returns `null`, three copies |
| 10 Google sign-in | later | NOT DONE | | product decision |
| 11 Shifa AI codes, history | open | NOT DONE | generic exceptions end the stream without a coded event | planned |
| 12 payments | done | PARTIAL | `failureReason`, `shifa://payment/return`, one open checkout | no patient push on PAID/FAILED |
| 13 live-visit fields | done | PARTIAL | fields on both DTOs, attach-after-booking, reschedule closes checkout | mapped in two controllers, not one mapper |
| 14 task entries | done | PARTIAL | `doctorId`, `doctorName`, `appointmentId`, check-in delete within 24 h | window not enforced on submit |
| 15 `appointmentId` on document | done | DONE | `PatientDocumentDto.kt:33` | none |
| Q answers into the app reference | planned | NOT DONE | `apps-flutter-patient/docs/kmp-spec/15-api-reference.md` has no such section | see `docs/apps/patient-app.md` |

## 7. Phase 5 and Phase 6

| Item | Claimed | Verified | Evidence |
|---|---|---|---|
| 49 doors, DTOs in `api/dto`, 25 same-name DTOs nested | status | DONE | grep |
| ArchUnit store sizes (api→infra 35, domain 0, shared 184, auth 17, doctors 7, clinics 7, admin 7, others 0, P3 358) | status | DONE, all match | `src/test/resources/archunit-store` |
| P3 counts "93 read, 11 write, 30 internal" | status | 94 / 11 / 28 in the commit diff | `git show e7a3821` |
| P5 batch loading, P6 `/page`, caches | status | DONE | `PublicDoctorCatalog`, `Pages`, `CacheConfig` |
| P4 finding "66 of 72 entities" | status | 64 of 70 `@Entity` classes are final | grep |
| V120 indexes "CONCURRENTLY" (Phase 6 table) | table | plain `CREATE INDEX IF NOT EXISTS`, justified in the migration | `V120__list_indexes.sql` |
| Step 3 proper, `EXCLUDE` constraint, auth split, AI split, `admin_audit_log` | open | NOT DONE, as the status says | |

## 8. Sections 0 to 3 and 11 to 13 (ground rules, "today", environments, scorecard)

- 0.1 "enforced, not just promised": only the OpenAPI snapshot and the `ddl-auto: validate` context test enforce the
  contract; golden tests, PR template and schema baseline do not exist.
- 0.2 removals: everything listed is removed; also removed but unlisted: `public-storage/docker-compose-two.yml`,
  `public-storage/nginx.conf`.
- Section 1 ("where the backend stands today") is a 2026-10-27 snapshot and is stale throughout: 556 main files,
  64 controllers, 153 `@Service`, 67 repositories, 70 entities, 121 migrations, 88 test files, three workflows,
  Dependabot; the technical layering, the `DATABASE_URL` parser, file logging and the junk files are gone. Still true
  from that list: 49 of 120 `@RequestBody` parameters carry `@Valid`; 18 files over 600 lines
  (`DoctorMorningToolService` 2184, `AuthController` 1488); 2 haversine, 3 `parseJsonList`, 4 slugify copies.
- 3.5: `ci.yml` runs on `main` pushes and PRs only (no `production` build), pg17 service; the release workflow has the
  dev URL hardcoded (no `DEV_BASE_URL` variable needed); the release does not check the Flyway version (13.6).
- 3.2 item 4 still says `SPRING_PROFILES_ACTIVE=dev` and lists variables that are yml literals now; the profile is
  `prod,dev`.
- 3.6: secrets have no defaults in `application-prod.yml`; `application.yml` keeps empty-string defaults for local runs.
  `GOOGLE_MAPS_API_KEY` stays a variable (status says so; the 3.6 table lists it under "moves to yml").
- Open questions: Q1 (empty Railway projects), Q3 cloud side, Q4 Firebase separation, Q5 step 5 (`publicBaseUrl` to
  `api.shifa.uz` in prod), Q6 Boot 4 timing, Q11 retention are open. Q12 was answered (30 days) and `CLAUDE.md` now says
  so. Q4 points to `PREPARATION_CHECKLIST.md`, which lives in `docs/`.
- Scorecard mechanisms present: `RequestTimingFilter` + `scripts/latency-report.sh`, `RuntimeMetricsLogger`, JaCoCo
  ratchet (0.27, measured 0.283), ArchUnit rules, `ddl-auto: validate`, session revocation 30 s.
  Absent: route coverage test, schema baseline, PR template, PII log filter, `findAll` allow-list test, ktlint,
  detekt `LargeClass`/`LongMethod` (inactive), `@Valid` ArchUnit rule, inline-DTO ArchUnit rule,
  `dependency-review-action`, `admin_audit_log`, Flyway version in `/actuator/info` and in the release gate.
- Workspace `CLAUDE.md` "Known problems" (Daily key fallback, tracked logs) are fixed; its "Today" description of the
  backend layout is stale. The backend `CLAUDE.md` was corrected on this branch (versions, roles, filters, routes,
  Hikari, deployment, schedulers, file counts, token lifetime).

## 9. Findings from running the apps against the backend

Patient app (Kotlin Multiplatform, debug build on an Android emulator, backend from this branch on localhost):
login, home, bookings, documents, messages (list, detail, mark-read), notifications, doctor profile, reviews, slot
picker and a completed booking all worked; the backend logged no error for any app call. Two findings:

- **A token with an invalid signature gets 403, not 401.** `JwtAuthFilter` answers 401 for expired or revoked sessions
  but lets a token that fails to parse through unauthenticated, so Spring returns its default 403 body
  (`{"status":403,"error":"Forbidden"}`), not the API error shape. Both apps treat 401 as "log out"; after a
  `JWT_SECRET` rotation every signed-in user would therefore see "Something went wrong" on every screen instead of being
  logged out. Backend fix: return 401 with the API body for an unparsable token (`JwtAuthFilter`, the `runCatching`
  failure branch). Roadmap 3.6 says rotating `JWT_SECRET` logs every user out; today it does not.
  **Fixed in the cleanup PR after Phase 6**: `SecurityConfig` now has an `ApiAuthenticationEntryPoint`, so every
  unauthenticated request to a protected route is a 401 with the API body (`TOKEN_INVALID`, `SESSION_EXPIRED` or
  `UNAUTHORIZED` without a token); public routes ignore a token that no longer parses. `JwtAuthFilterTest` and
  `ApplicationContextTest` pin it.
- **Seed doctors had no bookable slots** on development and locally: weekly rules exist but `ScheduleValidityService`
  requires a validity period. Fixed on this branch (`DevSeedRunner` adds one per seed doctor, also on already seeded
  environments), so booking can be tested on development after the next deploy.

Doctor web app (Flutter): Flutter is not installed on this machine (the `PATH` entry in `.zshrc` points to a folder
that no longer exists), so it was not run. Its 186 API paths were checked against the OpenAPI snapshot: all exist.
See `docs/apps/doctor-app.md`.

## 10. Recommended plan corrections

1. Replace every "proven by the golden tests" with the actual mechanism (OpenAPI snapshot, context test, the
   old-jar/new-jar comparison), or build the golden tests as Phase 1.4 intended.
2. Mark the absent scorecard mechanisms as open rows with a phase, or drop them.
3. Fix the Phase 0 leftovers in a small PR: remove `create-patient-for-doctor` from `shouldNotFilter`; 401 for
   unparsable tokens; `parseJsonList` → `emptyList()` (item 9); the three raw URL sites (item 6).
4. Re-sequence P4 as described in the status paragraph (step 3 proper, then `allOpen`, then `LAZY` per entity).
5. Add `management.endpoint.health.group.readiness.include: readinessState,db` before switching Railway to the readiness
   path (2.4).
6. Finish 2.5: move the reminder SMS/FCM sends out of the transaction (after-commit hook or outbox).
7. Update section 1 of the roadmap or label it as the 2026-10-27 snapshot; the current facts are in `README.md`.
