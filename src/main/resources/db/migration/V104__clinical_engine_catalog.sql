-- Clinical Engine v1.0 catalog, Smart Priority, synthesis templates, 52-tooth registry

CREATE TABLE clinical_group (
    group_id   VARCHAR(32) PRIMARY KEY,
    sort_order INT NOT NULL,
    name_ru    TEXT NOT NULL,
    name_uz    TEXT NOT NULL,
    name_en    TEXT NOT NULL
);

CREATE TABLE clinical_disease (
    disease_id  VARCHAR(32) PRIMARY KEY,
    group_id    VARCHAR(32) NOT NULL REFERENCES clinical_group (group_id),
    number_val  INT NOT NULL,
    slug        VARCHAR(64) NOT NULL,
    icd_codes   JSONB NOT NULL DEFAULT '[]'::jsonb,
    name_ru     TEXT NOT NULL,
    name_uz     TEXT NOT NULL,
    name_en     TEXT NOT NULL,
    active      BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE clinical_chip (
    chip_id    VARCHAR(64) PRIMARY KEY,
    field_name VARCHAR(32) NOT NULL,
    variables  JSONB NOT NULL DEFAULT '[]'::jsonb,
    priority   INT NOT NULL DEFAULT 50,
    active     BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE clinical_chip_i18n (
    chip_id VARCHAR(64) NOT NULL REFERENCES clinical_chip (chip_id) ON DELETE CASCADE,
    locale  VARCHAR(5) NOT NULL,
    label   TEXT NOT NULL,
    PRIMARY KEY (chip_id, locale)
);

CREATE TABLE clinical_chip_synthesis_template (
    id                BIGSERIAL PRIMARY KEY,
    chip_id           VARCHAR(64) NOT NULL REFERENCES clinical_chip (chip_id) ON DELETE CASCADE,
    locale            VARCHAR(5) NOT NULL,
    field_name        VARCHAR(32) NOT NULL,
    sentence_template TEXT NOT NULL,
    sort_order        INT NOT NULL DEFAULT 0,
    UNIQUE (chip_id, locale)
);

CREATE TABLE clinical_disease_chip (
    disease_id VARCHAR(32) NOT NULL REFERENCES clinical_disease (disease_id) ON DELETE CASCADE,
    chip_id    VARCHAR(64) NOT NULL REFERENCES clinical_chip (chip_id) ON DELETE CASCADE,
    sort_order INT NOT NULL DEFAULT 0,
    PRIMARY KEY (disease_id, chip_id)
);

CREATE TABLE clinical_occlusion_chip (
    chip_id      VARCHAR(32) PRIMARY KEY,
    angle_class  VARCHAR(8),
    icd_hint     VARCHAR(16),
    variables    JSONB NOT NULL DEFAULT '[]'::jsonb,
    priority     INT NOT NULL DEFAULT 50,
    label_ru     TEXT NOT NULL,
    label_uz     TEXT NOT NULL,
    label_en     TEXT NOT NULL
);

CREATE TABLE clinical_shared_template (
    template_id VARCHAR(64) PRIMARY KEY,
    template_type VARCHAR(32) NOT NULL,
    field_name  VARCHAR(32) NOT NULL,
    priority    INT NOT NULL DEFAULT 50,
    correlates  JSONB NOT NULL DEFAULT '[]'::jsonb,
    label_ru    TEXT NOT NULL,
    label_uz    TEXT NOT NULL,
    label_en    TEXT NOT NULL
);

CREATE TABLE clinical_dental_tooth_key (
    fdi_key     VARCHAR(4) PRIMARY KEY,
    dentition   VARCHAR(16) NOT NULL,
    quadrant    VARCHAR(16) NOT NULL,
    sort_order  INT NOT NULL
);

CREATE TABLE clinical_doctor_disease_usage (
    doctor_id  BIGINT NOT NULL REFERENCES doctor_profiles (id) ON DELETE CASCADE,
    disease_id VARCHAR(32) NOT NULL REFERENCES clinical_disease (disease_id) ON DELETE CASCADE,
    use_count  INT NOT NULL DEFAULT 0,
    last_used_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (doctor_id, disease_id)
);

CREATE INDEX idx_clinical_doctor_usage_top
    ON clinical_doctor_disease_usage (doctor_id, use_count DESC, last_used_at DESC);

CREATE TABLE clinical_doctor_disease_recent (
    id         BIGSERIAL PRIMARY KEY,
    doctor_id  BIGINT NOT NULL REFERENCES doctor_profiles (id) ON DELETE CASCADE,
    disease_id VARCHAR(32) NOT NULL REFERENCES clinical_disease (disease_id) ON DELETE CASCADE,
    used_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_clinical_doctor_recent
    ON clinical_doctor_disease_recent (doctor_id, used_at DESC);

ALTER TABLE patient_forms
    ADD COLUMN clinical_disease_id VARCHAR(32) REFERENCES clinical_disease (disease_id);

CREATE INDEX idx_patient_forms_clinical_disease
    ON patient_forms (clinical_disease_id)
    WHERE clinical_disease_id IS NOT NULL;
