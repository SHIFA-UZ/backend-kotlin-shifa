# Patient Phone Uniqueness — Architecture & Implementation

## Phase 1 — Backend Analysis Summary

### Where patient profiles are created

| Flow | Endpoint | Controller | Creates |
|------|----------|------------|---------|
| **Doctor-created** | `POST /api/patients` | `PatientsController.createPatient()` | One row in `patient_profiles` (no `user_id` initially). |
| **Self-registration** | `POST /api/auth/register-patient` | `AuthController.registerPatient()` | One row in `users` + one row in `patient_profiles` (with `user_id` set). |

Both flows write to `patient_profiles`. Neither flow checked for an existing patient by phone before insert.

### Uniqueness before this change

- **`users` table (V1):** `phone TEXT UNIQUE` — duplicate phones cannot exist in `users`.
- **`patient_profiles` table (V1):** `phone TEXT`, `email TEXT` — **no unique constraint**. Duplicate phones/emails were allowed.
- **Register-patient:** Checked only `users.findByPhone(r.phone)` and `users.findByEmail(...)`. So duplicate *users* were prevented, but a doctor could still create a `patient_profiles` row with a phone that already existed (e.g. from another doctor-created patient or from a different format of the same number).
- **POST /api/patients:** No uniqueness check; `patientsRepo.save(patient)` was called directly.

### Phone normalization

- **Before:** None. Only `trim()` was used in both flows.
- **After:** Single shared normalizer: trim → strip non-digits → ensure leading `+` → store in `phone` and `phone_normalized`. Uniqueness is enforced on `phone_normalized`.

---

## Phase 2 — Design

### 1. Backend enforcement (authoritative)

- **POST /api/patients:** Normalize phone; if non-empty, check `findByPhoneNormalized(normalized)`; if present → **409 Conflict** with message *"Patient with this phone number already exists."* Then save with normalized phone.
- **POST /api/auth/register-patient:** Normalize phone; check `users.findByPhone(normalized)` (user uniqueness); check `patients.findByPhoneNormalized(normalized)` (patient profile uniqueness); if either exists → **409**. Store normalized phone in both `users` and `patient_profiles`.
- **PATCH /api/patients/{id}:** When updating phone, normalize and check `findByPhoneNormalized` excluding current id; 409 if duplicate.

### 2. Database-level enforcement

- Add nullable column `phone_normalized` (TEXT).
- Backfill: set `phone_normalized = normalize(phone)` in SQL (trim, digits only, leading `+`; empty → NULL).
- Create **unique partial index**: `CREATE UNIQUE INDEX ... ON patient_profiles(phone_normalized) WHERE phone_normalized IS NOT NULL AND phone_normalized != ''`.
- Empty string is treated as NULL for uniqueness (no constraint on NULLs).

### 3. Phone normalization

- **Rule:** Trim → remove all non-digit characters → if result non-empty prefix with `+`; otherwise return null.
- Stored in both `phone` (display/backward compatibility) and `phone_normalized` (uniqueness and lookups).
- All checks use normalized value.

### 4. Error handling

- **409 Conflict**, body: `{ "message": "Patient with this phone number already exists." }`.
- No internal IDs or stack traces in response.

### 5. Doctor app UX

- On 409 from create/update patient: show user-friendly message and do not auto-create.

### 6. Registration consistency

- Register-patient uses the same normalizer and the same `patient_profiles` uniqueness check so it cannot create a duplicate profile by phone.

---

## Migration strategy

1. **V45** (single migration):
   - Add `phone_normalized` column.
   - Backfill with SQL expression (digits-only + leading `+`; blank → NULL).
   - Create unique partial index on `phone_normalized`.

2. **If migration fails** (duplicate `phone_normalized`):
   - Run analysis query (see below) to list duplicates.
   - Resolve manually (merge or leave one, clear/update others).
   - Re-run migration or add a follow-up migration that cleans and then adds the index.

3. **No automatic merge or delete** of existing data; duplicates require manual or scripted resolution before the unique index is applied.

4. **If V45 fails** (unique index creation fails due to duplicates): run the backfill and analysis query in `docs/duplicate_patient_phone_analysis.sql` to list duplicates, then resolve (e.g. merge or clear `phone`/`phone_normalized` for duplicates you want to drop). Then create the index manually or fix data and re-run migration.

---

## Edge cases handled

- **Null/empty phone:** Not considered for uniqueness; no constraint violation.
- **Whitespace / formatting:** Normalized before check and storage.
- **Concurrent requests:** DB unique index prevents two inserts with same normalized phone.
- **Register then doctor-create same number:** Second request (either flow) gets 409.
- **Update patient phone to existing number:** 409 when updating to a phone that already exists on another profile.
