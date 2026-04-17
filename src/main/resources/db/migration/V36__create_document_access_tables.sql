-- Access requests: doctor requests access to a document they don't own.
CREATE TABLE document_access_requests (
    id BIGSERIAL PRIMARY KEY,
    document_id BIGINT NOT NULL REFERENCES patient_documents(id) ON DELETE CASCADE,
    requesting_doctor_id BIGINT NOT NULL REFERENCES doctor_profiles(id),
    owner_type VARCHAR(20) NOT NULL CHECK (owner_type IN ('doctor', 'patient')),
    owner_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'approved', 'rejected')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_document_access_requests_document ON document_access_requests(document_id);
CREATE INDEX idx_document_access_requests_requesting ON document_access_requests(requesting_doctor_id);
CREATE UNIQUE INDEX idx_document_access_requests_unique_pending
    ON document_access_requests(document_id, requesting_doctor_id)
    WHERE status = 'pending';

-- Explicit grants: document owner approved access for a doctor.
CREATE TABLE document_access_grants (
    id BIGSERIAL PRIMARY KEY,
    document_id BIGINT NOT NULL REFERENCES patient_documents(id) ON DELETE CASCADE,
    doctor_id BIGINT NOT NULL REFERENCES doctor_profiles(id),
    granted_by VARCHAR(20) NOT NULL CHECK (granted_by IN ('doctor', 'patient')),
    granted_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(document_id, doctor_id)
);

CREATE INDEX idx_document_access_grants_document ON document_access_grants(document_id);
CREATE INDEX idx_document_access_grants_doctor ON document_access_grants(doctor_id);
