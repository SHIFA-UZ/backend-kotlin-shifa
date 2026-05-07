-- Expand Uzbek ICD-10 titles for seeded catalog entries.
-- This improves Uzbek UI labels beyond RU fallback.

UPDATE icd10_codes SET title_uz = 'O''tkir nazofaringit' WHERE code = 'J00';
UPDATE icd10_codes SET title_uz = 'O''tkir sinusit' WHERE code = 'J01.9';
UPDATE icd10_codes SET title_uz = 'O''tkir faringit' WHERE code = 'J02.9';
UPDATE icd10_codes SET title_uz = 'O''tkir tonzillit' WHERE code = 'J03.9';
UPDATE icd10_codes SET title_uz = 'O''tkir laringit' WHERE code = 'J04.0';
UPDATE icd10_codes SET title_uz = 'Yuqori nafas yo''llari infeksiyasi' WHERE code = 'J06.9';
UPDATE icd10_codes SET title_uz = 'Pnevmoniya (aniqlanmagan)' WHERE code = 'J18.9';
UPDATE icd10_codes SET title_uz = 'O''tkir bronxit' WHERE code = 'J20';
UPDATE icd10_codes SET title_uz = 'Astma (aniqlanmagan)' WHERE code = 'J45.9';
UPDATE icd10_codes SET title_uz = 'KOAH (aniqlanmagan)' WHERE code = 'J44.9';

UPDATE icd10_codes SET title_uz = 'Gipertenziv yurak kasalligi' WHERE code = 'I11.9';
UPDATE icd10_codes SET title_uz = 'Stenokardiya' WHERE code = 'I20.9';
UPDATE icd10_codes SET title_uz = 'Miokard infarkti' WHERE code = 'I21.9';
UPDATE icd10_codes SET title_uz = 'Koronar arteriya kasalligi' WHERE code = 'I25.10';
UPDATE icd10_codes SET title_uz = 'Bo''lmachalar fibrillyatsiyasi' WHERE code = 'I48.0';
UPDATE icd10_codes SET title_uz = 'Yurak yetishmovchiligi' WHERE code = 'I50.9';

UPDATE icd10_codes SET title_uz = 'Semizlik' WHERE code = 'E66.9';
UPDATE icd10_codes SET title_uz = 'Gipotireoz' WHERE code = 'E03.9';
UPDATE icd10_codes SET title_uz = 'Gipertireoz' WHERE code = 'E05.9';

UPDATE icd10_codes SET title_uz = 'Gastroezofageal reflyuks kasalligi (GERD)' WHERE code = 'K21.9';
UPDATE icd10_codes SET title_uz = 'Gastrit' WHERE code = 'K29.7';
UPDATE icd10_codes SET title_uz = 'Gastroenterit' WHERE code = 'K52.9';
UPDATE icd10_codes SET title_uz = 'O''t-tosh kasalligi' WHERE code = 'K80.2';
UPDATE icd10_codes SET title_uz = 'Appenditsit' WHERE code = 'K35.9';

UPDATE icd10_codes SET title_uz = 'Adontiya' WHERE code = 'K00.0';
UPDATE icd10_codes SET title_uz = 'Ortiqcha (superkomplekt) tishlar' WHERE code = 'K00.1';
UPDATE icd10_codes SET title_uz = 'Retinirlangan tishlar' WHERE code = 'K01.0';
UPDATE icd10_codes SET title_uz = 'Tishlar ishqalanib yeyilishi' WHERE code = 'K03.0';
UPDATE icd10_codes SET title_uz = 'Boshqa tish kasalliklari' WHERE code = 'K08.8';
UPDATE icd10_codes SET title_uz = 'Jag''ning yallig''lanish holati' WHERE code = 'K10.2';
UPDATE icd10_codes SET title_uz = 'Boshqa stomatit' WHERE code = 'K12.1';
UPDATE icd10_codes SET title_uz = 'Lab kasalliklari' WHERE code = 'K13.0';

UPDATE icd10_codes SET title_uz = 'Taranglik tipidagi bosh og''rig''i' WHERE code = 'G44.2';
UPDATE icd10_codes SET title_uz = 'Epilepsiya' WHERE code = 'G40.9';

UPDATE icd10_codes SET title_uz = 'Bel og''rig''i' WHERE code = 'M54.5';
UPDATE icd10_codes SET title_uz = 'Tizza bo''g''imi osteoartrozi' WHERE code = 'M17.9';
UPDATE icd10_codes SET title_uz = 'Bo''g''im og''rig''i' WHERE code = 'M25.5';

UPDATE icd10_codes SET title_uz = 'Infeksion diareya' WHERE code = 'A09';
UPDATE icd10_codes SET title_uz = 'Virusli infeksiya' WHERE code = 'B34.9';

UPDATE icd10_codes SET title_uz = 'Atopik dermatit' WHERE code = 'L20.9';
UPDATE icd10_codes SET title_uz = 'Dermatit' WHERE code = 'L30.9';
UPDATE icd10_codes SET title_uz = 'Akne' WHERE code = 'L70.0';

UPDATE icd10_codes SET title_uz = 'Tashvish buzilishi' WHERE code = 'F41.1';
UPDATE icd10_codes SET title_uz = 'Depressiya' WHERE code = 'F32.9';
UPDATE icd10_codes SET title_uz = 'Uyqusizlik' WHERE code = 'F51.0';

UPDATE icd10_codes SET title_uz = 'Siydik yo''llari infeksiyasi (UTI)' WHERE code = 'N39.0';
UPDATE icd10_codes SET title_uz = 'Buyrak toshi' WHERE code = 'N20.0';

UPDATE icd10_codes SET title_uz = 'Isitma' WHERE code = 'R50.9';
UPDATE icd10_codes SET title_uz = 'Bosh og''rig''i' WHERE code = 'R51';
UPDATE icd10_codes SET title_uz = 'Holsizlik' WHERE code = 'R53';
