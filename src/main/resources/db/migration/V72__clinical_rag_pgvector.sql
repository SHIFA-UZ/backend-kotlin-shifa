-- Clinical RAG: pgvector-backed chunks for semantic retrieval (025-2 charts, notes, appointment dental JSON).
-- Requires PostgreSQL with pgvector (e.g. CREATE EXTENSION enabled on the instance).

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE clinical_rag_chunks (
    id BIGSERIAL PRIMARY KEY,
    patient_id BIGINT NOT NULL REFERENCES patient_profiles(id) ON DELETE CASCADE,
    source_type VARCHAR(32) NOT NULL,
    source_record_id BIGINT NOT NULL,
    chunk_index INT NOT NULL,
    content_text TEXT NOT NULL,
    embedding vector(1536) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_clinical_rag_chunk UNIQUE (patient_id, source_type, source_record_id, chunk_index)
);

CREATE INDEX idx_clinical_rag_chunks_patient ON clinical_rag_chunks (patient_id);

-- IVFFLAT works with low row counts when lists is small; tune lists ≈ row_count/1000 in production.
CREATE INDEX idx_clinical_rag_chunks_embedding_ivfflat
    ON clinical_rag_chunks USING ivfflat (embedding vector_cosine_ops) WITH (lists = 50);

CREATE TABLE clinical_rag_retrieval_audit (
    id BIGSERIAL PRIMARY KEY,
    doctor_id BIGINT NOT NULL,
    patient_id BIGINT NOT NULL,
    query_excerpt TEXT NOT NULL,
    chunk_ids BIGINT[] NOT NULL,
    distances DOUBLE PRECISION[] NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_clinical_rag_audit_patient ON clinical_rag_retrieval_audit (patient_id);
