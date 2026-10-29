# Doctor, clinic and admin web app (`web-flutter-doctor`, Flutter): what to update after the Phase 6 backend

Written 2026-10-30 for the backend of branch `refactor/phase6`. **The app was not run**: Flutter is not installed on
the machine that ran the tests (the `PATH` entry in `.zshrc` points to a folder that no longer exists), so this is a
contract-level check plus the items the new backend makes possible. To run it against development once Flutter is
installed: `flutter run -d chrome --dart-define=API_BASE_URL=https://api-dev.shifa.uz` and log in with
`seed.doctor1@shifa.dev` / `Dev12345!` (clinic staff: `seed.staff@shifa.dev`).

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

## 2. Session errors: 403 versus 401

Same as the patient app: the backend returns 401 for expired or revoked sessions (the app logs out), but a token whose
signature no longer verifies (after a `JWT_SECRET` rotation) produces Spring's default 403 with a non-API body. Until
the backend returns 401 for that case (recommended fix, `docs/PLAN_AUDIT_2026-10-30.md` §9), the app should treat a
403 on its first profile call after start-up as "session invalid" and clear the token instead of showing an error.

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
