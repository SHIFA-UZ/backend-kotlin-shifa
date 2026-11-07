# Shifa AI streams: error codes and tool hints

The AI routes stream Server-Sent Events: `POST /api/ai/stream` and `POST /api/ai/morning/stream` (doctor),
`POST /api/patients/me/copilot/stream` (patient). Every existing event and field stays as it was for store builds;
the fields below were added (BE-07, BE-08, 2026-11-07).

## Before the stream opens

Refusals are the standard JSON error body (`error`, `message`, `status`, `code`, `requestId`) even though the route
produces `text/event-stream`:

| Status | `code` | When |
|---|---|---|
| 401 | `UNAUTHORIZED`, `TOKEN_INVALID`, `SESSION_EXPIRED` | No or bad token (sign out) |
| 403 | `AI_TIER_REQUIRED` (doctor streams), `FORBIDDEN` (patient stream, unchanged) | The tier lacks the AI feature |
| 400 | `VALIDATION` | Invalid request body |
| 429 | `RATE_LIMIT` (unchanged), plus `errorCode: AI_QUOTA_EXCEEDED` | Daily or per-minute AI limit (`reason`, `remaining`, `dailyLimit`, `resetAt`, `Retry-After`) |
| 503 | `UNAVAILABLE` | All stream slots busy; retry shortly |

## `event: error` inside the stream

`data` is `{"code", "message", "errorCode", "requestId"}`. `code` keeps its legacy value (the patient app matches
`RATE_LIMIT`); new clients read `errorCode`:

| Legacy `code` | `errorCode` | App |
|---|---|---|
| `RATE_LIMIT` | `AI_QUOTA_EXCEEDED` | Daily-limit banner |
| `AI_UNAVAILABLE` | `AI_UPSTREAM` | Provider failed; retry |
| `SAFETY_BLOCK` | `AI_SAFETY_BLOCK` | Blocked by the safety rules |
| `VALIDATION` | `VALIDATION` | Fix the request |
| `INTERNAL` (doctor streams only) | `INTERNAL` | Unexpected failure; generic retry (the stream then fails as before) |

`requestId` is the request's `X-Request-Id`: show it in the error so a report can be matched to a log line.

## Tool hints (`event: tool`, morning stream)

Next to the English `spokenHint`, a tool payload may carry `hint: {code, params}`; params hold ids, counts, names
and ISO dates/times, never English text. Cards already carry ISO `startAt`, `date`, enum `status` and
`range: {start, endExclusive, kind}` next to the English labels (`whenLabel`, `dateLabel`, `range.label`).
A payload without `hint` is a sentence that has no code yet: show `spokenHint` as sent.

| `code` | `params` | Sentence it stands for |
|---|---|---|
| `CANCEL_ASK_WHICH` | | Which visits should I cancel? |
| `CANCEL_TOO_MANY` | `max` | That matches more than {max} visits… |
| `CANCEL_NONE_ELIGIBLE` | `skipped` | No visits in that period can be cancelled. |
| `CANCEL_PREVIEW` | `count`, `names[]`, `startAts[]`, `skipped` | I can cancel {count} upcoming visits… Say yes to cancel them. |
| `CANCEL_NOT_CONFIRMED` | | Nothing was cancelled. Say yes if you want me to cancel… |
| `CANCEL_NOT_WAITING` | | I don't have a cancellation waiting for confirmation. |
| `CANCEL_SCHEDULE_CHANGED` | | The schedule changed, so I did not cancel anything. |
| `CANCEL_RESULT` | `cancelled`, `failed` | Cancelled {n}… |
| `CANCEL_CHOOSE_VISIT` | `count`, `alreadyCancelled` | I found {count} visits that can be cancelled… Which one? |
| `OPEN_CHOOSE_VISIT` | `count` | I found {count} visits for this patient. Which one should I open? |
| `VISIT_NOT_FOUND` | | I could not find that visit. |
| `VISIT_NO_MATCH` | | I could not find a matching visit. Nothing was changed. |
| `ASK_PATIENT` | | Which patient should I book? |
| `ASK_WHICH_PATIENT` | `candidates[] {patientId, patientName}` | Which patient did you mean? |
| `CONFIRM_PATIENT` | `patientId`, `patientName` | Did you mean {patientName}? |
| `ASK_BOOKING_DAY` | `patientName` | Which day should I book for {patientName}? |
| `BOOKING_CONFIRM_REQUIRED` | `workflowId`, `patientName`, `date`, `time` | Please confirm booking {patientName} at {time} on {date}. |
| `BOOKING_DONE` | `appointmentId`, `patientName`, `date`, `time` | Booked {patientName} at {time} on {date}. |
| `BOOKING_REJECTED`, `BOOKING_ABANDONED` | `workflowId` | I did not book / I abandoned that booking. |
| `BOOKING_NOTHING_TO_CONFIRM` | `workflowId`? | There is no booking (proposal) waiting for confirmation. |
| `BOOKING_SLOT_FAILED` | `workflowId` | That slot could not be booked. |
| `RESCHEDULE_DONE` | `appointmentId`, `date`, `time` | Moved the appointment to {time} on {date}. |
| `RESCHEDULE_REJECTED` | `workflowId` | I did not move that appointment. |
| `RESCHEDULE_CHANGED` | `workflowId` | The appointment or slot changed, so I did not move it. |
| `RESCHEDULE_NOTHING_TO_CONFIRM` | | There is no reschedule waiting for confirmation. |

Skipped rows of a cancellation preview also carry `reasonCode` (`ALREADY_CANCELLED`, `ALREADY_COMPLETED`,
`TIME_PASSED`) next to the English `reason`.

Not coded yet (still sentence only): schedule summaries, briefings, slot search and move-day questions, patient search
results, document and chart openings, visit completion, signatures and notes. Add a code next to the sentence when one
is needed (`appointments.application.ToolHints`), and list it here.
