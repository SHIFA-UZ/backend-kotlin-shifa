-- Persist Clinical Engine chip selections for form edit restore (025-2).

ALTER TABLE patient_forms
    ADD COLUMN clinical_chip_selections JSONB NOT NULL DEFAULT '[]'::jsonb;

CREATE INDEX idx_clinical_chip_i18n_locale
    ON clinical_chip_i18n (locale);
