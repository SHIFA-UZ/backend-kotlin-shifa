-- Quick cleanup: Delete duplicate patient profiles
-- Keeps the most recent patient (highest ID) for each phone/email
-- WARNING: Make sure to update foreign keys first if needed

-- First, let's see what duplicates exist
SELECT 'Duplicates by phone:' as info, phone, COUNT(*) as count, ARRAY_AGG(id ORDER BY id) as ids
FROM patient_profiles
WHERE phone IS NOT NULL AND phone != ''
GROUP BY phone
HAVING COUNT(*) > 1;

SELECT 'Duplicates by email:' as info, email, COUNT(*) as count, ARRAY_AGG(id ORDER BY id) as ids
FROM patient_profiles
WHERE email IS NOT NULL AND email != ''
GROUP BY email
HAVING COUNT(*) > 1;

-- Update foreign keys to point to the most recent patient
-- This ensures no data is lost when we delete duplicates

-- Update appointments
UPDATE appointments a
SET patient_id = (SELECT MAX(id) FROM patient_profiles p 
                  WHERE (p.phone = (SELECT phone FROM patient_profiles WHERE id = a.patient_id) 
                         AND p.phone IS NOT NULL AND p.phone != '')
                     OR (p.email = (SELECT email FROM patient_profiles WHERE id = a.patient_id) 
                         AND p.email IS NOT NULL AND p.email != ''))
WHERE EXISTS (SELECT 1 FROM patient_profiles p1, patient_profiles p2
              WHERE p1.id = a.patient_id
              AND ((p1.phone IS NOT NULL AND p1.phone != '' AND p2.phone = p1.phone AND p2.id > p1.id)
                   OR (p1.email IS NOT NULL AND p1.email != '' AND p2.email = p1.email AND p2.id > p1.id)));

-- Update doctor_reviews
UPDATE doctor_reviews dr
SET patient_id = (SELECT MAX(id) FROM patient_profiles p 
                  WHERE (p.phone = (SELECT phone FROM patient_profiles WHERE id = dr.patient_id) 
                         AND p.phone IS NOT NULL AND p.phone != '')
                     OR (p.email = (SELECT email FROM patient_profiles WHERE id = dr.patient_id) 
                         AND p.email IS NOT NULL AND p.email != ''))
WHERE EXISTS (SELECT 1 FROM patient_profiles p1, patient_profiles p2
              WHERE p1.id = dr.patient_id
              AND ((p1.phone IS NOT NULL AND p1.phone != '' AND p2.phone = p1.phone AND p2.id > p1.id)
                   OR (p1.email IS NOT NULL AND p1.email != '' AND p2.email = p1.email AND p2.id > p1.id)));

-- Now delete duplicates (keeps highest ID)
DELETE FROM patient_profiles
WHERE id IN (
    SELECT id FROM (
        SELECT id, ROW_NUMBER() OVER (PARTITION BY phone ORDER BY id DESC) as rn
        FROM patient_profiles WHERE phone IS NOT NULL AND phone != ''
    ) t WHERE t.rn > 1
);

DELETE FROM patient_profiles
WHERE id IN (
    SELECT id FROM (
        SELECT id, ROW_NUMBER() OVER (PARTITION BY email ORDER BY id DESC) as rn
        FROM patient_profiles WHERE email IS NOT NULL AND email != ''
    ) t WHERE t.rn > 1
);

-- Verify
SELECT 'After cleanup - Total patients:' as info, COUNT(*) FROM patient_profiles;
