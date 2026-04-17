-- Manual SQL script to insert 10 reviews (4-5 stars) for each doctor
-- Run this script after ensuring you have doctors and patients in the database
-- This version uses a simpler approach with explicit INSERT statements

-- Sample review comments (one sentence each)
-- Rating distribution: Mix of 4 and 5 stars

-- Note: Replace {DOCTOR_ID} and {PATIENT_ID} with actual IDs from your database
-- You can get doctor IDs with: SELECT id FROM doctor_profiles;
-- You can get patient IDs with: SELECT id FROM patient_profiles;

-- Example for Doctor ID 1 (repeat for each doctor):
-- Review 1
INSERT INTO doctor_reviews (doctor_id, patient_id, rating, comment, created_at, updated_at)
SELECT 
    d.id,
    p.id,
    5,
    'Excellent doctor, very professional and caring.',
    CURRENT_TIMESTAMP - INTERVAL '15 days',
    CURRENT_TIMESTAMP - INTERVAL '15 days'
FROM doctor_profiles d, patient_profiles p
WHERE d.id = 1 AND p.id = (SELECT id FROM patient_profiles ORDER BY RANDOM() LIMIT 1)
ON CONFLICT (doctor_id, patient_id) DO NOTHING;

-- Review 2
INSERT INTO doctor_reviews (doctor_id, patient_id, rating, comment, created_at, updated_at)
SELECT 
    d.id,
    p.id,
    5,
    'Great bedside manner and thorough examination.',
    CURRENT_TIMESTAMP - INTERVAL '30 days',
    CURRENT_TIMESTAMP - INTERVAL '30 days'
FROM doctor_profiles d, patient_profiles p
WHERE d.id = 1 AND p.id = (SELECT id FROM patient_profiles ORDER BY RANDOM() LIMIT 1)
ON CONFLICT (doctor_id, patient_id) DO NOTHING;

-- Review 3
INSERT INTO doctor_reviews (doctor_id, patient_id, rating, comment, created_at, updated_at)
SELECT 
    d.id,
    p.id,
    4,
    'Highly recommend, very knowledgeable and patient.',
    CURRENT_TIMESTAMP - INTERVAL '45 days',
    CURRENT_TIMESTAMP - INTERVAL '45 days'
FROM doctor_profiles d, patient_profiles p
WHERE d.id = 1 AND p.id = (SELECT id FROM patient_profiles ORDER BY RANDOM() LIMIT 1)
ON CONFLICT (doctor_id, patient_id) DO NOTHING;

-- Review 4
INSERT INTO doctor_reviews (doctor_id, patient_id, rating, comment, created_at, updated_at)
SELECT 
    d.id,
    p.id,
    5,
    'Wonderful experience, felt heard and understood.',
    CURRENT_TIMESTAMP - INTERVAL '60 days',
    CURRENT_TIMESTAMP - INTERVAL '60 days'
FROM doctor_profiles d, patient_profiles p
WHERE d.id = 1 AND p.id = (SELECT id FROM patient_profiles ORDER BY RANDOM() LIMIT 1)
ON CONFLICT (doctor_id, patient_id) DO NOTHING;

-- Review 5
INSERT INTO doctor_reviews (doctor_id, patient_id, rating, comment, created_at, updated_at)
SELECT 
    d.id,
    p.id,
    5,
    'Best doctor I have ever visited, very attentive.',
    CURRENT_TIMESTAMP - INTERVAL '75 days',
    CURRENT_TIMESTAMP - INTERVAL '75 days'
FROM doctor_profiles d, patient_profiles p
WHERE d.id = 1 AND p.id = (SELECT id FROM patient_profiles ORDER BY RANDOM() LIMIT 1)
ON CONFLICT (doctor_id, patient_id) DO NOTHING;

-- Review 6
INSERT INTO doctor_reviews (doctor_id, patient_id, rating, comment, created_at, updated_at)
SELECT 
    d.id,
    p.id,
    4,
    'Professional service with excellent communication.',
    CURRENT_TIMESTAMP - INTERVAL '90 days',
    CURRENT_TIMESTAMP - INTERVAL '90 days'
FROM doctor_profiles d, patient_profiles p
WHERE d.id = 1 AND p.id = (SELECT id FROM patient_profiles ORDER BY RANDOM() LIMIT 1)
ON CONFLICT (doctor_id, patient_id) DO NOTHING;

-- Review 7
INSERT INTO doctor_reviews (doctor_id, patient_id, rating, comment, created_at, updated_at)
SELECT 
    d.id,
    p.id,
    5,
    'Very satisfied with the treatment and care provided.',
    CURRENT_TIMESTAMP - INTERVAL '105 days',
    CURRENT_TIMESTAMP - INTERVAL '105 days'
FROM doctor_profiles d, patient_profiles p
WHERE d.id = 1 AND p.id = (SELECT id FROM patient_profiles ORDER BY RANDOM() LIMIT 1)
ON CONFLICT (doctor_id, patient_id) DO NOTHING;

-- Review 8
INSERT INTO doctor_reviews (doctor_id, patient_id, rating, comment, created_at, updated_at)
SELECT 
    d.id,
    p.id,
    4,
    'Outstanding medical expertise and friendly approach.',
    CURRENT_TIMESTAMP - INTERVAL '120 days',
    CURRENT_TIMESTAMP - INTERVAL '120 days'
FROM doctor_profiles d, patient_profiles p
WHERE d.id = 1 AND p.id = (SELECT id FROM patient_profiles ORDER BY RANDOM() LIMIT 1)
ON CONFLICT (doctor_id, patient_id) DO NOTHING;

-- Review 9
INSERT INTO doctor_reviews (doctor_id, patient_id, rating, comment, created_at, updated_at)
SELECT 
    d.id,
    p.id,
    5,
    'Great doctor who takes time to explain everything clearly.',
    CURRENT_TIMESTAMP - INTERVAL '135 days',
    CURRENT_TIMESTAMP - INTERVAL '135 days'
FROM doctor_profiles d, patient_profiles p
WHERE d.id = 1 AND p.id = (SELECT id FROM patient_profiles ORDER BY RANDOM() LIMIT 1)
ON CONFLICT (doctor_id, patient_id) DO NOTHING;

-- Review 10
INSERT INTO doctor_reviews (doctor_id, patient_id, rating, comment, created_at, updated_at)
SELECT 
    d.id,
    p.id,
    5,
    'Exceptional care and attention to detail.',
    CURRENT_TIMESTAMP - INTERVAL '150 days',
    CURRENT_TIMESTAMP - INTERVAL '150 days'
FROM doctor_profiles d, patient_profiles p
WHERE d.id = 1 AND p.id = (SELECT id FROM patient_profiles ORDER BY RANDOM() LIMIT 1)
ON CONFLICT (doctor_id, patient_id) DO NOTHING;
