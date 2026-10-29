# Patient app (`apps-flutter-patient`, Kotlin Multiplatform): what to update after the Phase 6 backend

Written 2026-10-30 after running the app (debug build, Android emulator) against the backend of branch
`refactor/phase6`. Nothing here is required for the app to keep working: every endpoint the app calls exists unchanged
in `docs/api/openapi.json`, and all flows below worked without an error on the backend side. The items are
improvements the new backend makes possible, plus two things the test showed.

## What was tested and worked

| Flow | App calls | Result |
|---|---|---|
| Login | `POST /auth/login?app=patient` | 200, token issued |
| Home | `GET /patients/me/profile`, `/patients/me/appointments`, `/tasks/my-tasks`, `/notifications/unread/count`, `/public/doctors/{id}`, `PUT /patients/me/fcm-token` | all 200/204 |
| Bookings tab | `GET /public/doctors` (4 calls), `/patients/me/appointments` | 200 |
| Documents tab | `GET /patients/me/documents` | 200, both seed documents shown |
| Messages tab, conversation, read | `GET /patients/me/messages/conversations`, `/conversations/{id}`, `POST /conversations/{id}/read` | 200, unread count went 2 → 0 (new bulk mark-read) |
| Notifications | `GET /notifications` | 200 (new batched feed) |
| Doctor profile, reviews, locations | `GET /public/doctors/{id}`, `/reviews`, `/locations` | 200 (new batched listing, cached profile) |
| Create booking | `GET /patients/me/schedule/doctors/{id}/available?day=`, `POST /patients/me/appointments` | 200, "Appointment slot booked" |
| Shifa AI tab | none until a chat starts | screen renders (no AI key locally) |
| Profile | `GET /patients/me/profile` | 200 |

## 1. Adopt the paged endpoints (optional, release by release)

The backend keeps every list endpoint unchanged and adds `/page` siblings with the same filters and order. Each returns
Spring's `Page` shape (`content`, `totalElements`, `totalPages`, `number`, `size`, `first`, `last`, `empty`, plus
`pageable` and `sort` objects), with 0-based `page` and `size` (default 20, `sort` ignored):

| Today | Sibling | Where it helps |
|---|---|---|
| `GET /notifications` | `GET /notifications/page?page=0&size=20` | the feed grows for ever; the seed patient already has 36 rows |
| `GET /patients/me/appointments` (with `status`, `startDate`, `endDate`) | `GET /patients/me/appointments/page?...` | past appointments list |
| `GET /patients/me/documents` | `GET /patients/me/documents/page` | documents tab |
| `GET /public/doctors/{doctorId}/reviews` | `GET /public/doctors/{doctorId}/reviews/page` | reviews tab on the doctor profile |

Suggested order: notifications first (infinite scroll on the feed), then appointments. `15-api-reference.md` §1.8
("only `GET /public/doctors` is paginated") needs the new paragraph; a `DemoBackend` handler per adopted sibling.

## 2. Doctor availability: stop probing day by day

On the booking screen the app called `GET /patients/me/schedule/doctors/{id}/available` **60 times** (two months of
days) when the seed doctor had no slots, and 7 times when he had. The doctor DTO already carries
`nextAvailableStartAt`: `GET /public/doctors/{id}` always computes it over the next 30 days, and the list does so over
21 days when `includeNextAvailable=true` is sent. Use it to pick the first date to show and to say "no availability in
the next month" without any probing; only the chosen day needs an `available` call.

## 3. Handle 403 like 401 for session errors (until the backend changes)

The app logs out on 401. The backend answers 401 for expired and revoked sessions, but a token whose signature does not
verify (after a `JWT_SECRET` rotation on the server) is ignored and the request ends in Spring's default **403** with a
non-API body (`{"status":403,"error":"Forbidden",...}`). The app then shows "Something went wrong" on every screen and
never logs out. Two options: the backend returns 401 with the API error shape for unparsable tokens (recommended, listed
in `docs/PLAN_AUDIT_2026-10-30.md` §9), or the app treats a 403 on `/patients/me/profile` at start-up as "session
invalid" and clears the token.

## 4. API reference (`docs/kmp-spec/15-api-reference.md`) is stale in places

- §1.1 still describes the Flutter app (`core/config/app_config.dart`, `--dart-define=API_BASE_URL`,
  `publicBaseUrl` on `localhost:8090`). The KMP app has `ApiConfig.BASE_URL` (production, constant) and no
  override; document that, and that the development backend is `https://api-dev.shifa.uz` (seed accounts
  `seed.patient1@shifa.dev` / `Dev12345!`).
- §1.8 pagination: add the `/page` siblings (section 1 above).
- Error shape: `{error, message, status, code, requestId}`; `code` values are in the backend's
  `shared/ErrorCode.kt` (16 values, e.g. `SESSION_INVALID`, `TOKEN_INVALID`, `VALIDATION`, `RATE_LIMIT`). The app
  matches on English `message` text today; matching on `code` is the stable way.
- Appointment statuses the backend returns: `REQUESTED CONFIRMED IN_PROGRESS CANCELLED COMPLETED NO_SHOW
  PENDING_PAYMENT EXPIRED PAYMENT_FAILED`; the reference maps `REQUESTED` to "unknown". Payment statuses
  `NOT_REQUIRED PENDING PAID FAILED REFUNDED PAY_IN_CLINIC`; `failureReason` is exposed.
- `PatientDocumentDto` has `uploadedByPatient`, `creatorType` (`PATIENT`, `DOCTOR`, `UNKNOWN`) and `appointmentId`;
  the app can drop its "Added by you" heuristic if it still derives it from names.
- Both appointment DTOs carry `signatureRequested`, `alreadySigned`, `documentIds`, `notes`;
  `POST /patients/me/appointments/{id}/documents` attaches documents after booking;
  `TaskDto` has `doctorId`, `doctorName`, `appointmentId`; `DELETE /tasks/my-tasks/{taskId}/check-ins/{id}` within 24 h.
- Account deletion: `POST /patients/me/delete-account/request` returns `channels`, `maskedEmail`, `maskedPhone`
  and no longer flips the account state; `/confirm` accepts `smsOtp` (purpose `ACCOUNT_DELETION`, requested through
  the generic OTP endpoint) or the e-mail code.

## 5. Pointing a debug build at development

The app has no base-URL override; the test used a temporary local edit of `ApiConfig.BASE_URL` (reverted). A
`BuildConfig` field or a debug-menu setting for the base URL would let testers use `https://api-dev.shifa.uz`
(roadmap 3.4). Cleartext is already allowed by `network_security_config.xml`, so `http://10.0.2.2:8080` works for a
local backend on the emulator.

## Nothing to change for

Login, profile, FCM token, documents, chat, notifications, public doctors, reviews, locations, payments methods,
video token, co-pilot streaming: request and response shapes are identical to the previous backend (the Phase 6 change
set was verified response-by-response against the previous build).
