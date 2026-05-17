CREATE TABLE clinics (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    phone VARCHAR(64),
    email VARCHAR(255),
    address TEXT,
    time_zone VARCHAR(64) NOT NULL DEFAULT 'Asia/Tashkent',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE clinic_memberships (
    id BIGSERIAL PRIMARY KEY,
    clinic_id BIGINT NOT NULL REFERENCES clinics(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    membership_role VARCHAR(32) NOT NULL,
    doctor_profile_id BIGINT REFERENCES doctor_profiles(id) ON DELETE CASCADE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (clinic_id, user_id)
);

CREATE INDEX idx_clinic_memberships_user ON clinic_memberships(user_id);
CREATE INDEX idx_clinic_memberships_clinic ON clinic_memberships(clinic_id);

ALTER TABLE doctor_profiles ADD COLUMN clinic_id BIGINT REFERENCES clinics(id);
CREATE INDEX idx_doctor_profiles_clinic_id ON doctor_profiles(clinic_id);

-- One clinic row per distinct legacy clinic label (trimmed).
INSERT INTO clinics (name, time_zone)
SELECT trimmed_c,
       tz
FROM (
         SELECT TRIM(clinic)                                        AS trimmed_c,
                COALESCE(MAX(time_zone), 'Asia/Tashkent')           AS tz
         FROM doctor_profiles
         WHERE clinic IS NOT NULL
           AND TRIM(clinic) <> ''
         GROUP BY TRIM(clinic)
     ) sub;

UPDATE doctor_profiles dp
SET clinic_id = c.id
FROM clinics c
WHERE dp.clinic IS NOT NULL
  AND TRIM(dp.clinic) <> ''
  AND TRIM(dp.clinic) = c.name;

INSERT INTO clinic_memberships (clinic_id, user_id, membership_role, doctor_profile_id, active)
SELECT dp.clinic_id,
       dp.user_id,
       'DOCTOR',
       dp.id,
       TRUE
FROM doctor_profiles dp
WHERE dp.clinic_id IS NOT NULL
ON CONFLICT (clinic_id, user_id) DO NOTHING;

-- First membership per clinic becomes OWNER (manage catalog / staff).
UPDATE clinic_memberships m
SET membership_role = 'OWNER'
FROM (
         SELECT DISTINCT ON (clinic_id) id
         FROM clinic_memberships
         ORDER BY clinic_id, id ASC
     ) first_row
WHERE m.id = first_row.id;
