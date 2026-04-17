-- Document creator: who uploaded the document (doctor or patient).
-- Exactly one of uploaded_by_doctor_id / uploaded_by_patient_profile_id may be set; both null = legacy (treated as locked).
ALTER TABLE patient_documents
    ADD COLUMN uploaded_by_doctor_id BIGINT NULL,
    ADD COLUMN uploaded_by_patient_profile_id BIGINT NULL;

ALTER TABLE patient_documents
    ADD CONSTRAINT fk_patient_documents_uploaded_by_doctor
        FOREIGN KEY (uploaded_by_doctor_id) REFERENCES doctor_profiles(id);
ALTER TABLE patient_documents
    ADD CONSTRAINT fk_patient_documents_uploaded_by_patient
        FOREIGN KEY (uploaded_by_patient_profile_id) REFERENCES patient_profiles(id);
