-- Create professions table
CREATE TABLE professions (
    id BIGSERIAL PRIMARY KEY,
    english VARCHAR(255) NOT NULL UNIQUE,
    uzbek VARCHAR(255) NOT NULL,
    category VARCHAR(100),
    display_order INTEGER DEFAULT 0,
    is_active BOOLEAN NOT NULL DEFAULT true
);

-- Create indexes for efficient searching
CREATE INDEX idx_profession_english ON professions(english);
CREATE INDEX idx_profession_uzbek ON professions(uzbek);
CREATE INDEX idx_profession_category ON professions(category);
CREATE INDEX idx_profession_active ON professions(is_active);

-- Insert all professions with categories
INSERT INTO professions (english, uzbek, category, display_order, is_active) VALUES
-- 🩺 General & Primary Care
('General Practitioner (GP)', 'Umumiy amaliyot shifokori', 'General & Primary Care', 1, true),
('Family Physician', 'Oilaviy shifokor', 'General & Primary Care', 2, true),
('Internist (Internal Medicine)', 'Terapevt', 'General & Primary Care', 3, true),
('Pediatrician', 'Pediatr', 'General & Primary Care', 4, true),
('Geriatrician', 'Geriatr (keksalar shifokori)', 'General & Primary Care', 5, true),

-- 🫀 Medical (Non-Surgical) Specialties
('Cardiologist', 'Kardiolog', 'Medical Specialties', 1, true),
('Endocrinologist', 'Endokrinolog', 'Medical Specialties', 2, true),
('Gastroenterologist', 'Gastroenterolog', 'Medical Specialties', 3, true),
('Pulmonologist', 'Pulmonolog', 'Medical Specialties', 4, true),
('Nephrologist', 'Nefrolog', 'Medical Specialties', 5, true),
('Hematologist', 'Gematolog', 'Medical Specialties', 6, true),
('Rheumatologist', 'Revmatolog', 'Medical Specialties', 7, true),
('Allergist / Immunologist', 'Allergolog / Immunolog', 'Medical Specialties', 8, true),
('Infectious Disease Specialist', 'Infeksionist', 'Medical Specialties', 9, true),
('Oncologist', 'Onkolog', 'Medical Specialties', 10, true),
('Neurologist', 'Nevrolog', 'Medical Specialties', 11, true),
('Psychiatrist', 'Psixiatr', 'Medical Specialties', 12, true),
('Dermatologist', 'Dermatolog', 'Medical Specialties', 13, true),
('Venereologist', 'Venerolog', 'Medical Specialties', 14, true),
('Phthisiatrician (TB specialist)', 'Ftiziatr', 'Medical Specialties', 15, true),
('Toxicologist', 'Toksikolog', 'Medical Specialties', 16, true),
('Sports Medicine Doctor', 'Sport shifokori', 'Medical Specialties', 17, true),
('Occupational Medicine Specialist', 'Mehnat tibbiyoti shifokori', 'Medical Specialties', 18, true),

-- 🧠 Mental Health & Behavioral
('Psychotherapist', 'Psixoterapevt', 'Mental Health & Behavioral', 1, true),
('Clinical Psychologist', 'Klinik psixolog', 'Mental Health & Behavioral', 2, true),
('Child Psychiatrist', 'Bolalar psixiatri', 'Mental Health & Behavioral', 3, true),

-- 🩻 Diagnostic & Laboratory
('Radiologist', 'Radiolog', 'Diagnostic & Laboratory', 1, true),
('Ultrasound Doctor (Sonographer)', 'UZI shifokori', 'Diagnostic & Laboratory', 2, true),
('Pathologist', 'Patolog', 'Diagnostic & Laboratory', 3, true),
('Laboratory Doctor', 'Laboratoriya shifokori', 'Diagnostic & Laboratory', 4, true),
('Nuclear Medicine Specialist', 'Yadro tibbiyoti shifokori', 'Diagnostic & Laboratory', 5, true),
('Functional Diagnostics Doctor', 'Funksional diagnostika shifokori', 'Diagnostic & Laboratory', 6, true),

