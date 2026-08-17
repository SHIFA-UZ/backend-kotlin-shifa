-- Add missing high-volume physician specialties (including Urology)
-- used during doctor onboarding. Idempotent on english unique key.

INSERT INTO professions (english, uzbek, category, display_order, is_active) VALUES
-- General & Primary Care
('Hospitalist', 'Gospitalist', 'General & Primary Care', 6, true),
('Adolescent Medicine Specialist', 'O''smirlar tibbiyoti shifokori', 'General & Primary Care', 7, true),
('Preventive Medicine Specialist', 'Profilaktika tibbiyoti shifokori', 'General & Primary Care', 8, true),

-- Medical Specialties
('Urologist', 'Urolog', 'Medical Specialties', 19, true),
('Andrologist', 'Androlog', 'Medical Specialties', 20, true),
('Hepatologist', 'Gepatolog', 'Medical Specialties', 21, true),
('Diabetologist', 'Diabetolog', 'Medical Specialties', 22, true),
('Hematologist-Oncologist', 'Gematolog-onkolog', 'Medical Specialties', 23, true),
('Interventional Cardiologist', 'Interventsion kardiolog', 'Medical Specialties', 24, true),
('Cardiac Electrophysiologist', 'Aritmolog', 'Medical Specialties', 25, true),
('Pain Medicine Specialist', 'Og''riq tibbiyoti mutaxassisi', 'Medical Specialties', 26, true),
('Addiction Medicine Specialist (Narcologist)', 'Narkolog', 'Medical Specialties', 27, true),
('Phlebologist', 'Flebolog', 'Medical Specialties', 28, true),
('Angiologist', 'Angiolog', 'Medical Specialties', 29, true),
('Sexologist', 'Seksolog', 'Medical Specialties', 30, true),
('Clinical Pharmacologist', 'Klinik farmakolog', 'Medical Specialties', 31, true),
('Obesity Medicine Specialist', 'Semizlik tibbiyoti mutaxassisi', 'Medical Specialties', 32, true),
('Radiation Oncologist', 'Radiatsion onkolog', 'Medical Specialties', 33, true),

-- Diagnostic & Laboratory
('Interventional Radiologist', 'Interventsion radiolog', 'Diagnostic & Laboratory', 7, true),

-- Surgical Specialties
('Colorectal Surgeon (Proctologist)', 'Koloproktolog', 'Surgical Specialties', 12, true),
('Thoracic Surgeon', 'Torakal jarroh', 'Surgical Specialties', 13, true),
('Cardiac Surgeon', 'Yurak jarrohi', 'Surgical Specialties', 14, true),
('Abdominal Surgeon', 'Qorin bo''shlig''i jarrohi', 'Surgical Specialties', 15, true),
('Hand Surgeon', 'Qo''l jarrohi', 'Surgical Specialties', 16, true),
('Spine Surgeon', 'Umurtqa pog''onasi jarrohi', 'Surgical Specialties', 17, true),
('Foot and Ankle Surgeon', 'Oyoq-to''piq jarrohi', 'Surgical Specialties', 18, true),
('Bariatric Surgeon', 'Bariatrik jarroh', 'Surgical Specialties', 19, true),
('Burn Specialist', 'Kuyish bo''yicha jarroh', 'Surgical Specialties', 20, true),
('Head and Neck Surgeon', 'Bosh va bo''yin jarrohi', 'Surgical Specialties', 21, true),
('Breast Surgeon', 'Ko''krak bezi jarrohi', 'Surgical Specialties', 22, true),

-- Women's & Reproductive Health
('Maternal-Fetal Medicine Specialist', 'Perinatolog', 'Women''s & Reproductive Health', 6, true),
('Gynecologic Oncologist', 'Ginekolog-onkolog', 'Women''s & Reproductive Health', 7, true),
('Urogynecologist', 'Uroginekolog', 'Women''s & Reproductive Health', 8, true),

-- ENT & Senses
('Retina Specialist', 'Retina mutaxassisi', 'ENT & Senses', 5, true),

-- Dental Specialties
('Endodontist', 'Endodont', 'Dental Specialties', 7, true),
('Implantologist', 'Implantolog', 'Dental Specialties', 8, true),

-- Children & Development
('Pediatric Endocrinologist', 'Bolalar endokrinologi', 'Children & Development', 6, true),
('Pediatric Gastroenterologist', 'Bolalar gastroenterologi', 'Children & Development', 7, true),
('Pediatric Pulmonologist', 'Bolalar pulmonologi', 'Children & Development', 8, true),
('Pediatric Nephrologist', 'Bolalar nefrologi', 'Children & Development', 9, true),
('Pediatric Oncologist', 'Bolalar onkologi', 'Children & Development', 10, true),
('Pediatric Hematologist-Oncologist', 'Bolalar gematolog-onkologi', 'Children & Development', 11, true),
('Pediatric Urologist', 'Bolalar urologi', 'Children & Development', 12, true),
('Pediatric Otolaryngologist', 'Bolalar LOR shifokori', 'Children & Development', 13, true),
('Pediatric Ophthalmologist', 'Bolalar oftalmologi', 'Children & Development', 14, true),
('Pediatric Orthopedist', 'Bolalar ortopedi', 'Children & Development', 15, true),
('Pediatric Allergist', 'Bolalar allergologi', 'Children & Development', 16, true),
('Pediatric Infectious Disease Specialist', 'Bolalar infeksionisti', 'Children & Development', 17, true),
('Pediatric Dermatologist', 'Bolalar dermatologi', 'Children & Development', 18, true),

-- Emergency & Intensive Care
('Pediatric Emergency Physician', 'Bolalar shoshilinch yordam shifokori', 'Emergency & Intensive Care', 5, true),
('Pediatric Intensivist', 'Bolalar reanimatologi', 'Emergency & Intensive Care', 6, true),
('Pediatric Anesthesiologist', 'Bolalar anesteziologi', 'Emergency & Intensive Care', 7, true),
('Hyperbaric Medicine Specialist', 'Giperbarik tibbiyot shifokori', 'Emergency & Intensive Care', 8, true),

-- Other Important Specialties
('Endoscopist', 'Endoskopist', 'Other Important Specialties', 10, true),
('Aesthetic Medicine Specialist', 'Estetik tibbiyot shifokori', 'Other Important Specialties', 11, true),
('Trichologist', 'Trikolog', 'Other Important Specialties', 12, true),
('Podiatrist', 'Podolog', 'Other Important Specialties', 13, true),
('Forensic Physician', 'Sud-tibbiyot eksperti', 'Other Important Specialties', 14, true),
('Wound Care Specialist', 'Yara davolash mutaxassisi', 'Other Important Specialties', 15, true),
('Epileptologist', 'Epileptolog', 'Other Important Specialties', 16, true),
('Stroke Neurologist', 'Insult mutaxassisi', 'Other Important Specialties', 17, true),
('Tropical Medicine Specialist', 'Tropik tibbiyot shifokori', 'Other Important Specialties', 18, true)
ON CONFLICT (english) DO NOTHING;
