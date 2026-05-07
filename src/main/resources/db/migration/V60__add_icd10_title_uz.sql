-- Add Uzbek ICD-10 title support for localized diagnosis search results.

ALTER TABLE icd10_codes
  ADD COLUMN IF NOT EXISTS title_uz TEXT;

-- Speed up optional Uzbek trigram matching.
CREATE INDEX IF NOT EXISTS idx_icd10_title_uz_trgm
  ON icd10_codes
  USING GIN (title_uz gin_trgm_ops);

-- Seed Uzbek titles for commonly used diagnoses (especially dental set).
UPDATE icd10_codes SET title_uz = 'Emal kariyesi' WHERE code = 'K02.0';
UPDATE icd10_codes SET title_uz = 'Dentin kariyesi' WHERE code = 'K02.1';
UPDATE icd10_codes SET title_uz = 'To''xtagan kariyes' WHERE code = 'K02.3';
UPDATE icd10_codes SET title_uz = 'Pulpit' WHERE code = 'K04.0';
UPDATE icd10_codes SET title_uz = 'Pulpa nekrozi' WHERE code = 'K04.1';
UPDATE icd10_codes SET title_uz = 'Apikal periodontit' WHERE code = 'K04.5';
UPDATE icd10_codes SET title_uz = 'Gingivit' WHERE code = 'K05.0';
UPDATE icd10_codes SET title_uz = 'Surunkali gingivit' WHERE code = 'K05.1';
UPDATE icd10_codes SET title_uz = 'Periodontit' WHERE code = 'K05.3';
UPDATE icd10_codes SET title_uz = 'Milk retraksiyasi' WHERE code = 'K06.0';
UPDATE icd10_codes SET title_uz = 'VNTJ buzilishlari' WHERE code = 'K07.6';
UPDATE icd10_codes SET title_uz = 'Tish yo''qolishi' WHERE code = 'K08.1';
UPDATE icd10_codes SET title_uz = 'Jag'' kistasi' WHERE code = 'K09.0';
UPDATE icd10_codes SET title_uz = 'Aftoz stomatit' WHERE code = 'K12.0';
UPDATE icd10_codes SET title_uz = 'Glossit' WHERE code = 'K14.0';

UPDATE icd10_codes SET title_uz = 'Gipertenziya' WHERE code = 'I10';
UPDATE icd10_codes SET title_uz = '1-tip diabet' WHERE code = 'E10.9';
UPDATE icd10_codes SET title_uz = '2-tip diabet' WHERE code = 'E11.9';
UPDATE icd10_codes SET title_uz = 'Migren' WHERE code = 'G43.9';
UPDATE icd10_codes SET title_uz = 'COVID-19' WHERE code = 'U07.1';

-- For entries not manually translated yet, use Russian fallback to avoid English-only UI for Uzbek locale.
UPDATE icd10_codes
SET title_uz = title_ru
WHERE title_uz IS NULL AND title_ru IS NOT NULL;
