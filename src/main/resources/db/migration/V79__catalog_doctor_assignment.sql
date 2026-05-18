ALTER TABLE treatment_plan_catalog_items
    ADD COLUMN applies_to_all_doctors BOOLEAN NOT NULL DEFAULT TRUE;

CREATE TABLE treatment_plan_catalog_item_doctors (
    id BIGSERIAL PRIMARY KEY,
    catalog_item_id BIGINT NOT NULL REFERENCES treatment_plan_catalog_items(id) ON DELETE CASCADE,
    doctor_profile_id BIGINT NOT NULL REFERENCES doctor_profiles(id) ON DELETE CASCADE,
    CONSTRAINT uq_catalog_item_doctor UNIQUE (catalog_item_id, doctor_profile_id)
);

CREATE INDEX idx_catalog_item_doctors_catalog ON treatment_plan_catalog_item_doctors(catalog_item_id);
CREATE INDEX idx_catalog_item_doctors_doctor ON treatment_plan_catalog_item_doctors(doctor_profile_id);

ALTER TABLE doctor_services
    ADD COLUMN source_catalog_item_id BIGINT REFERENCES treatment_plan_catalog_items(id) ON DELETE SET NULL;

CREATE UNIQUE INDEX uq_doctor_services_catalog_source
    ON doctor_services (doctor_id, source_catalog_item_id)
    WHERE source_catalog_item_id IS NOT NULL;
