# Doctor, clinic and admin web app (`web-flutter-doctor`, Flutter): what to update after the Phase 6 backend

Written 2026-10-30 for the backend of branch `refactor/phase6`. **The app was not run**: Flutter is not installed on
the machine that ran the tests (the `PATH` entry in `.zshrc` points to a folder that no longer exists), so this is a
contract-level check plus the items the new backend makes possible. To run it against development once Flutter is
installed: `flutter run -d chrome --dart-define=API_BASE_URL=https://api-dev.shifa.uz` and log in with
`seed.doctor1@shifa.dev` / `Dev12345!` (clinic staff: `seed.staff@shifa.dev`).

## Check against the pre-release backend (2026-10-31)

Done with Flutter 3.35.5 (the version the deploy workflows pin) against `https://api-dev.shifa.uz` at commit 460e0af,
as `seed.doctor1@shifa.dev`, in a headless Chrome driven over the DevTools protocol (web build) and on the Android
emulator (debug APK). `flutter analyze` (no errors, the usual infos and warnings), `flutter test` (54 tests) and
`flutter build web` pass. Every screen below loaded with only 2xx answers from the API: sign-in, Home, Calendar
(day view and the appointment detail), Patients (overview, documents, appointments), Clinic (overview, services
with prices shown as `amountMinor / 100`, treatment plans, finance dashboard), Tasks, Reports, Finance, Settings
(profile, services and pricing editor) and the Shifa Intelligence panel; on Android: sign-in, Home, Calendar,
Patients, Chat. A token whose signature no longer verifies gets 401 `TOKEN_INVALID` and the app signs out.

Found and fixed on the backend (branch `fix/intelligence-readonly-transactions`): `POST /api/ai/morning/stream`,
which carries every Shifa Intelligence prompt of this app, answered 500 because the Phase 6 commit e7a3821 marked
`IntelligenceBookingWorkflowService.active*` read-only while the repository expires stale workflows with an UPDATE
first; `AppointmentActionService.previewCancellation` had the same defect. Production (3201e80) is not affected.

Changed in this repo (branch `chore/api-domain`): the release-build fallback `AppConfig.productionApiBaseUrl`, the
two deploy workflows and the README now use `https://api.shifa.uz` (section 3 below). The live web builds at
`doctor.shifa.uz` and `admin.shifa.uz` still carry the old host until they are rebuilt and deployed.

Not checked: the admin panel (its sign-in needs the e-mail OTP of a seeded admin mailbox) and video calls.

## Contract check

All 186 distinct `/api/...` paths referenced under `lib/` exist in `docs/api/openapi.json` with the same methods (the
six that did not match literally were prefix constants and query-string templates). No request or response shape used
by the app changed in Phase 5 or Phase 6; the Phase 6 rewrites were verified response-by-response against the previous
build. Nothing is required for the current build to keep working.

## 1. Adopt the paged endpoints (optional)

The admin and clinic roster lists already use Spring `Pageable` parameters. Three doctor-side lists gained a `/page`
sibling with the same filters and order, returning the same `Page` shape (`content`, `totalElements`, `totalPages`,
`number`, `size`, ...), 0-based `page`, `size` default 20, `sort` ignored:

| Today | Sibling | Screen |
|---|---|---|
| `GET /api/patients/{patientId}/documents?clinicId=` | `GET /api/patients/{patientId}/documents/page?clinicId=&page=&size=` | patient documents |
| `GET /api/patients/{patientId}/forms` | `GET /api/patients/{patientId}/forms/page` | patient forms |
| `GET /api/notifications` | `GET /api/notifications/page` | notification bell |

Everything else the doctor app lists (calendar day, schedule rules, conversations, treatment plans of a clinic,
catalog items) is bounded by nature and stays as it is. The `for-assignment` directory loop (pages of 500) is unchanged
and still works; Spring's default cap of 2000 rows per page applies.

## 2. Session errors are always 401 now

The backend returns 401 with the API body for every token it cannot accept: expired or revoked sessions as before, and
since the cleanup PR that followed Phase 6 also tokens that do not parse or whose signature no longer verifies (after a
`JWT_SECRET` rotation; `code` `TOKEN_INVALID` or `SESSION_EXPIRED`). The app's existing "401 means sign out" handling
covers it. A request without a token to a protected route is also a 401 now (`code` `UNAUTHORIZED`) instead of a 403;
public routes (professions, public doctors) ignore a token that no longer parses or verifies and keep answering 200 (a
revoked session is still 401 everywhere, as before).

## 3. API host

The CI workflows (`google_play_deploy.yml`, `ios_testflight.yml`) and the web build still pass
`API_BASE_URL=https://shifa-doc-backend-mvp-production.up.railway.app`. The host stays alive, but `https://api.shifa.uz`
is the production name now (roadmap Q5 step 4, branch `chore/api-domain` in this repo), and development builds should
use `https://api-dev.shifa.uz`.

## 4. Error codes

Error bodies are `{error, message, status, code, requestId}`; `code` values come from the backend's
`shared/ErrorCode.kt` (`VALIDATION`, `SESSION_INVALID`, `TOKEN_INVALID`, `RATE_LIMIT`, `FORBIDDEN`, `NOT_FOUND`, ...).
Where the app branches on the English `message`, branch on `code`; `requestId` is worth showing in error toasts so a
support request can be matched to a backend log line.

## 5. Data the doctor side can now show without extra calls

- `TaskDto` carries `doctorId`, `doctorName`, `appointmentId` (remote-care tasks linked to a visit).
- `PatientDocumentDto` carries `uploadedByPatient`, `creatorType`, `appointmentId`.
- Appointment DTOs carry `signatureRequested`, `alreadySigned`, `documentIds`, `notes`; patients can attach PDFs after
  booking (`documentIds` grows), which the pre-visit screen can list.
- `GET /api/public/doctors/{id}` is cached for 60 s on the server: a doctor editing their own profile sees the public
  view update within a minute, which is expected.

## 6. Known backend items that will need app work later (not in this release)

Push texts in the patient's language (item 1), per-device push tokens (item 2), Shifa AI coded SSE errors (item 11),
the auth split and Boot 4 upgrade have no app impact until they ship; the KMP migration spec for this app
(`docs/kmp-spec/`) should take the paged endpoints and the error codes as given.
