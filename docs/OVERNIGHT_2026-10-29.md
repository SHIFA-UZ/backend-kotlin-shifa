# Overnight run, 2026-10-28/29: what was done, what was left

Branch `chore/overnight-2026-10-29`, one commit per step, every step reviewed by Codex (gpt-6-astra) and
verified with the full test suite **including the Spring context boot against a local PostgreSQL 17 + pgvector**
(installed on this machine for the purpose, cluster in the session scratch folder on port 5433). All of it
deploys to **development** on merge; production is untouched (`production` branch still at `3201e80`).

Final state: 398 tests, 0 failures; detekt clean against its baseline; line coverage 28.3 % (floor 27 %).

## Done (in merge order of the commits)

| Step | Roadmap | What changed | Codex |
|---|---|---|---|
| Dev seed (merged in) | 1.7 | `DevSeedRunner`: clinic, 3 doctors, staff, 5 patients, 11 appointments, documents with real PDFs, tasks, notifications. Flag `app.dev-seed`, refused on a `prod` profile without `dev`. | SHIP (2 rounds) |
| Platform | 2.5, 2.3, 2.6, 2.8, P4 | ShedLock on the 6 jobs (V119); `X-Request-Id` + MDC pattern; Caffeine-evicted rate-limit buckets; prod Tomcat 100 / Hikari 15-2; stdout-only logs; graceful shutdown 30 s; Hibernate batch fetch 50. | SHIP |
| API additive 1 | 4 (5, 7, 9, 12, 14, 15) | `PatientDocumentDto.uploadedByPatient/creatorType/appointmentId`; `TaskDto.doctorId/doctorName`; `PaymentStatusDto.failureReason`; explicit `radiusKm` is strict; patient notified (in-app + push after commit) on self-cancel. | FIX FIRST → fixed |
| Indexes | Phase 6 | V120: `notifications(patient_id, created_at)`, `notifications(doctor_id, created_at)`, `messages(conversation_id, created_at)`. | SHIP |
| Boot 3.5 | 3.1 | Spring Boot 3.3.4 → **3.5.16**, springdoc 2.8.17, dependency-management 1.1.7; Page serialization mode pinned. OpenAPI snapshot: rendering only (3.1 nullable types), no path/field change. | SHIP |
| Gradle | 3.3 | Wrapper 8.10.2 → 8.14.3. | – |
| Kotlin | 3.2 | 2.0.21 → **2.2.21**. | – |
| API additive 2 | 4 (13), P7 | Live-visit fields on both appointment DTOs (`signatureRequested`, `alreadySigned`, `documentIds`, `notes`); `POST /api/patients/me/appointments/{id}/documents`; reschedule closes the old open checkout; SSE on a bounded executor (16 streams, 503 when full, 120 s stall timeout). | FIX FIRST ×2 → fixed |
| Tasks | 4 (14) | `remote_care_tasks.appointment_id` (V121), `CreateTaskRequest.appointmentId`, `DELETE /api/tasks/my-tasks/{taskId}/check-ins/{id}` undo within 24 h. | – (covered by the SSE round) |
| Error codes | 4 (4) | `code` + `requestId` in every error body, legacy keys byte-identical; filters' bodies too; 42 messages the patient app matches on are frozen by a test. | FIX FIRST → fixed |
| Quality gates | 1.11 | detekt 1.23.8 + baseline (480 legacy findings), JaCoCo floor 27 %, both in `./gradlew check` (CI). | SHIP |
| Platform 2 | 2.2 (part), 2.6, cache | Firebase temp-file write removed from `main()`; session cache 30 s; 60 s caches for professions and public clinics. | FIX FIRST → fixed |
| Payments | 4 (12) | Return pages link/redirect to `shifa://payment/return?paymentId=&ref=&status=` only when the checkout request carried `platform: "app"`. | FIX FIRST → fixed |
| Deletion | 4 (8) | Phone-only patients confirm deletion with an SMS code; no status flip before confirmation; `channels`/`maskedEmail` in the request response. Codex found that failed-attempt counters rolled back with the request: OTP `verify` methods and the challenge counter now commit in their own transaction (also fixes the pre-existing email path). | FIX FIRST → fixed |

Every API change is additive. `docs/api/openapi.json` was regenerated after each one; the diff per step is
listed in the commit messages (paths, schemas and fields added; nothing removed).

## Left, and why