-- 🔪 Surgical Specialties
('General Surgeon', 'Umumiy jarroh', 'Surgical Specialties', 1, true),
('Cardiothoracic Surgeon', 'Yurak-ko''krak jarrohi', 'Surgical Specialties', 2, true),
('Neurosurgeon', 'Neyroxirurg', 'Surgical Specialties', 3, true),
('Orthopedic Surgeon', 'Ortoped-jarroh', 'Surgical Specialties', 4, true),
('Trauma Surgeon', 'Travmatolog', 'Surgical Specialties', 5, true),
('Plastic Surgeon', 'Plastik jarroh', 'Surgical Specialties', 6, true),
('Vascular Surgeon', 'Qon tomir jarrohi', 'Surgical Specialties', 7, true),
('Pediatric Surgeon', 'Bolalar jarrohi', 'Surgical Specialties', 8, true),
('Oncologic Surgeon', 'Onkojarroh', 'Surgical Specialties', 9, true),
('Maxillofacial Surgeon', 'Yuz-jag'' jarrohi', 'Surgical Specialties', 10, true),
('Transplant Surgeon', 'Transplantolog', 'Surgical Specialties', 11, true),

-- 👶 Women's & Reproductive Health
('Gynecologist', 'Ginekolog', 'Women''s & Reproductive Health', 1, true),
('Obstetrician', 'Akusher', 'Women''s & Reproductive Health', 2, true),
('Obstetrician-Gynecologist (OB-GYN)', 'Akusher-ginekolog', 'Women''s & Reproductive Health', 3, true),
('Reproductive Medicine Specialist', 'Reproduktolog', 'Women''s & Reproductive Health', 4, true),
('Mammologist', 'Mammolog', 'Women''s & Reproductive Health', 5, true),

-- 👁️👂 ENT & Senses
('Ophthalmologist', 'Oftalmolog (ko''z shifokori)', 'ENT & Senses', 1, true),
('Otolaryngologist (ENT)', 'LOR shifokori', 'ENT & Senses', 2, true),
('Audiologist', 'Audiolog', 'ENT & Senses', 3, true),
('Phoniatrician', 'Foniatr', 'ENT & Senses', 4, true),

-- 🦷 Dental Specialties
('Dentist', 'Stomatolog', 'Dental Specialties', 1, true),
('Orthodontist', 'Ortodont', 'Dental Specialties', 2, true),
('Oral Surgeon', 'Og''iz bo''shlig''i jarrohi', 'Dental Specialties', 3, true),
('Periodontist', 'Parodontolog', 'Dental Specialties', 4, true),
('Prosthodontist', 'Ortopedik stomatolog', 'Dental Specialties', 5, true),
('Pediatric Dentist', 'Bolalar stomatologi', 'Dental Specialties', 6, true),

-- 🧒 Children & Development
('Neonatologist', 'Neonatolog', 'Children & Development', 1, true),
('Pediatric Neurologist', 'Bolalar nevrologi', 'Children & Development', 2, true),
('Pediatric Cardiologist', 'Bolalar kardiologi', 'Children & Development', 3, true),
('Developmental Pediatrician', 'Rivojlanish pediatri', 'Children & Development', 4, true),
('Speech Therapist', 'Logoped', 'Children & Development', 5, true),

-- 🚑 Emergency & Intensive Care
('Emergency Physician', 'Shoshilinch tibbiyot shifokori', 'Emergency & Intensive Care', 1, true),
('Intensivist (ICU Doctor)', 'Reanimatolog', 'Emergency & Intensive Care', 2, true),
('Anesthesiologist', 'Anesteziolog', 'Emergency & Intensive Care', 3, true),
('Anesthesiologist-Resuscitator', 'Anesteziolog-reanimatolog', 'Emergency & Intensive Care', 4, true),

-- 🧬 Other Important Specialties
('Geneticist', 'Genetik', 'Other Important Specialties', 1, true),
('Epidemiologist', 'Epidemiolog', 'Other Important Specialties', 2, true),
('Public Health Specialist', 'Jamoat salomatligi mutaxassisi', 'Other Important Specialties', 3, true),
('Rehabilitation Doctor', 'Reabilitolog', 'Other Important Specialties', 4, true),
('Physiotherapist', 'Fizioterapevt', 'Other Important Specialties', 5, true),
('Manual Therapist', 'Manual terapevt', 'Other Important Specialties', 6, true),
('Nutritionist / Dietitian', 'Dietolog', 'Other Important Specialties', 7, true),
('Palliative Care Specialist', 'Palliativ yordam shifokori', 'Other Important Specialties', 8, true),
('Sleep Medicine Specialist', 'Uyqu bo''yicha mutaxassis', 'Other Important Specialties', 9, true);
