# Push notifications

What the backend sends over FCM and what the inbox (`GET /api/notifications`, `NotificationDto`) carries. The type
list is guarded by `PushNotificationsDocTest`: a new `Notification.Type` fails the build until it is listed here.

## Doctor pushes

- **Devices:** every token the doctor registered with `POST /api/doctors/me/fcm-tokens {token, platform}` (removed with
  `DELETE /api/doctors/me/fcm-tokens/{token}` at sign-out), plus the single legacy `PUT /api/doctors/me/fcm-token`
  token of the Flutter build; each push goes to each of them once. Tokens FCM reports as unregistered are dropped.
- **Text:** `notification.title`/`body` are written in the doctor's language (`PUT /api/doctors/me/language`; en, uz,
  uz-Cyrl, ru) by `DoctorPushI18n`.
- **`data`** (strings; a key is present only when it applies):

| Key | Meaning |
|---|---|
| `type` | One of the types below |
| `notificationId` (and the older `id`) | The inbox row; mark it read with `PUT /api/notifications/{id}/read` |
| `title`, `message` | The inbox row's English text (the tray shows the localised one) |
| `createdAt` | ISO-8601 instant |
| `appointmentId`, `appointmentStartAt` | The appointment and its start (ISO-8601 instant) |
| `patientId`, `patientName` | The patient (from the document, the appointment or the sender) |
| `documentId`, `documentTitle`, `documentAccessRequestId` | Documents and access requests |
| `taskId` | Remote-care task |
| `treatmentPlanId`, `installmentItemId` | Treatment plan and installment |
| `patientFormId` | Patient form (025-2) |
| `conversationId` (and the older `chatId`, `entityId`) | Chat conversation |
| `carePartnershipId` (and the older `partnershipId`) | Care partnership |

The inbox's `NotificationDto` has the same ids (`conversationId` and `carePartnershipId` since 2026-11-07).

## Patient pushes about a cancelled or missed visit

- `APPOINTMENT_CANCELLED` from `POST /api/appointments/{id}/cancel` (a reason given): the title and body are in the
  patient's language (en, uz, ru) and end with the reason as a sentence; `data` and the inbox row also carry
  `cancelReasonCode` (`DOCTOR_UNAVAILABLE`, `PATIENT_REQUEST`, `RESCHEDULED`, `OTHER`) and `cancelNote` when the doctor
  wrote one. A cancellation without a reason (`DELETE /api/appointments/{id}`, schedule blocks) is unchanged: English
  text, no reason keys.
- `APPOINTMENT_NO_SHOW` from `POST /api/appointments/{id}/no-show` (unless the body has `"notify": false`): title and
  body in the patient's language, with `appointmentId`.

## Types

`D` = can reach the doctor app, `P` = the patient app.

| Type | To |
|---|---|
| `CHAT_MESSAGE` | D, P |
| `APPOINTMENT_CANCELLED` | D, P |
| `APPOINTMENT_CHANGED` | P |
| `APPOINTMENT_NO_SHOW` | P |
| `APPOINTMENT_REMINDER` | P |
| `APPOINTMENT_BOOKED_BY_PATIENT` | D |
| `APPOINTMENT_CANCELLED_BY_PATIENT` | D |
| `APPOINTMENT_RESCHEDULED_BY_PATIENT` | D |
| `APPOINTMENT_ASSIGNED` | (not sent today) |
| `APPOINTMENT_PAID_BY_PATIENT` | D |
| `APPOINTMENT_BRIEFING_READY` | D |
| `SIGNATURE_REQUESTED` | P |
| `TASK_ASSIGNED` | P |
| `TASK_REMINDER` | P |
| `TASK_COMPLETED` | D |
| `TASK_CANCELLED` | P |
| `DOCUMENT_ACCESS_REQUEST` | D, P |
| `DOCUMENT_ACCESS_APPROVED` | D |
| `DOCUMENT_ACCESS_REJECTED` | D |
| `GENERAL` | D (test push) |
| `AI_SCRIBE_READY` | D |
| `AI_VISIT_SUMMARY_READY` | P |
| `CONSULTATION_PAYMENT_REMINDER` | P |
| `CONSULTATION_PAYMENT_DUE_24H` | P |
| `CONSULTATION_PAYMENT_DUE_6H` | P |
| `CONSULTATION_PAYMENT_DUE_1H` | P |
| `TREATMENT_PLAN_PAYMENT_REMINDER` | P |
| `TREATMENT_PLAN_UPDATED` | P |
| `PROPHYLAXIS_REMINDER` | P |
| `INSTALLMENT_DUE_SOON` | P |
| `INSTALLMENT_DUE_TODAY` | P |
| `INSTALLMENT_OVERDUE` | P |
| `INSTALLMENT_SCHEDULE_CREATED` | P |
| `CARE_PARTNERSHIP_INVITE` | D |
| `CARE_PARTNERSHIP_ACCEPTED` | D |
| `CARE_PARTNERSHIP_PROGRESS` | D |