| Item | Why not tonight | What it needs |
|---|---|---|
| 1.10 config consolidation | Needs the production variable values you export (3.6 actions). | Your export, then one PR. |
| 1.13 production half | Railway mutation on production. | Run the `serviceInstanceUpdate` mutation with environment id `cdcf775b-004f-46d4-8b3a-e0ba25b71d7e` before the first release (or before 2026-12-01). The dev half is done. |
| 2.1 Dockerfile hardening (non-root, JAVA_TOOL_OPTIONS) | No Docker here to build-test; non-root breaks volume writes unless the mount is chowned at start. | A deliberate change with a dev deploy to watch. |
| 2.2 DATABASE_URL parser removal | Needs the three `SPRING_DATASOURCE_*` reference variables in both Railway environments first. | Your variables, then one PR. |
| 2.3 JSON logs | Would change the `grep`-based log workflow and `scripts/latency-report.sh`; request id is in place. | Decide when a log search tool needs it. |
| 2.4 readiness health path | Railway setting. | After a cold-start measurement on dev. |
| 2.7 object storage | Provider choice (Railway storage vs Cloudflare R2). | Your decision. |
| 3.4 Spring Boot 4 | Too large to do unattended; Jackson 3 and Spring Security 7 change defaults. | Its own day, with the golden tests. |
| Phase 4 item 1 (push texts in the patient's language) | 35 types × 4 languages of copy, best taken from the app's `strings.xml`. | Content work, then one PR. |
| Phase 4 item 2 (push token per device) | New table + 40 call sites; belongs to the `notifications` move. | Phase 5 step 2. |
| Phase 5 feature moves | Large mechanical diffs; not something to merge from a night without a human look. | One feature per PR, starting with `shared/`. |
| P6 paging on List endpoints | A paged variant changes the response type; needs the apps to opt in. | Design the `page`/`size` sibling with the app teams. |
| Enforcement flips (0.2 step 2, 0.8 step 2) | Need the week of dev logs; earliest ~2026-11-04. | Check the dev log, then two one-line PRs. |

## Observations worth your attention

- **Hibernate 6.6** (via Boot 3.5) is stricter about merging detached entities with generated ids and about
  stale references after bulk deletes. Tests and the context boot pass; watch the dev log for
  `TransientObjectException` / optimistic-lock errors during the soak.
- **Railway ingress** puts `<client>, <CDN node>` into `X-Forwarded-For` and drops client-supplied values
  (verified 2026-10-28). The rate-limit key stays on the first entry; the dev overlay logs the chain on 429.
- OTP attempt counters on the SMS and email codes themselves are read-modify-write (pre-existing); the deletion
  challenge counter is now an atomic SQL increment. Under heavy concurrent guessing the code counters could
  admit a few extra attempts; the challenge counter cannot. Worth an atomic update there too (Phase 5 `auth/`).
- **detekt baseline entries are keyed by function signature.** The final `./gradlew build` check found the branch
  red: adding `returnPlatform` to the two checkout functions in `PaymentService` changed their signatures, so their
  four baseline entries (LongMethod, ThrowsCount) stopped matching. Fixed by regenerating the baseline
  (`./gradlew detektBaseline`, four lines changed). Rule for later steps: when a baselined function's signature
  changes, regenerate the baseline in the same commit, and run `build`, not only `test`, before pushing.
- **The first development deploy of this branch failed in the Docker build** (2026-10-29 06:23). The Dockerfile ran
  `gradle clean build -x test`, and `build` now includes detekt, whose rules and baseline live in `config/detekt/`,
  which the image never copies. Fixed by building the image with `gradle clean bootJar`: the image only needs the
  jar; tests, detekt and the coverage floor run in CI. Local verification of the gates must use `./gradlew build`,
  which is also what CI runs. Note the image still uses the `gradle:8.5-jdk21` base while the wrapper is 8.14.3;
  it compiles today, but pinning the image to the wrapper version is a cheap follow-up (2.1).
- The dev seed accounts (`seed.doctor1..3@shifa.dev`, `seed.patient1..3@shifa.dev`, `seed.staff@shifa.dev`,
  password `Dev12345!`) appear on development after the next deploy. They are not in production.

## Release checklist (unchanged, for when you are ready)

1. Apply the Railway production deploy settings (1.13 mutation above).
2. GitHub: environment `production` with required reviewer; ruleset on branch `production` allowing the workflow push.
3. Actions → "Release to production" → ref `main`. It checks dev runs that SHA and fast-forwards `production`.
4. Rollback = Railway "Redeploy" of the previous production deployment.
