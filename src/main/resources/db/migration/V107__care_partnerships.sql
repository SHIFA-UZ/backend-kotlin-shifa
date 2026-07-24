-- Care partnerships: cross-clinic / cross-border co-treatment between doctors.
CREATE TABLE IF NOT EXISTS care_partnerships (
    id                        BIGSERIAL PRIMARY KEY,
    patient_id                BIGINT NOT NULL,
    initiating_doctor_id      BIGINT NOT NULL,
    partner_doctor_id         BIGINT NOT NULL,
    status                    VARCHAR(32) NOT NULL,
    originating_appointment_id BIGINT NULL,
    specialty_requested       VARCHAR(255),
    message                   TEXT,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_care_partnerships_patient
        FOREIGN KEY (patient_id) REFERENCES patient_profiles (id),
    CONSTRAINT fk_care_partnerships_initiating_doctor
        FOREIGN KEY (initiating_doctor_id) REFERENCES doctor_profiles (id),
    CONSTRAINT fk_care_partnerships_partner_doctor
        FOREIGN KEY (partner_doctor_id) REFERENCES doctor_profiles (id),
    CONSTRAINT fk_care_partnerships_appointment
        FOREIGN KEY (originating_appointment_id) REFERENCES appointments (id) ON DELETE SET NULL
);

CREATE INDEX IF NOT EXISTS idx_care_partnerships_patient
    ON care_partnerships (patient_id);
CREATE INDEX IF NOT EXISTS idx_care_partnerships_initiating
    ON care_partnerships (initiating_doctor_id);
CREATE INDEX IF NOT EXISTS idx_care_partnerships_partner
    ON care_partnerships (partner_doctor_id);
CREATE INDEX IF NOT EXISTS idx_care_partnerships_status
    ON care_partnerships (status);

CREATE TABLE IF NOT EXISTS care_partnership_progress_updates (
    id                BIGSERIAL PRIMARY KEY,
    partnership_id    BIGINT NOT NULL,
    author_doctor_id  BIGINT NOT NULL,
    body              TEXT NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_care_progress_partnership
        FOREIGN KEY (partnership_id) REFERENCES care_partnerships (id) ON DELETE CASCADE,
    CONSTRAINT fk_care_progress_author
        FOREIGN KEY (author_doctor_id) REFERENCES doctor_profiles (id)
);

CREATE INDEX IF NOT EXISTS idx_care_progress_partnership
    ON care_partnership_progress_updates (partnership_id, created_at DESC);
