-- Upgrade ICD-10 search quality: full-text search + trigram similarity.
-- Safe, backward compatible.

-- Enable pg_trgm for typo-tolerant matching.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- Add optional parent_code for future hierarchy support.
ALTER TABLE icd10_codes
  ADD COLUMN IF NOT EXISTS parent_code VARCHAR(16);

-- Add a tsvector column for full-text search across EN/RU + keywords.
-- Generated column keeps data consistent without triggers.
ALTER TABLE icd10_codes
  ADD COLUMN IF NOT EXISTS search_tsv tsvector
  GENERATED ALWAYS AS (
    setweight(to_tsvector('simple', COALESCE(title, '')), 'A') ||
    setweight(to_tsvector('simple', COALESCE(title_ru, '')), 'A') ||
    setweight(to_tsvector('simple', COALESCE(keywords, '')), 'B')
  ) STORED;

-- Full-text index.
CREATE INDEX IF NOT EXISTS idx_icd10_search_tsv_gin
  ON icd10_codes
  USING GIN (search_tsv);

-- Trigram indexes for fuzzy matching on text fields.
CREATE INDEX IF NOT EXISTS idx_icd10_title_trgm
  ON icd10_codes
  USING GIN (title gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_icd10_title_ru_trgm
  ON icd10_codes
  USING GIN (title_ru gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_icd10_keywords_trgm
  ON icd10_codes
  USING GIN (keywords gin_trgm_ops);

